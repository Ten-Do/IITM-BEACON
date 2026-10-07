# Backlog

## TODO

- **BL-062**: A method a public page doesn't take — `POST`/`PUT`/`PATCH`/`DELETE` on `/`,
  `/gallery`, `/gallery/{id}`, `GET`/`PUT` on `/gallery/{id}/contact`, `OPTIONS` anywhere —
  answers the HTML 404 page (M7, decision 33), because `SecurityConfig`'s method-specific matchers
  send it to the `denyAll()` tail before Spring MVC could answer 405. Decision 33 otherwise says an
  unsupported method is a 405 with `Allow`. Decide whether 404 is acceptable for these; if not,
  answer 405 with the right `Allow` from the tail's page handler (without loosening a matcher) and
  test it. Found while answering unknown paths with the 404 page (M7).

- **BL-061**: The visitor's code page (`submission/login-code.html`) has no "Use a different
  email" link, unlike the admin's (UC-ADMIN-OTP-VERIFY); a visitor who mistyped their email has to
  go back through the header link or the browser's Back button. Add the link (to
  `/submissions/login`, which forgets the pending email) and cover it in the page tests and the
  e2e baseline. Found while moving the login email into the session (M7).

- **BL-060**: Session fixation before login: the login email is now kept in the session between
  the email and code steps (M7), but the session id is rotated only at the successful verify
  (BL-034). An attacker who planted a session id in the victim's browser could open
  `/submissions/login/code` (or `/admin/login/code`) with it after the victim's email step and
  read the email shown there. Rotate the session id at the email step too (adjusting
  `config.SessionFixationTest`, which pins the id surviving until login) — decide, implement and
  test. Found while moving the login email into the session (M7).

- **BL-059**: `static/js/photo-viewer.js` imports PhotoSwipe from its version-less WebJar URL, so
  the browser revalidates it on every page instead of caching it for a year like every other
  asset (`config.StaticAssetsConfig`, M7). Pass the versioned URLs from the template (e.g. data
  attributes rendered with `@{...}`) and test that the viewer still opens. Found while making the
  static assets cacheable (M7).

- **BL-058**: `server.forward-headers-strategy` is relied on being off (decision 27: trusting
  `X-Forwarded-*` from the whole Docker network would let a client spoof its IP), but it isn't set
  explicitly, so a cloud platform's auto-detection could switch it on. Set it to `none`
  explicitly in `application.yml` (and the test copy), with a guard test. Found in the M7
  security fixes.

- **BL-057**: Requests that Spring Security's `StrictHttpFirewall` rejects (e.g. `/gallery;x=1`,
  answered 400) get none of the security headers — no CSP, `X-Content-Type-Options`, frame
  options or `Cache-Control` — because the rejection happens before the header writers run.
  Give those responses the same headers (a custom `RequestRejectedHandler`) and the decision-33
  error representation, with a test. Found in the M7 security fixes.

- **BL-056**: The public JSON endpoints — the admin and visitor OTP request/verify — accept a
  request body of any size, read in full before validation. Cap JSON bodies (e.g. a small limit
  for everything except the submission endpoints, as `config.NonUploadMultipartFilter` now does
  for multipart) and answer 413 per decision 33, with tests. Found in the M7 security fixes.

- **BL-055**: An anonymous multipart POST to the three submission endpoints (`POST
  /submissions/form`, `POST /api/submissions`, `PUT /api/submissions/mine`) without an
  `X-XSRF-TOKEN` header is still parsed up to `max-request-size` (1010 MB) by the CSRF check
  before it is refused — the M7 multipart limit exempts those paths so real uploads keep working.
  Closing it means refusing such requests before the CSRF check when there is no visitor session,
  which changes decision 32's ordering and the expired-session redirect of decision 23 — decide
  whether that trade-off is wanted, then implement and test it. Found in the M7 security fixes.

- **BL-054**: The generic HTML error page (decision 33, `templates/error/page.html`) always shows
  the visitor header, also when an admin hits an error on a moderation or catalog page, so the
  admin loses their navigation there. Choose the header by the session's role (or by the path, as
  `SecurityConfig` does for the login redirect) and test both. Found while building the error
  page (M7).

