package com.iitm.beacon.testsupport;

import java.util.Objects;
import java.util.function.Supplier;
import org.testcontainers.lifecycle.Startable;

/**
 * One container shared by every test in the JVM (Testcontainers' "singleton
 * container" pattern): created and started on the first {@link #get()},
 * then handed, still running, to every later caller, from any thread. It is
 * never stopped here; Testcontainers' Ryuk sidecar removes it when the JVM
 * exits.
 *
 * <p>A container that fails to start is stopped again and not remembered,
 * and the failure is rethrown with an explanation of what the tests need,
 * so a missing Docker daemon fails each test that needs the container with
 * a readable message rather than skipping it, and a later call tries again.
 * An exception from the factory itself (e.g. a malformed image name) is
 * passed through unchanged.
 */
public final class SharedContainer<T extends Startable> {

    private final String description;
    private final Supplier<T> factory;
    private volatile T started;

    /**
     * @param description what the container is, for the failure message
     *     (e.g. {@code "PostgreSQL (postgres:16-alpine)"})
     * @param factory creates the (not yet started) container
     */
    public SharedContainer(String description, Supplier<T> factory) {
        this.description = Objects.requireNonNull(description, "description");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    /** The running shared container, started by this call if no earlier call has. */
    public T get() {
        T current = started;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (started == null) {
                started = createAndStart();
            }
            return started;
        }
    }

    private T createAndStart() {
        T container = factory.get();
        try {
            container.start();
            return container;
        } catch (RuntimeException startFailure) {
            try {
                container.stop();
            } catch (RuntimeException stopFailure) {
                startFailure.addSuppressed(stopFailure);
            }
            throw new IllegalStateException("Could not start the shared " + description + " test container."
                    + " The tests that use it are part of the normal `./mvnw test` run and are never skipped:"
                    + " they need a running Docker daemon that Testcontainers can reach. Start Docker; if it"
                    + " listens on a non-default socket (e.g. Docker Desktop on Linux), point Testcontainers at"
                    + " it with the DOCKER_HOST environment variable or docker.host in"
                    + " ~/.testcontainers.properties.", startFailure);
        }
    }
}
