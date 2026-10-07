# NFR Verification

The M7 verification pass (`milestones.md`): how each Quality Attribute Scenario in `nfr.md` is
shown to hold, and what the last run measured. `test_plan.md` says *what* is tested and why;
this file records the evidence. Update it whenever a run is repeated or a test moves.

- **Date of this pass:** 2026-10-07.
- **Automated suite:** `./mvnw test` (unit, repository, MockMvc and real-server tests on H2,
  plus the PostgreSQL 16 tests through Testcontainers — needs Docker). Browser end-to-end tests:
  `make e2e`.
- **Performance run:** `make perf` (see §2).
- Open points found by the pass are tracked in `security-review.md` and `BACKLOG.md`, not here.

## 1. Summary

| NFR | Verified by | Result |
|---|---|---|
| `NFR-GALLERY-PERFORMANCE` | `make perf`, gallery scenarios | met — p95 ≤ 82 ms (limit 2 s), §2 |
| `NFR-SEARCH-PERFORMANCE` | `make perf`, filter/search scenarios | met — p95 ≤ 166 ms (limit 1 s), §2 |
| `NFR-MODERATION-QUEUE-PERFORMANCE` | `make perf`, moderation scenarios | met — p95 ≤ 190 ms (limit 2 s), §2 |
| `NFR-DASHBOARD-PERFORMANCE` | `make perf`, dashboard scenarios | met — p95 ≤ 68 ms (limit 2 s), §2 |
| `NFR-ADMIN-OTP-BRUTEFORCE` | automated tests, §3.1 | met |
| `NFR-VISITOR-OTP-BRUTEFORCE` | automated tests, §3.2 | met |
| `NFR-CONTACT-CONFIDENTIALITY` | automated tests, §3.3 | met |
| `NFR-UPLOAD-SPOOFING` | automated tests, §3.4 | met |
| `NFR-ERROR-TRANSPARENCY` | automated tests, §3.5 | met |
| `NFR-CATALOG-CONFIGURABILITY` | automated tests, §3.6 | met |

## 2. Performance

**How:** `make perf` runs `./mvnw -Pperf test` on the host — only the `@Tag("perf")` test
`perf.PerformanceNfrTest`, which the normal suite skips. It starts the app on a random port
against PostgreSQL 16 (Testcontainers), seeds a production-sized dataset through the app's own
repositories (`perf.PerfDataSeeder`, fixed seed, so contacts are encrypted as in production),
logs in as the admin over real HTTP (with CSRF), and measures each scenario: 20 warm-up
requests, 200 sequential requests, then 5 concurrent clients × 40 requests. Latency is request
sent → full body received, measured by the client; percentiles are nearest-rank. A scenario
passes when every response is a 200 and its p95 is within the NFR. The full report is written
to `target/perf/results.md`.

**Run of 2026-10-07** — 12 CPUs, 3.9 GB heap, OpenJDK 21.0.12, Spring Boot 3.5.16, PostgreSQL
16.14 in Docker Desktop (every SQL round trip crosses the VM boundary), client in the same JVM
as the app. Dataset: 500 approved / 60 pending / 40 rejected testimonials, 40 countries (the
largest, DE, has 108), 2420 sections (answers 305–1499 chars, avg 936), 3667 photo rows, 932
encrypted contacts, 1543 achievement ticks. Run twice; the numbers agreed.

