# Backlog

## TODO

- **BL-009**: Gallery article view derives an achievement's display text via a `humanize()`
  string transform on its slug (`made_new_friends` → `Made new friends`) instead of looking up
  the real `Achievement.label` — close but not always identical to the actual seeded label (e.g.
  drops trailing words like "here"). `GalleryService`/`TestimonialDetailDto` currently expose
  achievements as slugs only; fixing this means resolving each slug to its `Achievement` row (or
  changing the DTO to carry the label directly) rather than approximating it client-side of the
  DB. Found while implementing the `gallery` view layer (M4).

- **BL-008**: Neither the admin nor the visitor OTP login screens surface attempts-remaining or
  cooldown state — both just show a generic "wrong or expired code" message on any rejection
  (`OtpService`/`VisitorOtpService` already track `attemptsRemaining` internally, decisions 4/17;
  it just never reaches the view). Surface it on `adminauth/login.html` and
  `submission/login-code.html` once there's a concrete UX for it. Found while implementing the
  `adminauth`/`submission` view layers (M4).

- **BL-007**: `submission/form.html`'s `SubmissionFormCommand` binding has no field-level Bean
  Validation or inline per-field error messages — a binding failure (e.g. a non-numeric
  `admissionYear`) or a `SubmissionValidationException` from `createFromForm`/`editFromForm`
  currently re-renders the form with one generic banner, not a highlight on the specific invalid
  field. `TestimonialSubmissionRequest`'s own `@Valid` annotations already exist and are reused
  for validation itself (decision 21) — this entry is only about surfacing failures per-field in
  the rendered form. Found while implementing the `submission` view layer (M4).

- **BL-006**: `make checkstyle` currently fails with 7 warnings, all outside this milestone's
  core logic: unused imports in `OtpServiceRateLimitTest`, `VisitorOtpServiceRateLimitTest`,
  `ModerationServiceListPendingTest` (likely auto-fixable via `make fix`/`spotless:apply`), plus
  line-length overruns in `OtpServiceTest`, `GalleryViewControllerTest`, and
  `ModerationServiceListPendingTest` (two lines) needing a manual rewrap. Found while implementing
  the M4 view layer.

- **BL-005**: SpotBugs `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` findings — `make spotbugs` currently fails
  with 49 Medium findings, entirely in `common.web.PageResponse`; the `gallery` slice's
  `PhotoRefDto`/`TestimonialSectionViewDto`/`TestimonialDetailDto`/`TopicCatalogEntryDto`; the
  `moderation` slice's `ModerationPhotoRefDto`/`ModerationSectionViewDto`/
  `ModerationTestimonialDetailDto`; and the `submission` slice's mutable `SectionFormEntry`/
  `SubmissionFormCommand` form-command classes plus one constructor field on
  `SubmissionViewController` — all either return/accept a `List<T>` field without a defensive
  copy, or (the two form-command classes) are deliberately mutable, framework-bound objects
  Spring's `@ModelAttribute`/Thymeleaf `th:field` binding needs live list references into, which
  SpotBugs can't distinguish from attacker-controlled state. Decide and apply one consistent fix
  for the immutable-record group (either `List.copyOf(...)` compact-constructor defensive copies,
  matching `submission.TestimonialSubmissionRequest`'s existing pattern, or a scoped
  `spotbugs-exclude.xml` entry analogous to the existing `domain..` one) and a separate, narrowly
  scoped `spotbugs-exclude.xml` entry for the mutable form-command classes (analogous to the
  existing constructor-injected-service-field exemptions). Found while implementing the `gallery`
  slice (M4) and extended while implementing `submission`'s view layer (M4); `mvn test` itself is
  unaffected since SpotBugs isn't bound to the `test` phase.

- **BL-004**: Wire up real CSRF protection once a frontend exists — M2 disabled CSRF
  (`SecurityConfig`, `.csrf(AbstractHttpConfigurer::disable)`) for the cookie-session JSON API
  (`JSESSIONID`, per `api-spec.yaml`'s `adminSession`/`visitorSession` schemes) ahead of any wired
  frontend. Interim mitigation is only `Content-Type: application/json` + `SameSite` cookie
  attributes, not a real CSRF token exchange. Once a frontend is being integrated, add Spring
  Security's CSRF token support (e.g. cookie-to-header token pattern) and have the frontend
  read/send the token on all state-changing requests. Decided with the user during M2 planning
  (2026-09-19).

- **BL-003**: Testcontainers-Postgres migration-parity integration test — M1's automated tests
  run only against H2 (`NON_KEYWORDS=VALUE;MODE=PostgreSQL`); nothing proves the same Flyway
  migration set applies cleanly against a real Postgres instance. Add the
  `org.testcontainers:postgresql` dependency and at least one integration test that boots a real
  Postgres container, runs the full migration set against it, and asserts success — catching
  Postgres-specific SQL issues H2's compatibility mode could mask. Deferred from M1 by user
  decision (2026-09-19); fits M7 (hardening pass) or earlier if convenient.

- **BL-002**: Cross-testimonial photo browsing by tag — a dedicated view where visitors
  browse/filter photos (by auto topic-tags and/or free-form custom tags, both already in the
  core schema — see `docs/decisions.md`) across all testimonials, not scoped to one
  testimonial. In scope, pending design and implementation — see `docs/scope.md`,
  "Backlogged".
