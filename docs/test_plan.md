# Test Plan

Short by design — the full testing methodology lives in the `tdd-enforcer` subagent
(`CLAUDE.md` Testing). This doc says *what* gets tested and *why it counts as done*, tying every
test back to a concrete acceptance criterion in `use-cases.md` or `nfr.md` rather than restating
the methodology.

## 1. Approach

TDD throughout: a failing test is written before the implementation that makes it pass; no
untested module is merged (`CLAUDE.md`). The one exception is the browser end-to-end level
below: those tests are written after the implementation, never test-first. Five test levels:

- **Unit** — services, the shared `EncryptedValueConverter`/`EmailLookupHashService`, and OTP
  expiry/attempt logic. OTP logic and the rejected-testimonial retention job both inject a
  `Clock` (`architecture.md` §16) so expiry/30-day-window behavior is testable without waiting on
  real time.
- **Repository/integration** — Spring Data JPA repositories against H2, including the query
  behind the gallery's combined country/topic/keyword filter (decision 8) and the encrypted-field
  round-trip. Plus a real PostgreSQL 16 (Testcontainers, `testsupport.PostgresContainerSupport`,
  one container per test JVM, one database per test class) for what H2's PostgreSQL mode could
  hide: the Flyway migration set, the entities' schema validation and the seed data
  (`domain.PostgresMigrationParityTest`). These run in the normal `./mvnw test`, so the suite
  needs a reachable Docker daemon.
- **Web layer** — `MockMvc` against controllers: request validation (`@Valid` + Bean Validation),
  file-upload handling, and centralized error mapping (`@RestControllerAdvice`). Every
  state-changing request sends its CSRF token the way real clients do, through
  `testsupport.Csrf` (`csrfField()` for a page form, `csrfHeader()` for a REST call) — never
  spring-security-test's `csrf()`, which a guard test bans (decision 32).
- **Browser end-to-end** — Playwright for Java (`@Tag("e2e")`, `com.iitm.beacon.e2e`) driving
  the real app in Chromium, desktop and mobile presets. Tests assert only with screenshot
  baselines: they drive the page into a state (clicks, keys, swipes), then one screenshot checks
  the presence, layout and look of every element at once — no hard-coded colours, coordinates,
  computed styles or other DOM measurements. What isn't visible (statuses, headers, security
  checks) is covered at the web layer above. Runs only inside Docker
  (`make e2e`; `make e2e-update-screenshots` regenerates baselines) so screenshots are identical
  on any machine; plain `mvn test` excludes it. **Not TDD:** written after the implementation,
  as the final check that the finished UI works end to end — there's nothing to screenshot
  before it exists. Every new baseline is reviewed by eye before it's accepted.
- **Manual performance/load pass** — the four performance NFRs (`NFR-GALLERY-PERFORMANCE`,
  `NFR-SEARCH-PERFORMANCE`, `NFR-MODERATION-QUEUE-PERFORMANCE`, `NFR-DASHBOARD-PERFORMANCE`) are
  stated as 95th-percentile response times against a production-sized dataset — that's a load-test
  concern, not a unit test. Run manually with `make perf` (`./mvnw -Pperf test`, the
  `@Tag("perf")` test `perf.PerformanceNfrTest`, which the normal suite skips): the app on a
  random port against PostgreSQL 16 (Testcontainers), a seeded dataset of 600 testimonials,
  every scenario measured sequentially and with 5 concurrent clients, p95 asserted against the
  NFR. Run it before each milestone's demo gate and record the results in
  `nfr-verification.md`. Its pure helpers (percentiles, dataset plan, report) are unit-tested
  in the normal suite.

## 2. Per-module test list

At least one concrete test per module, each tied to the use-case or NFR it verifies.

