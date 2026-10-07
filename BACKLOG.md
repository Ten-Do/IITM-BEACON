# Backlog

## TODO

- **BL-038**: Renaming a topic's or achievement's slug in the catalog (decision 28) while a
  visitor has the submission form open breaks that visitor's submit: the form posts sections by
  `topicSlug` and ticks by achievement slug, so the old slug is rejected as "Unknown or inactive
  topic"/"achievement" and the visitor has to reload the form, losing nothing saved but seeing
  an error they can't explain. Decide whether to accept it (rare, admin-only action), or make
  the form post ids instead of slugs (api-spec `TestimonialSubmission` change), or show a
  "the topic list changed, please reload" message; document and test the chosen behaviour.
  Found while building the catalog admin (M5).

- **BL-037**: A path id that isn't a number answers 500 on every REST endpoint that takes one —
  e.g. `DELETE /api/catalog/topics/abc`, `POST /api/moderation/testimonials/abc/approve`, or an
  id beyond `Long` like `/api/catalog/topics/99999999999999999999`: Spring raises
  `MethodArgumentTypeMismatchException`, which `GlobalExceptionHandler` has no handler for, so it
  falls through to the generic 500. The same happens to a non-numeric id in a query-parameter
  list — `GET /api/gallery/testimonials?topicIds=abc` or `?groupIds=abc` (found while splitting
  the gallery filter, decision 29). Decide the status (400 for a malformed id, or 404 as for an
  unknown one), add the handler with a fixed message (the exception's own text names Java types),
  document it in `api-spec.yaml`, and test it on one endpoint per slice. Found while building the
  catalog admin API (M5).

- **BL-036**: The gallery list has no defined order: `GalleryService.browse` pages through
  `testimonialRepository.findAll(spec, pageable)` with an unsorted `PageRequest`, so the SQL has
  `LIMIT/OFFSET` but no `ORDER BY`. H2 happens to return the rows in the order of the
  `(status, country_code)` index (cards come out sorted by country code); PostgreSQL guarantees
  nothing, so the order can change between requests and a card can repeat on, or vanish from,
  the next page. Neither `docs/use-cases.md` (UC-BROWSE-APPROVED) nor `docs/api-spec.yaml` says
  what the order should be — decide it (e.g. newest approval first, with the id as the
  tie-breaker), sort the query by it, document it, and test paging across a page boundary.
  Found while writing the gallery grid e2e tests.

- **BL-035**: The moderation queue may show a section's photos in a different order than the
  public article: `GalleryService.toSectionView` sorts a section's photos by `displayOrder`, but
  `ModerationService.toSectionView` maps `section.getPhotos()` as loaded, and
  `TestimonialSection.photos` has no `@OrderBy` — so the admin reviews (and pages through in the
  fullscreen viewer) an order visitors never see. Sort by `displayOrder` there too (or put
  `@OrderBy("displayOrder")` on the mapping and drop both manual sorts), with a test that inserts
  photos out of order. Found while showing photo tags and the fullscreen viewer in the
  moderation queue.

- **BL-034**: Session fixation: `common.security.SessionAuthenticator.login` stores the new
  security context in the existing HTTP session without rotating its id — the logins are done by
  hand after the OTP check, so Spring Security's session-fixation protection
  (`changeSessionId` on authentication) never runs, and a session id planted before the login
  stays valid after it, for the admin and visitor alike. Call `request.changeSessionId()` in
  `login` before saving the context; it keeps the session's attributes, so the saved page for
  `PostLoginRedirect` survives. Test that the `JSESSIONID` changes on a successful verify (REST
  and page flows) and that the return to the requested page still works. Found while adding the
  return to the requested page after login.

- **BL-033**: A logged-in user of the other role gets `RestAccessDeniedHandler`'s JSON 403 body
  on an HTML page instead of a page: an admin session opening `/submissions/form` or
  `/submissions/confirmation`, or a visitor session opening `/moderation/**`. The session check
  makes it worse: on a 403 from the session ping `static/js/session-check.js` reloads the page,
  which then lands on that same JSON 403. Add a page-aware `AccessDeniedHandler` in
  `SecurityConfig` (like the entry point of decision 23) that redirects those pages to their own
  role's login page — where logging in replaces the other role's session — and keeps the JSON
  403 for `/api/**`. Found while adding the login redirect for expired sessions.

- **BL-032**: The page heading hierarchy has gaps: `gallery/list.html` has no `h1` at all (its
  only heading is the empty state's `h3.gallery-empty-title`), `gallery/not-found.html` has only
  an `h3`, and `moderation/queue.html` jumps from its `h1` straight to the empty state's `h3`.
  Screen-reader users navigating by headings get no page title on the gallery list and skipped
  levels elsewhere. Give each page one `h1` (a visually matching or visually hidden one where
  the design has no title) and make the empty-state/not-found headings the next level down,
  restyling the classes so nothing moves visually. Found while turning the article's subtopic
  heading into an `h3`.

- **BL-031**: The submission form's photo picker refuses a file over the size limit, a non-image
  or one over the photo count limits the moment it's chosen, but not a format the server can't
  convert: a HEIC/HEIF/AVIF photo (e.g. an iPhone original chosen on a desktop browser) is
  accepted and previewed as a tile showing its file name, then rejected by
  `PhotoImageProcessor` on submit — and every attached photo is lost on the re-render (BL-025).
  Refuse those formats at selection time too (by the file's type and extension, with a message
  naming the accepted formats), keeping the server check as the backstop. Found while building
  the multi-photo picker.

- **BL-030**: On the submission form, clearing a contact's value doesn't untick its "Show
  publicly" checkbox, and the browser console shows `TypeError: Cannot set properties of null
  (setting 'checked')`: `contactRow.update()` in `static/js/submission-form.js` looks the checkbox
  up with `this.$el`, which inside a method called from the value input's `x-on:input` is that
  input, not the row (Alpine resolves `$el` for the element whose directive runs). The server
  then rejects the submission with "Enter a contact before making it public.". Capture the row
  element in `init()` (as `photoPicker` does). Found in the browser check of the multi-photo
  picker.

- **BL-029**: Client errors that Spring MVC raises before a handler runs come back as a JSON 500
  ("An unexpected error occurred", logged as a server error): `GlobalExceptionHandler`'s catch-all
  `Exception` handler also catches `MethodArgumentTypeMismatchException` and
  `HttpRequestMethodNotSupportedException`. E.g. `GET /gallery/abc`, `GET /gallery/<20 digits>`,
  `GET /api/gallery/testimonials/abc` (should be 400 or 404), and `PUT /admin/login`,
  `PUT /submissions/login` (should be 405). The browser pages also get a JSON body instead of an
  HTML page. Map them to their 4xx statuses (and give the view routes an HTML answer, like
  `gallery/not-found`). The new `POST /gallery/{id}/contact` sidesteps it with a digits-only path
  pattern. Found while adding the AJAX contact reveal.

- **BL-028**: The moderation queue prints each card's submission time as a raw `Instant`
  (`Submitted 2026-09-27T13:35:03.314670Z` — ISO-8601 with microseconds, in UTC), straight from
  `'Submitted ' + ${card.createdAt()}` in `moderation/queue.html`. Render it as a readable
  date/time (decide the format and the time zone to show it in). Found while making the queue fit
  on phones.

- **BL-027**: On the submission form, an invalid text input, select or textarea gets its
  `submission-field-input--invalid` class but is not highlighted (no red border or background)
  unless it has focus: in `beacon.css` the `.submission-field-input--invalid` rule comes *before*
  the `.submission-field-input, .submission-field-select` and `.submission-field-textarea` rules,
  and at the same specificity the later rule's `border`/`background` win. Only the red message
  under the field shows. Move the invalid rule after those base rules (or raise its specificity),
  and pin the order in `StaticResourcesTest`. Found in the phone screenshots of a rejected form
  while making the form fit on phones.

- **BL-026**: A photo tag has no length limit: `TestimonialSubmissionRequest.PhotoInput` caps only
  the number of tags (`@Size(max = 10)`), not each tag, and `photo_tag.tag_text` is an unbounded
  `VARCHAR` — so a single tag can be thousands of characters. Under a thumbnail such a tag chip is
  cut to one line with an ellipsis, but the fullscreen viewer's caption shows every tag in full
  (the caption bar scrolls once it passes 45% of the screen height, leaving less room for the
  photo). Decide a maximum tag length, reject longer tags server-side as a field error on that
  photo's tags input (like "Tags need a photo"), and say the limit in the form. Found while
  showing photo tags on the article, in the moderation queue and in the viewer.

- **BL-025**: A photo that `PhotoStorageService.store` rejects (unsupported format such as HEIC,
  over the size limit, or over `max-pixels`) comes back as one global form error, e.g.
  "Unsupported image format. Please upload JPEG, PNG, WebP, GIF, TIFF or BMP." — it doesn't say
  which of up to 50 uploads (up to 5 per topic) was the problem, and every selected file is lost
  on the re-render. Report it next to its topic's photos (`sections[k].photos`, the anchor the
  per-topic count limit already uses), naming the original filename, the way other violations
  already are. With JS the photo picker already refuses a file over the size limit before
  upload, so this is mostly about formats (see BL-031), `max-pixels`, and browsers without JS.
  Found while building the WebP photo pipeline.

- **BL-024**: A second POST of the OTP code form after a successful one — a double-clicked
  "Verify", or the browser's Back button then resubmitting — re-renders the code page with "That
  code is wrong or has expired", because the first POST already consumed the code, even though the
  session is now logged in (and the saved page to return to was consumed too). In
  `AdminAuthViewController.verify` and `SubmissionViewController.verifyOtp`, a request whose
  session already holds the matching role should skip the OTP check and redirect like a
  successful login. Found while adding the return to the requested page after login.

- **BL-023**: The `app` service in `docker-compose.yml` passes only the required variables into
  the container, so the optional overrides documented in `.env.example` (`BEACON_UPLOADS_ROOT`,
  `BEACON_PHOTO_MAX_COUNT`, `BEACON_PHOTO_MAX_PER_SECTION`, `BEACON_PHOTO_MAX_SIZE_BYTES`,
  `BEACON_SESSION_TIMEOUT`, and the photo pipeline's `BEACON_PHOTO_MAX_PIXELS`,
  `BEACON_PHOTO_FULL_MAX_EDGE`, `BEACON_PHOTO_THUMB_MAX_EDGE`, `BEACON_PHOTO_WEBP_QUALITY`,
  `BEACON_PHOTO_WEBP_THUMB_QUALITY`, `BEACON_PHOTO_BACKFILL_ENABLED`)
  never reach it — Compose reads `.env` only to substitute variables in the compose file itself.
  Forward them as optional (e.g. `BEACON_SESSION_TIMEOUT: ${BEACON_SESSION_TIMEOUT:-24h}`), or drop
  them from `.env.example`. Found while adding the 24h session timeout.

- **BL-022**: The `maven` and `e2e` tooling services in `docker-compose.yml` run as root and
  bind-mount the workspace, so on native Docker Engine (Linux without Docker Desktop, which
  remaps ownership) every file they write lands root-owned on the host: `target/`, and for `e2e`
  the committed screenshot baselines in `src/test/resources/e2e-screenshots/`. The host user then
  can't `./mvnw clean` or edit/delete those files without sudo. Run both services as the host
  user (e.g. `user: "${UID}:${GID}"` plus a writable `HOME`/Maven repo location for the
  `maven-repo` volume). Found while adding the e2e (Playwright) infrastructure.

- **BL-021**: Some submission-form field errors still show Hibernate Validator's default
  messages (e.g. "must be true" under the consent checkbox, "must not be blank"), which are
  resolved from the JVM's default locale — a non-English JVM renders them in another language
  next to English UI text. Give every constraint on `TestimonialSubmissionRequest` an explicit
  English message (or pin the validation message locale); `countryCode`'s two constraints already
  have one. Found while fixing the BUGS.md batch.

- **BL-020**: The pre-existing tests in `SubmissionServiceEditTest` never flush, and their
  "reloaded" testimonial is the same in-memory instance from the persistence context — so they
  never check what actually reaches the database, which is how the BUGS.md edit HTTP 500
  (duplicate `TestimonialAchievement`, `photo_tag` unique violation) slipped through. Make them
  flush + clear + reload like the tests added for that fix. Found while fixing the BUGS.md batch.

- **BL-019**: `GET /admin/login` still shows the email step to an admin who already has a live
  session, unlike `GET /submissions/login`, which sends a logged-in visitor straight on. Redirect
  an `ADMIN` session to `/moderation/queue`. Found while fixing the BUGS.md batch.

- **BL-018**: `SubmissionViewController.requestOtp` (`POST /submissions/login`, also used by the
  code page's "Resend code") doesn't catch `TooManyRequestsException`, contradicting decision 21
  (a View-Controller turns it into an inline error). With the default visitor limit of one
  request per email per minute, pressing "Resend code" within a minute shows the browser
  `GlobalExceptionHandler`'s raw JSON 429. Catch it and re-render the code page with a readable
  error, as `AdminAuthViewController` does. Found while fixing the BUGS.md batch.

- **BL-017**: Orphaned photo files on rollback — `PhotoStorageService.store` converts each
  upload and writes its two files (`<uuid>.webp` and `<uuid>-thumb.webp`, decision 22) to the
  uploads volume before the surrounding transaction commits, so when the transaction later rolls
  back both files stay on disk with no `photo` row pointing at them (seen in prod, before the
  WebP pipeline: `76a6645d-6c9c-4922-bfe0-9e663719f6b3.jpeg`, left by the failed edit behind the
  BUGS.md HTTP 500). This now also happens on an ordinary rejection: photo files are checked one
  at a time while they're stored, after every other rule has passed, so when a later photo of
  the same submission is refused (unsupported format, over the size or pixel limit), the
  earlier photos' files are already written. Delete both files of every photo stored during a
  transaction when it rolls back (e.g. a `TransactionSynchronization` registered in `store`), and
  likewise defer `delete(Photo)` of removed or replaced photos — which removes both files — until
  after commit, so a rollback can't lose a live file (`LegacyPhotoBackfill` already deletes its
  originals only after its commit). Found while fixing the BUGS.md batch.

- **BL-016**: `src/test/resources/application.yml` shadows `src/main/resources/application.yml`
  on the test classpath, so tests never load the main file — `jpa.open-in-view: false`,
  `hibernate.ddl-auto: validate`, and `flyway.enabled` are never exercised by the suite, and
  every main-file setting a test depends on has to be duplicated by hand (the upload limits
  already are, guarded by `config/UploadLimitsConfigTest`). Rename the test file to a profile
  overlay (e.g. `application-test.yml` + an active `test` profile) so it layers on top of the main
  file instead of replacing it. Found while fixing the BUGS.md batch.

- **BL-015**: The seeded guiding prompt of the `general` topic is "Anything else you'd like to
  add. (mandatory catch-all)" (V13) — the "(mandatory catch-all)" note is spec wording from
  `use-cases.md`'s catalog table, yet it's rendered to visitors as the textarea's label on the
  submission form. Replace the seeded prompt with visitor-facing text via a new migration. Found
  while fixing the BUGS.md batch.

- **BL-014**: A crafted form POST with an index of `sections[256]` or higher returns a JSON 500:
  Spring's data binder auto-grow limit (256) throws `InvalidPropertyException`, which reaches
  `GlobalExceptionHandler`'s generic handler. Map it to a 400 (REST) / an inline form error
  (`SubmissionViewController`). Pre-existing; found while fixing the BUGS.md batch.

- **BL-013**: Spring Boot's `UserDetailsServiceAutoConfiguration` still kicks in and creates an
  in-memory user with a generated password (a `Using generated security password` WARN on every
  startup, prod included), even though the app authenticates only via OTP sessions
  (`SessionAuthenticator`). Exclude that auto-configuration (or declare an explicit empty
  `UserDetailsService`) so no unused credential exists. Found in the prod container's logs while
  fixing the BUGS.md batch.

- **BL-011**: The gallery detail page's revealed contacts and the moderation queue's contact
  chips print the contact type's slug (`whatsapp: …`) instead of its display name —
  `gallery.ContactMethodViewDto`/`moderation.ModerationContactMethodViewDto` carry only
  `ContactType.slug`. Now that `contact_type.name` exists (decision 5), carry and render the
  name instead. Found while fixing the BUGS.md batch.

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
  it just never reaches the view). Surface it on `adminauth/login-code.html` and
  `submission/login-code.html` once there's a concrete UX for it. Found while implementing the
  `adminauth`/`submission` view layers (M4).

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
