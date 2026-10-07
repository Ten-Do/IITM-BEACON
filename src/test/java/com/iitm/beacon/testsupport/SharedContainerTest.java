package com.iitm.beacon.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.lifecycle.Startable;

/**
 * {@link SharedContainer} with stand-in containers, so none of this needs
 * Docker: start-once semantics, thread safety, and the explanation given
 * when a container can't be started.
 */
class SharedContainerTest {

    /** A stand-in container that counts its starts and stops, and can be told to fail either. */
    private static final class FakeContainer implements Startable {

        private final AtomicInteger starts = new AtomicInteger();
        private final AtomicInteger stops = new AtomicInteger();
        private final RuntimeException startFailure;
        private final RuntimeException stopFailure;
        private final long startMillis;

        FakeContainer() {
            this(null, null, 0);
        }

        FakeContainer(RuntimeException startFailure, RuntimeException stopFailure, long startMillis) {
            this.startFailure = startFailure;
            this.stopFailure = stopFailure;
            this.startMillis = startMillis;
        }

        @Override
        public void start() {
            starts.incrementAndGet();
            if (startMillis > 0) {
                try {
                    Thread.sleep(startMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void stop() {
            stops.incrementAndGet();
            if (stopFailure != null) {
                throw stopFailure;
            }
        }
    }

    private static IllegalStateException noDocker() {
        return new IllegalStateException("Could not find a valid Docker environment");
    }

    @Test
    void get_firstCall_createsAndStartsExactlyOneContainer() {
        AtomicInteger created = new AtomicInteger();
        FakeContainer container = new FakeContainer();
        SharedContainer<FakeContainer> shared = new SharedContainer<>("Fake", () -> {
            created.incrementAndGet();
            return container;
        });

        assertThat(shared.get()).isSameAs(container);
        assertThat(created).hasValue(1);
        assertThat(container.starts).hasValue(1);
        assertThat(container.stops).hasValue(0);
    }

    @Test
    void get_repeatedCalls_returnTheSameContainer_withoutCreatingOrStartingAnother() {
        AtomicInteger created = new AtomicInteger();
        SharedContainer<FakeContainer> shared = new SharedContainer<>("Fake", () -> {
            created.incrementAndGet();
            return new FakeContainer();
        });

        FakeContainer first = shared.get();
        FakeContainer second = shared.get();
        FakeContainer third = shared.get();

        assertThat(second).isSameAs(first);
        assertThat(third).isSameAs(first);
        assertThat(created).hasValue(1);
        assertThat(first.starts).hasValue(1);
    }

    @Test
    void get_concurrentFirstCalls_startExactlyOneContainer_andAllGetIt() throws Exception {
        int threads = 16;
        AtomicInteger created = new AtomicInteger();
        // A slow start widens the window in which a second thread could slip in.
        SharedContainer<FakeContainer> shared = new SharedContainer<>("Fake", () -> {
            created.incrementAndGet();
            return new FakeContainer(null, null, 50);
        });
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<FakeContainer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return shared.get();
                }));
            }
            go.countDown();

            FakeContainer first = results.get(0).get(10, TimeUnit.SECONDS);
            for (Future<FakeContainer> result : results) {
                assertThat(result.get(10, TimeUnit.SECONDS)).isSameAs(first);
            }
            assertThat(created).hasValue(1);
            assertThat(first.starts).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void get_startFails_throwsAnExplanationThatNamesTheContainerAndDocker_keepingTheOriginalCause() {
        IllegalStateException original = noDocker();
        SharedContainer<FakeContainer> shared =
                new SharedContainer<>("PostgreSQL (postgres:16-alpine)", () -> new FakeContainer(original, null, 0));

        assertThatThrownBy(shared::get)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PostgreSQL (postgres:16-alpine)")
                .hasMessageContaining("Docker")
                .hasMessageContaining("DOCKER_HOST")
                .hasMessageContaining("~/.testcontainers.properties")
                .hasCause(original);
    }

    @Test
    void get_startFails_stopsTheContainerThatFailedToStart() {
        FakeContainer failing = new FakeContainer(noDocker(), null, 0);
        SharedContainer<FakeContainer> shared = new SharedContainer<>("Fake", () -> failing);

        assertThatThrownBy(shared::get).isInstanceOf(IllegalStateException.class);

        assertThat(failing.stops).hasValue(1);
    }

    @Test
    void get_startAndCleanupStopBothFail_reportsTheStartFailure_withTheStopFailureSuppressed() {
        IllegalStateException startFailure = noDocker();
        IllegalStateException stopFailure = new IllegalStateException("stop failed too");
        SharedContainer<FakeContainer> shared =
                new SharedContainer<>("Fake", () -> new FakeContainer(startFailure, stopFailure, 0));

        assertThatThrownBy(shared::get)
                .isInstanceOf(IllegalStateException.class)
                .hasCause(startFailure)
                .satisfies(thrown -> assertThat(thrown.getCause().getSuppressed()).containsExactly(stopFailure));
    }

    @Test
    void get_afterAFailedStart_theNextCallTriesAgain_andThenKeepsTheContainerThatStarted() {
        FakeContainer failing = new FakeContainer(noDocker(), null, 0);
        FakeContainer working = new FakeContainer();
        List<FakeContainer> toCreate = new ArrayList<>(List.of(failing, working));
        AtomicInteger created = new AtomicInteger();
        SharedContainer<FakeContainer> shared = new SharedContainer<>("Fake", () -> {
            created.incrementAndGet();
            return toCreate.remove(0);
        });

        assertThatThrownBy(shared::get).isInstanceOf(IllegalStateException.class);

        assertThat(shared.get()).isSameAs(working);
        assertThat(shared.get()).isSameAs(working);
        assertThat(created).hasValue(2);
    }

    @Test
    void get_factoryThrows_propagatesThatExceptionUnchanged() {
        IllegalArgumentException badImage = new IllegalArgumentException("bad image name");
        SharedContainer<FakeContainer> shared = new SharedContainer<>("Fake", () -> {
            throw badImage;
        });

        assertThatThrownBy(shared::get).isSameAs(badImage);
    }

    @Test
    void constructor_nullDescriptionOrFactory_isRejectedImmediately() {
        assertThatNullPointerException().isThrownBy(() -> new SharedContainer<FakeContainer>(null, FakeContainer::new));
        assertThatNullPointerException().isThrownBy(() -> new SharedContainer<FakeContainer>("Fake", null));
    }
}