| Scenario | Request | NFR (p95 ≤) | p95 sequential | p95 5 concurrent | SQL / request | Result |
|---|---|---|---:|---:|---:|---|
| Gallery page, first page | `/gallery` | GALLERY (2 s) | 82 ms | 67 ms | 110 | pass |
| Gallery API, first page | `/api/gallery/testimonials` | GALLERY (2 s) | 44 ms | 57 ms | 106 | pass |
| Filter: country | `?country=DE` | SEARCH (1 s) | 45 ms | 57 ms | 90 | pass |
| Filter: topic group | `?groupIds=1` | SEARCH (1 s) | 55 ms | 51 ms | 91 | pass |
| Filter: standalone topic | `?topicIds=28` | SEARCH (1 s) | 36 ms | 58 ms | 101 | pass |
| Search: common word (500 hits) | `?q=campus` | SEARCH (1 s) | 75 ms | 166 ms | 106 | pass |
| Search: rare word (3 hits) | `?q=kolam` | SEARCH (1 s) | 28 ms | 73 ms | 26 | pass |
| Combined: country + group + search | API | SEARCH (1 s) | 86 ms | 129 ms | 82 | pass |
| Combined, gallery page | `/gallery?…` | SEARCH (1 s) | 115 ms | 160 ms | 86 | pass |
| Moderation queue page | `/moderation/queue` | MODERATION-QUEUE (2 s) | 113 ms | 190 ms | 348 | pass |
| Moderation API, pending | `/api/moderation/testimonials/pending` | MODERATION-QUEUE (2 s) | 111 ms | 174 ms | 348 | pass |
| Dashboard page | `/` | DASHBOARD (2 s) | 22 ms | 68 ms | 5 | pass |
| Analytics API, summary | `/api/analytics/summary` | DASHBOARD (2 s) | 10 ms | 13 ms | 5 | pass |

All 26 measurements pass; the worst p95 is about a tenth of its limit. Two observations that
are not misses: a page of 20 cards issues about 110 SQL statements and a moderation page about
350 (one query per lazily loaded collection — `BACKLOG.md`), and the common-word search is the
slowest public filter (an unindexed `LIKE '%word%'` over all section text, which grows with the
amount of text). Only the default page size (20) and the first page were measured.

## 3. Security and other NFRs

Each Response Measure, split into checkable claims, with the tests that prove it. Paths are
under `src/test/java/com/iitm/beacon/`.

### 3.1 NFR-ADMIN-OTP-BRUTEFORCE

| Claim | Tests |
|---|---|
| At most `max-attempts` wrong codes per OTP; exhausting them invalidates it | `adminauth/OtpServiceTest` (boundaries, concurrent decrements), `adminauth/AdminOtpBruteForceOverHttpTest` (over HTTP) |
| Expired code rejected | `adminauth/OtpServiceTest`, `AdminAuthControllerExpiredOtpTest`, `AdminAuthViewControllerExpiredOtpTest` |
| Max attempts and TTL configurable | `adminauth/OtpServiceConfigurabilityTest` |
| Request rate per email and per IP, server-side (429) | `adminauth/OtpServiceRateLimitTest`, `AdminAuthControllerRateLimitTest`, `AdminAuthViewControllerRateLimitTest`, `adminauth/AdminOtpRequestPerIpLimitTest` |
| Shipped defaults: TTL 5 min, 5 attempts, 1 request/min per email, 5/min per IP, each overridable by env var | `config/OtpDefaultsConfigTest` |

### 3.2 NFR-VISITOR-OTP-BRUTEFORCE

| Claim | Tests |
|---|---|
| Attempts, exhaustion and expiry, as for the admin | `submission/VisitorOtpServiceTest`, `VisitorAuthControllerExpiredOtpTest`, `submission/VisitorOtpBruteForceOverHttpTest`, `submission/SubmissionLoginOtpBoundsTest` (page flow) |
| Configurable, with the same defaults | `submission/VisitorOtpServiceConfigurabilityTest`, `config/OtpDefaultsConfigTest` |
| Request rate per email and per IP | `submission/VisitorOtpServiceRateLimitTest`, `VisitorAuthControllerRateLimitTest`, `submission/VisitorOtpRequestPerIpLimitTest` |
| Many visitors' OTPs held correctly and concurrently; one email's guesses don't touch another's | `submission/VisitorOtpServiceTest` (concurrency), `VisitorAuthControllerTest`, `submission/VisitorOtpBruteForceOverHttpTest` |
| Codes stored hashed, never in plaintext | `submission/VisitorOtpServiceTest` |
| Requests for an email with and one without a testimonial are indistinguishable — status, every header, body and mail count, REST and page flow, accepted and rate-limited | `submission/VisitorOtpEnumerationResistanceTest`, `VisitorAuthControllerTest` |

### 3.3 NFR-CONTACT-CONFIDENTIALITY