- **BL-053**: `GET /api/gallery/testimonials/{id}` still accepts hex and signed spellings of an id
  (`/0x1A`, `/+26`) because Spring's `Long` converter uses `Long.decode`, while the page route
  `/gallery/{id}` (decision 33) accepts only 1–18 digits. Give the REST route the same
  `\d{1,18}` pattern so both answer 404 alike, and test it. Found while fixing the client-error
  statuses (M7).

- **BL-052**: Every `@SpringBootTest` context schedules the real retention job
  (`moderation.RejectedTestimonialCleanupJob`, Sundays 03:00 UTC, real clock) because the test
  `application.yml` repeats the real cron. A test run that overlaps that minute could purge
  fixtures with an old `rejected_at` while a test still uses them. Set
  `beacon.retention.cleanup-cron: "-"` in the test configuration (adjusting
  `config.RetentionCronConfigTest`'s drift guard, which today requires the copy to be verbatim)
  or exclude `SchedulingConfig` from test contexts. Found while building the retention job (M7).

- **BL-051**: A visitor's edit that loaded their testimonial just before the retention job purged
  it (decision 3) fails on commit with Hibernate's stale-state error, which surfaces as a 500 —
  rare (a rejected testimonial, the weekly 03:00 UTC run) but unhandled. Map it to a readable
  outcome (e.g. "your testimonial was removed — start a new one") in `SubmissionService` /
  `SubmissionViewController`, with a test that deletes the row between load and save. Found while
  building the retention job (M7).

- **BL-050**: "Delete photo files after commit" now exists three times —
  `CatalogAdminService.deletePhotoFilesAfterCommit`,
  `ModerationService.deletePurgedPhotoFilesAfterCommit` and `PhotoStorageService`'s deferred
  delete. Move the pattern into `config.PhotoFileDeleter` (e.g. `deleteAfterCommit(photos)`) and
  make the three callers use it, keeping their tests green. Found during M7.

- **BL-049**: Listing pages load lazily, one query per collection (N+1): a gallery page of 20
  cards issues about 110 SQL statements and a moderation queue page of 20 about 350 (sections,
  photos per section, tags per photo, contacts, achievements). Every performance NFR still
  passes with a wide margin (`nfr-verification.md` §2), but the cost grows with the page size —
  the API allows `size` up to 100. Fetch what a page shows in a bounded number of queries (fetch
  joins / `@EntityGraph` / batch fetching), and pin the statement count per page in a test.
  Found in the M7 performance pass.

- **BL-048**: The production jar carries `application-dev.yml` (with the dev default encryption
  key and pepper) and the H2 driver, though neither is used under `prod`. Keep dev-only
  configuration and the H2 dependency out of the prod artifact (e.g. a Maven profile for dev, or
  excluding the file at packaging), without breaking `make` targets, tests and local runs.
  Found in the M7 security review (SR-24).

- **BL-047**: A submission can carry any number of contact methods — `TestimonialSubmissionRequest`
  has no `@Size` on its contact list (the form offers one row per contact type). Decide a cap
  (e.g. one per contact type, or a small number), reject more server-side as a field error, and
  test the boundary. Found in the M7 security review (SR-23).

- **BL-046**: `common.ratelimit.RateLimiterService` evicts a bucket 10 minutes after its last use
  and keeps at most 100 000 buckets (both hard-coded), so a configured window longer than 10
  minutes isn't fully honoured and spraying many keys can evict buckets; `VisitorOtpService`
  keeps at most 50 000 pending OTPs, so a flood of requests can evict legitimate ones (failing
  closed). Derive the eviction from the configured windows and decide/test sensible caps. Found
  in the M7 security review (SR-22).

- **BL-045**: `GlobalExceptionHandler` logs the JSON parser's own message at DEBUG for an
  unreadable body, and that message can repeat part of a submitted value (possibly a contact or
  an email). Log a fixed description instead, and test that a submitted value never reaches the
  log. Found in the M7 security review (SR-21).

- **BL-044**: Sessions expire after 24 hours idle (decision 23) but have no absolute lifetime — an
  admin session kept alive by the session ping lasts forever. Decide whether an absolute
  lifetime is wanted (e.g. 7 days for the admin), implement and test it. Found in the M7
  security review (SR-20).

- **BL-043**: `common.crypto.EncryptedValueConverter` calls `SecureRandom.getInstanceStrong()` for
  every encryption — a blocking entropy source on some systems and a new instance each time.
  Use one shared non-blocking `SecureRandom` (e.g. `new SecureRandom()` / `DRBG`) and keep the
  IV-uniqueness tests green. Found in the M7 security review (SR-19).

- **BL-042**: `SecurityConfig` permits `/h2-console/**` (and exempts it from CSRF) and sets
  `X-Frame-Options: SAMEORIGIN` in every profile, though the console exists only in `dev`.
  Apply that matcher, the CSRF exemption and the relaxed framing only in `dev`, and test that
  `prod` denies the path. Found in the M7 security review (SR-18).

- **BL-041**: `config.PhotoFileDeleter` resolves a stored `file_path`/`thumbnail_path` against the
  uploads root without checking that the result stays inside it — an absolute path or `..` in
  the database would delete a file elsewhere. Paths are server-generated, so the risk is low;
  normalise and refuse (log) any path outside the root, with tests. Found in the M7 security
  review (SR-17).

- **BL-040**: The gallery search `q` doesn't escape SQL `LIKE` wildcards
  (`TestimonialSpecifications`): searching `%` matches every testimonial and `_` matches any
  character. Not an injection (the pattern is a bound parameter), but wrong results. Escape `%`,
  `_` and the escape character, and test searches for them. Found in the M7 security review
  (SR-16).

- **BL-039**: HTTP responses are sent uncompressed — `server.compression` isn't enabled in
  `application.yml`, and nothing in `docker-compose.yml` sits in front of the app to gzip them.
  The homepage dashboard (decision 30) now carries the world map inline: about 100 KB of SVG
  path data in every `/` response, most of a phone's first page load, and SVG paths compress
  very well. Enable compression for `text/html`, `text/css`, `text/javascript` and
  `application/json` (with a sensible `min-response-size`), unless the production deployment
  puts a compressing reverse proxy in front of the app — decide which, and test that a
  `GET /` with `Accept-Encoding: gzip` comes back compressed. Found while building the
  dashboard map (M6).

- **BL-038**: Renaming a topic's or achievement's slug in the catalog (decision 28) while a
  visitor has the submission form open breaks that visitor's submit: the form posts sections by
  `topicSlug` and ticks by achievement slug, so the old slug is rejected as "Unknown or inactive
  topic"/"achievement" — an error naming an internal slug the visitor can't explain.
  Deactivating (or hiding through its group) that topic or achievement meanwhile does the same.
  No reload is needed — the re-shown form is rebuilt from the current catalog
  (`TopicPickerModel.alignSections`) — but that rebuild silently drops the section posted under
  the old slug, so its typed answer is lost (and new photos, BL-025), and a ticked achievement
  comes back unticked. Decide whether to accept it (rare, admin-only action), or make the form
  post ids instead of slugs (api-spec `TestimonialSubmission` change), or keep the posted
  answer and show a "the topic list changed" message; document and test the chosen behaviour.
  Found while building the catalog admin (M5).

- **BL-035**: The moderation queue may show a section's photos in a different order than the
  public article: `GalleryService.toSectionView` sorts a section's photos by `displayOrder`, but
  `ModerationService.toSectionView` maps `section.getPhotos()` as loaded, and
  `TestimonialSection.photos` has no `@OrderBy` — so the admin reviews (and pages through in the
  fullscreen viewer) an order visitors never see. Sort by `displayOrder` there too (or put
  `@OrderBy("displayOrder")` on the mapping and drop both manual sorts), with a test that inserts
  photos out of order. Found while showing photo tags and the fullscreen viewer in the
  moderation queue.

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
  resolved from a locale (likely the request's `Accept-Language`, falling back to the JVM
  default — not confirmed by running) — so they can render in another language next to English
  UI text. Give every constraint on `TestimonialSubmissionRequest` (including its nested records)
  an explicit English message (or pin the validation message locale, e.g. via
  `jakarta.validation.constraints.*.message` keys in `messages.properties`); `countryCode`'s two
  constraints, `rollNumber`'s `@Pattern`, `admissionYear`'s `@Min` and `sections`' `@NotEmpty`
  already have one. Found while fixing the BUGS.md batch.

- **BL-020**: The pre-existing tests in `SubmissionServiceEditTest` never flush, and their
  "reloaded" testimonial is the same in-memory instance from the persistence context — so they
  never check what actually reaches the database, which is how the BUGS.md edit HTTP 500
  (duplicate `TestimonialAchievement`, `photo_tag` unique violation) slipped through. Make them
  flush + clear + reload like the tests added for that fix. Found while fixing the BUGS.md batch.

- **BL-019**: `GET /admin/login` still shows the email step to an admin who already has a live
  session, unlike `GET /submissions/login`, which sends a logged-in visitor straight on; the same
  goes for `GET /admin/login/code` (the visitor's `/submissions/login/code` redirects). Redirect
  an `ADMIN` session to `/moderation/queue`. Found while fixing the BUGS.md batch.

- **BL-018**: `SubmissionViewController.requestOtp` (`POST /submissions/login`, also used by the
  code page's "Resend code") doesn't catch `TooManyRequestsException`, contradicting decision 21
  (a View-Controller turns it into an inline error). With the default visitor limit of one
  request per email per minute, pressing "Resend code" within a minute shows the browser
  `GlobalExceptionHandler`'s raw JSON 429. Catch it and re-render the code page with a readable
  error, as `AdminAuthViewController` does. Found while fixing the BUGS.md batch.

- **BL-016**: `src/test/resources/application.yml` shadows `src/main/resources/application.yml`
  on the test classpath, so tests never load the main file — `jpa.open-in-view: false`,
  `hibernate.ddl-auto: validate`, and `flyway.enabled` are never exercised by the suite, and
  every main-file setting a test depends on has to be duplicated by hand (the upload limits
  already are, guarded by `config/UploadLimitsConfigTest` and its siblings). One consequence: no
  H2 test validates the entities against the migrated schema — `BeaconApplicationTests`' Javadoc
  claims it does, but with `ddl-auto` shadowed Boot's default with Flyway is `none` (a schema
  drift left it green); only `domain.PostgresMigrationParityTest` (M7, on Postgres) checks it, and
  it sets the main-file settings itself through `testsupport.PostgresContainerSupport`. Rename the
  test file to a profile overlay (e.g. `application-test.yml` + an active `test` profile) so it
  layers on top of the main file instead of replacing it, and fix that Javadoc. Found while fixing
  the BUGS.md batch.

- **BL-015**: The seeded guiding prompt of the `general` topic is "Anything else you'd like to
  add. (mandatory catch-all)" (V13) — the "(mandatory catch-all)" note is spec wording from
  `use-cases.md`'s catalog table, yet it's rendered to visitors as the textarea's label on the
  submission form. Replace the seeded prompt with visitor-facing text via a new migration. Found
  while fixing the BUGS.md batch.

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
  existing constructor-injected-service-field exemptions). Records added since hold lists without
  a copy too and widen the set (count not re-run): `moderation.ModerationQueueCardDto`,
  `submission.TopicCatalogEntryDto`, `GalleryViewController.SectionBlock`;
  `analytics.AnalyticsSummaryDto`/`DashboardView` already copy. Found while implementing the
  `gallery` slice (M4) and extended while implementing `submission`'s view layer (M4); `mvn test`
  itself is unaffected since SpotBugs isn't bound to the `test` phase.

- **BL-002**: Cross-testimonial photo browsing by tag — a dedicated view where visitors
  browse/filter photos (by auto topic-tags and/or free-form custom tags, both already in the
  core schema — see `docs/decisions.md`) across all testimonials, not scoped to one
  testimonial. In scope, pending design and implementation; nothing of it is built yet.