| Module | Test | Verifies |
|---|---|---|
| `domain` | `EncryptedValueConverter` round-trips a value (encrypt → store → decrypt) and produces different ciphertext for the same plaintext on two separate calls | decision 6 / `NFR-CONTACT-CONFIDENTIALITY` |
| `domain` | `TestimonialRepository.findByEmailLookupHash(...)` resolves the correct row for a normalized email | UC-VISITOR-LOGIN routing |
| `domain` | Every Flyway migration applies on PostgreSQL 16, the entities validate against the result, and its columns, keys and seed rows equal those of H2 migrated from the same scripts | BL-003, deployment parity |
| `common` | A validation failure on any endpoint maps to HTTP 400 with an actionable message and no stack trace or internal detail in the body | `NFR-ERROR-TRANSPARENCY` |
| every slice | A malformed path id answers 404, a malformed/missing parameter or part 400, an unsupported method 405 with `Allow`, 406/415 as such — JSON `ErrorResponse` under `/api/**`, the HTML error page on pages — with a fixed message, no exception text and no ERROR log; an unreadable submission-form field redirects back to the form | `NFR-ERROR-TRANSPARENCY`, decision 33 |
| `common` | A server error answers a generic body to JSON, HTML and XML clients (real server too), and `/error` never includes trace, exception, message or binding errors | `NFR-ERROR-TRANSPARENCY` |
| `adminauth` | `OtpService` invalidates the OTP once the configured max wrong attempts is reached; a correct code submitted after expiry (via injected `Clock`) is still rejected | UC-ADMIN-OTP-VERIFY alt flow, `NFR-ADMIN-OTP-BRUTEFORCE` |
| `submission` (visitor auth) | The OTP-request endpoint's response is identical whether or not the submitted email has an existing testimonial | UC-VISITOR-LOGIN alt flow, `NFR-VISITOR-OTP-BRUTEFORCE` |
| `submission` | Submitting with a topic group picked but none of its subtopics filled in is rejected — picking a group alone doesn't count | UC-CREATE-TESTIMONIAL alt flow |
| `submission` | An uploaded file whose actual content isn't an image is rejected, even when the client-declared `Content-Type` claims `image/jpeg` | `NFR-UPLOAD-SPOOFING` |
| `submission` | A submission whose later photo is refused, or whose save fails, leaves no photo files behind; an edit that fails keeps the files of the photos it would have removed, which go only after a successful commit | decision 2, architecture §7 |
| `submission` | Editing an `APPROVED` testimonial changing only country, score, achievements, or contacts leaves it `APPROVED` (short-circuit); editing its section text resets it to `PENDING` | UC-EDIT-TESTIMONIAL, decision 18 |
| `moderation` | Approving a `PENDING` testimonial clears `identity_modified`/`score_modified`/every `TestimonialSection.modified` flag and sets status to `APPROVED` | UC-APPROVE-TESTIMONIAL |
| `moderation` | Rejecting a testimonial sets `REJECTED` + `rejected_at`, and it disappears from both the public gallery and the pending queue | UC-REJECT-TESTIMONIAL |
| `moderation` | `RejectedTestimonialCleanupJob` purges only testimonials whose `rejected_at` is more than 30 days before the injected `Clock`'s current time, leaving more-recent rejections — and resubmitted testimonials with a stale `rejected_at` — untouched | UC-PURGE-REJECTED, decision 3 |
| `moderation` | A purge deletes the testimonial's sections, photos, tags, contacts and achievement ticks; its photo files go only after the commit, none on a rollback, and a missing file doesn't stop the run | UC-PURGE-REJECTED alt flow, decision 3 |
| `catalogadmin` | Deactivating a `Topic` removes it from the next submission-form/filter pick-list load and hides its existing `TestimonialSection`s from the gallery article, keyword search, moderation queue and edit form; reactivating it shows them again unchanged | UC-MANAGE-TOPICS alt flow, decision 28, `NFR-CATALOG-CONFIGURABILITY` |
| `catalogadmin` | Deactivating a `TopicGroup` hides all of its topics and their sections the same way, though the topics themselves stay `active` | UC-MANAGE-TOPIC-GROUPS alt flow, decision 28 |
| `catalogadmin` | Deactivating an `Achievement` removes it from the form checklist and hides its ticks on existing testimonials; reactivating restores them | UC-MANAGE-ACHIEVEMENTS alt flow, decision 28 |
| `catalogadmin` | Re-parenting a `Topic` to a different `TopicGroup` (or to standalone) does not change any existing `TestimonialSection.topic_id` | decision 11 |
| `catalogadmin` | Deleting a `Topic` deletes its sections with their photo rows and files, after commit, and leaves each affected testimonial's status unchanged; deleting a `TopicGroup` does the same for all of its topics; deleting an `Achievement` deletes all ticks of it | decision 28 |
| `catalogadmin` | A duplicate slug on create or edit answers 409; `general` can't be deactivated, deleted, moved into a group or re-slugged (409) | UC-MANAGE-TOPICS, UC-MANAGE-ACHIEVEMENTS alt flows, decision 28 |
| `catalogadmin` | Every `/api/catalog/**` endpoint answers 401 without a session and 403 for a visitor session; a `/catalog/**` page without a session redirects to `/admin/login` and returns there after the login | decision 23, decision 28 |
| `config` | Every OTP login (admin/visitor, REST/page) rotates the session id — the id planted before it gets 401 afterwards — and renews the CSRF token, keeping the saved page; a failed verify rotates nothing | BL-034, decision 23 |
| `config` | A session of the other role on a page is redirected to that page's login page (GET saved, POST not) and the login replaces it; `/api/**` keeps the JSON 403 | BL-033, decision 23 |
| `config` | A POST/PUT/PATCH/DELETE without a valid CSRF token gets the JSON 403 with no side effect on a page and an `/api/**` route of every slice; the `XSRF-TOKEN` cookie is set on GETs and accepted in the header; every POST form renders the token; an unreadable multipart form upload is redirected back to the form | BL-004, decision 32 |
| `config` | No `UserDetailsService` bean exists (no generated password) | BL-013, decision 4 |
| `config` | A non-API path no route serves is the HTML 404 page for anonymous, visitor and admin (any method, with the CSRF token), with the security headers, not saved, no session; `/api/**` keeps JSON 401/403 | decision 33 |
| `config` | On the real server: `JSESSIONID` and `XSRF-TOKEN` are `Secure` with `BEACON_COOKIE_SECURE` on or a trusted forwarded https, not otherwise; every redirect `Location` is relative; `X-Forwarded-For`/`-Proto` count only from a configured proxy address | decision 34 |
| `config` | Every response carries the CSP (with `'unsafe-eval'`/`blob:` only on the submission form) and `Referrer-Policy`; no template has inline scripts, styles or handlers | decision 34 |
| `config` | Versioned CSS/JS/WebJar URLs and photos are long-cached, plain URLs revalidated, pages and the API `no-store`; templates render versioned asset URLs | decision 34 |
| `config` | A multipart request to anything but the submission endpoints is refused from its headers when over 16 KB (413) or of unknown length (411), without its body being read; the submission endpoints keep their upload limits | decisions 32, 34 |
| `gallery`, `moderation` | The largest addressable page answers an empty page; the next one (offset overflow) a 400 | decision 33 |
| `adminauth`, `submission` | The login email never appears in a URL, link or form between the two steps (kept in the session, removed at login); a code that can't be emailed gets the success response byte for byte and a WARN without the address; dev/test logs mask the recipient | decisions 4, 17, `NFR-CONTACT-CONFIDENTIALITY` |
| `moderation` | A reject whose email can't be sent leaves the testimonial `PENDING`: REST 503 with the fixed message, the page back on the queue with the message and the reason kept | UC-REJECT-TESTIMONIAL alt flow, decision 33 |
| `config` | `POST /logout` with a token ends the session (the old id gets 401), expires the cookies and redirects an admin to `/admin/login`, anyone else to `/`; without a token 403, other methods 405; the header shows "Log out" only where intended | decision 23 |
| `submission` | Saving an edit leaves sections of invisible topics (with photos) and ticks of inactive achievements untouched | UC-EDIT-TESTIMONIAL, decision 28 |
| `moderation` | Approving keeps `modified` on a section whose topic is hidden; making that topic visible again (reactivating it or its group, or moving it out of an inactive group) returns each `APPROVED` testimonial with such a section to `PENDING` | UC-APPROVE-TESTIMONIAL alt flow, decisions 18, 28 |
| `gallery` | A topic id equal to an existing group id filters by the topic when sent as `topicIds` and by the group only when sent as `groupIds` | UC-FILTER-TOPIC, decision 29 |
| `gallery` | A combined country + topic + keyword query returns only `APPROVED` testimonials matching all three as AND conditions | UC-FILTER-COUNTRY, UC-SEARCH-KEYWORD, UC-FILTER-TOPIC, decision 8 |
| `gallery` | The list is ordered newest approval first with the id as tie-breaker, a caller's sort is ignored, and paging across page boundaries shows every testimonial exactly once | UC-BROWSE-APPROVED, decision 31 |
| `gallery` | The reveal-contact endpoint returns only entries with `is_public = true`, and only for an `APPROVED` testimonial | UC-REVEAL-CONTACT |
| `gallery` | The normal browse/detail response never includes any `ContactMethod` value or the submitter's email | `NFR-CONTACT-CONFIDENTIALITY` |
| `analytics` | Dashboard aggregate counts include only `APPROVED` testimonials — adding a `PENDING` one doesn't change the stats | UC-VIEW-DASHBOARD |
| `analytics` | A topic group counts a testimonial once however many of its subtopics it fills; sections of hidden topics and ticks of inactive achievements aren't counted; zero counts are left out; the "recommending" share counts scores of 6 or more | UC-VIEW-DASHBOARD, decisions 28, 30 |
| `analytics` | With no approved testimonial `/` shows the empty state and the REST summary has empty lists and null score figures; with some, a represented country's map region links to its gallery filter, shaded by its count | UC-VIEW-DASHBOARD alt flow, decision 30 |