| Claim | Tests |
|---|---|
| Login email and every contact value stored as ciphertext (no plaintext or part of it in any column, IV + tag present, same value stored twice differs) | `domain/testimonial/ContactConfidentialityAtRestTest`, `ContactMethodRepositoryTest`, `TestimonialRepositoryTest`, `domain/PostgresMigrationParityTest` (round trip on PostgreSQL) |
| Unreadable without the key (wrong key, flipped bit, truncation, tampering) | `common/crypto/EncryptedValueConverterKeyTest`, `EncryptedValueConverterTest` |
| No public response carries a contact value or login email (gallery list/detail, REST and pages, homepage, analytics, catalogs); searching for one finds nothing | `gallery/PublicResponsesHideContactsTest`, `GalleryControllerTest`, `GalleryViewControllerTest` |
| Contacts revealed only one testimonial at a time, only its public ones, only if approved | `gallery/GalleryServiceRevealContactTest`, `gallery/GalleryContactRevealTest` |
| Never logged in plaintext: dev/test mailers mask the recipient (`j***@example.com`); a failed send logs only the exception type, never the address | `config/LogMaskTest`, `LoggingOtpMailerTest`, `LoggingNotificationMailerTest`, `config/OtpMailFailureResponseTest`, `moderation/RejectWhenEmailFailsTest` |
| Never in a URL: the login email stays in the session between the two login steps | `config/LoginPendingEmailTest`, `submission/VisitorOtpEnumerationResistanceTest` |

Found and fixed during this pass (`security-review.md` SR-08, SR-09, SR-10): the dev mailers
printed the full address, the login email travelled in the redirect URL, and a mail failure
logged the exception text, which can name the recipient.

### 3.4 NFR-UPLOAD-SPOOFING

| Claim | Tests |
|---|---|
| Format detected from the bytes, never the declared `Content-Type` or name | `submission/PhotoStorageServiceTest`, `submission/PhotoUploadSpoofingTest` |
| Non-images rejected (PE, ELF, SVG with script, HTML, PDF, ZIP, shell script, HEIC/AVIF, truncated files) with nothing written | `submission/PhotoUploadSpoofingTest`, `PhotoImageProcessorTest` |
| Rejected at the endpoints like any invalid upload (REST 400 / inline form error), no row, uploads directory empty — also when a valid photo came first | `submission/SubmissionUploadSpoofingWebTest` |
| Only the app's own WebP re-encoding is stored; appended payloads (script, ZIP, executable, text chunks) never reach it | `submission/PhotoUploadSpoofingTest`, `PhotoStorageServiceTest` |

### 3.5 NFR-ERROR-TRANSPARENCY

| Claim | Tests |
|---|---|
| A server error answers a generic `ErrorResponse` — no message, class name, stack trace or SQL — for JSON, HTML and XML clients, MockMvc and the real server | `common/error/ErrorTransparencyOverHttpTest`, `common/error/ErrorTransparencyRealServerTest`, `GlobalExceptionHandlerTest` |
| The `/error` dispatch never includes trace, exception, message or binding errors, not even on request (`?trace=true`) | `common/error/ErrorTransparencyOverHttpTest`, `config/ErrorDetailConfigTest` |
| Every handled exception maps to a defined status with a fixed message | `GlobalExceptionHandlerTest`, `GlobalExceptionHandlerCatalogTest`, `RestAuthenticationEntryPointTest`, `RestAccessDeniedHandlerTest`, `config/CsrfProtectionTest` |

### 3.6 NFR-CATALOG-CONFIGURABILITY

| Claim | Tests |
|---|---|
| A catalog change (create, rename, reorder, deactivate/reactivate, delete, re-parent) shows on the very next request of the form, filters, catalog lists and articles | `catalogadmin/CatalogChangesTakeEffectImmediatelyTest`, `moderation/HiddenEditReviewFlowTest`, `GalleryServiceVisibilityTest`, `ModerationServiceVisibilityTest` |
| No application-level caching exists to invalidate | `architecture/NoApplicationCachingTest`, `config/NoCatalogCacheContextTest` |
