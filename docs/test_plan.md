# Test Plan

Short by design — the full testing methodology lives in the `tdd-enforcer` subagent
(`CLAUDE.md` Testing). This doc says *what* gets tested and *why it counts as done*, tying every
test back to a concrete acceptance criterion in `use-cases.md` or `nfr.md` rather than restating
the methodology.

## 1. Approach

TDD throughout: a failing test is written before the implementation that makes it pass; no
untested module is merged (`CLAUDE.md`). Four test levels:

- **Unit** — services, the shared `EncryptedValueConverter`/`EmailLookupHashService`, and OTP
  expiry/attempt logic. OTP logic and the rejected-testimonial retention job both inject a
  `Clock` (`architecture.md` §16) so expiry/30-day-window behavior is testable without waiting on
  real time.
- **Repository/integration** — Spring Data JPA repositories against H2, including the query
  behind the gallery's combined country/topic/keyword filter (decision 8) and the encrypted-field
  round-trip.
- **Web layer** — `MockMvc` against controllers: request validation (`@Valid` + Bean Validation),
  file-upload handling, and centralized error mapping (`@RestControllerAdvice`).
- **Manual performance/load pass** — the four performance NFRs (`NFR-GALLERY-PERFORMANCE`,
  `NFR-SEARCH-PERFORMANCE`, `NFR-MODERATION-QUEUE-PERFORMANCE`, `NFR-DASHBOARD-PERFORMANCE`) are
  stated as 95th-percentile response times against a production-sized dataset — that's a load-test
  concern, not a unit test. Run manually (e.g. a simple script hitting the endpoints against a
  seeded, hundreds-of-rows dataset) before each milestone's demo gate, not as part of the
  automated suite.

## 2. Per-module test list

At least one concrete test per module, each tied to the use-case or NFR it verifies.

| Module | Test | Verifies |
|---|---|---|
| `domain` | `EncryptedValueConverter` round-trips a value (encrypt → store → decrypt) and produces different ciphertext for the same plaintext on two separate calls | decision 6 / `NFR-CONTACT-CONFIDENTIALITY` |
| `domain` | `TestimonialRepository.findByEmailLookupHash(...)` resolves the correct row for a normalized email | UC-VISITOR-LOGIN routing |
| `common` | A validation failure on any endpoint maps to HTTP 400 with an actionable message and no stack trace or internal detail in the body | `NFR-ERROR-TRANSPARENCY` |
| `adminauth` | `OtpService` invalidates the OTP once the configured max wrong attempts is reached; a correct code submitted after expiry (via injected `Clock`) is still rejected | UC-ADMIN-OTP-VERIFY alt flow, `NFR-ADMIN-OTP-BRUTEFORCE` |
| `submission` (visitor auth) | The OTP-request endpoint's response is identical whether or not the submitted email has an existing testimonial | UC-VISITOR-LOGIN alt flow, `NFR-VISITOR-OTP-BRUTEFORCE` |
| `submission` | Submitting with a topic group picked but none of its subtopics filled in is rejected — picking a group alone doesn't count | UC-CREATE-TESTIMONIAL alt flow |
| `submission` | An uploaded file whose actual content isn't an image is rejected, even when the client-declared `Content-Type` claims `image/jpeg` | `NFR-UPLOAD-SPOOFING` |
| `submission` | Editing an `APPROVED` testimonial changing only country, score, achievements, or contacts leaves it `APPROVED` (short-circuit); editing its section text resets it to `PENDING` | UC-EDIT-TESTIMONIAL, decision 18 |
| `moderation` | Approving a `PENDING` testimonial clears `identity_modified`/`score_modified`/every `TestimonialSection.modified` flag and sets status to `APPROVED` | UC-APPROVE-TESTIMONIAL |
| `moderation` | Rejecting a testimonial sets `REJECTED` + `rejected_at`, and it disappears from both the public gallery and the pending queue | UC-REJECT-TESTIMONIAL |
| `moderation` | `RejectedTestimonialCleanupJob` purges only testimonials whose `rejected_at` is more than 30 days before the injected `Clock`'s current time, leaving more-recent rejections untouched | UC-PURGE-REJECTED, decision 3 |
| `catalogadmin` | Deactivating a `Topic` removes it from the next submission-form/filter pick-list load, while existing `TestimonialSection` rows referencing it keep rendering unchanged | UC-MANAGE-TOPICS alt flow, `NFR-CATALOG-CONFIGURABILITY` |
| `catalogadmin` | Re-parenting a `Topic` to a different `TopicGroup` (or to standalone) does not change any existing `TestimonialSection.topic_id` | decision 11 |
| `gallery` | A combined country + topic + keyword query returns only `APPROVED` testimonials matching all three as AND conditions | UC-FILTER-COUNTRY, UC-SEARCH-KEYWORD, UC-FILTER-TOPIC, decision 8 |
| `gallery` | The reveal-contact endpoint returns only entries with `is_public = true`, and only for an `APPROVED` testimonial | UC-REVEAL-CONTACT |
| `gallery` | The normal browse/detail response never includes any `ContactMethod` value or the submitter's email | `NFR-CONTACT-CONFIDENTIALITY` |
| `analytics` | Dashboard aggregate counts include only `APPROVED` testimonials — adding a `PENDING` one doesn't change the stats | UC-VIEW-DASHBOARD |

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