## 3. Acceptance gates

Each milestone-plan demo (`milestones.md`) is gated on a specific subset of `UC-*`/`NFR-*` IDs
actually passing, not just "code exists."

### Mid-demo (2026-10-09) — core loop only

Must pass: UC-VISITOR-LOGIN, UC-CREATE-TESTIMONIAL, UC-EDIT-TESTIMONIAL, UC-ADMIN-OTP-REQUEST,
UC-ADMIN-OTP-VERIFY, UC-VIEW-PENDING-QUEUE, UC-APPROVE-TESTIMONIAL, UC-REJECT-TESTIMONIAL,
UC-BROWSE-APPROVED, UC-FILTER-COUNTRY, UC-SEARCH-KEYWORD, UC-FILTER-TOPIC, UC-EXPAND-TESTIMONIAL,
UC-VIEW-PHOTOS-FULLSCREEN, UC-REVEAL-CONTACT; `NFR-GALLERY-PERFORMANCE`, `NFR-SEARCH-PERFORMANCE`,
`NFR-MODERATION-QUEUE-PERFORMANCE`, `NFR-ADMIN-OTP-BRUTEFORCE`, `NFR-VISITOR-OTP-BRUTEFORCE`,
`NFR-CONTACT-CONFIDENTIALITY`, `NFR-UPLOAD-SPOOFING`, `NFR-ERROR-TRANSPARENCY`.

Not required yet: UC-MANAGE-TOPIC-GROUPS, UC-MANAGE-TOPICS, UC-MANAGE-ACHIEVEMENTS,
UC-VIEW-DASHBOARD, UC-PURGE-REJECTED, `NFR-CATALOG-CONFIGURABILITY`, `NFR-DASHBOARD-PERFORMANCE`.

### Final submission (2026-11-06) — full MVP

Everything above, plus: UC-MANAGE-TOPIC-GROUPS, UC-MANAGE-TOPICS, UC-MANAGE-ACHIEVEMENTS,
UC-VIEW-DASHBOARD, UC-PURGE-REJECTED, `NFR-CATALOG-CONFIGURABILITY`, `NFR-DASHBOARD-PERFORMANCE`.
Plus a manual containerized-deployment smoke test: `docker-compose up` brings up the app and
Postgres together, the app is reachable, and data persists across a container restart.
