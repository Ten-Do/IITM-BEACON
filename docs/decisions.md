# Decisions Log

> **Locked — approved baseline (2026-09-10).** Reviewed for consistency across `scope.md`,
> `nfr.md`, `use-cases.md`, `architecture.md`, and `api-spec.yaml`. AI agents: do not edit this
> file — wording, structure, or cross-references — without the user's explicit approval first.

ADR-style log of **accepted decisions only**. One entry per decision — what and why. There is
no status field: a decision is recorded here once it's actually made, never while still open.
An undecided question gets resolved through discussion before it's written down, not logged as
pending. If a decision changes later, this file is updated in place to reflect the new
decision — it is not kept as history.

---

## 1. Country reference data

**Decision:** `Country` is a reference table keyed by its ISO 3166-1 alpha-2 code (e.g. `IN`,
`US`, `DE`), with a `name` column holding the canonical English short name. The table is
seeded once, in full, via a migration — it is not grown dynamically from submissions.

**Rationale:** ISO 3166-1 is the standard, unambiguous way to store a country reference list —
avoids free-text typos/casing drift, and the alpha-2 code is directly usable by common map
visualization libraries for the analytics dashboard's country map.

## 2. Photo model

**Decision:** Uploaded photos are stored on the filesystem, on a directory mounted as a Docker
volume — the DB stores the relative file path, not the image bytes. A `Photo` belongs to a
`TestimonialSection`, not directly to a `Testimonial`; deletion cascades transitively (deleting
a `Testimonial` deletes its `TestimonialSection` rows, which delete their `Photo` rows). A
testimonial renders as an article: each filled section shows its topic label as a subheading,
its answer text, then its own photos, followed by the next filled section. Each `Photo` may
additionally carry 0–10 free-form, submitter-entered tags (Instagram-style), stored in a
`PhotoTag` table, independent of the automatic topic tag it inherits from its section. The
maximum number of photos per testimonial, the maximum per section (topic), and the maximum size
of a single photo file are all enforced server-side and set via environment variables (their
current defaults are in decision 22).

Stored filenames are a randomly generated UUID (`<uuid>.webp`, plus `<uuid>-thumb.webp` for the
thumbnail — every upload is re-encoded, decision 22), never the client's original filename or
declared `Content-Type` — so a path under the public `/uploads/**` prefix isn't sequential or
otherwise guessable from a testimonial or photo id. Files are served back to any client through a
plain Spring static resource handler (a `WebMvcConfigurer` bean in `config`, mapping `/uploads/**`
to the mounted directory) — not a dedicated per-photo endpoint, and not owned by any one feature
slice. Upload validation (`PhotoStorageService`, `PhotoImageProcessor`) proves a file is a real
image (NFR-UPLOAD-SPOOFING) by detecting its format from its actual bytes with the registered
`ImageIO` readers (`ImageIO.getImageReaders` against an `ImageInputStream`) and then decoding it —
a file no registered `ImageReader` can decode is rejected, regardless of its declared
`Content-Type` or filename extension. What is stored is never the uploaded file itself, only the
app's own re-encoding of it (decision 22).

**Rationale:** Filesystem storage matches a lightweight, containerized deployment target and
avoids bloating Postgres/H2 with binary data — simpler than external object storage for MVP
scope. Attaching photos to the specific section they illustrate (rather than to one
undifferentiated pile per testimonial) is both a better authoring experience and gives every
photo a meaningful topic tag for free; the free-form tag then lets the submitter add finer
custom details a fixed topic list could never anticipate. A plain static resource handler keeps
photo-serving as simple as the filesystem storage it's built on — no new endpoint, no slice
ownership question; UUID filenames are a low-cost mitigation against enumerating photos of a
testimonial that isn't (or never will be) publicly approved, since the handler itself carries no
per-request authorization. Detecting the format with the same `ImageIO` readers that then decode
the image needs no separate content-sniffing library (e.g. Apache Tika), while still validating
real file content instead of trusting client-supplied metadata. Count/size limits live in env
vars, not code, so they can change without a redeploy.

## 3. Rejected-testimonial retention

**Decision:** Rejecting a testimonial is a soft delete: status becomes `REJECTED`, immediately
hidden from the public gallery and the admin pending queue. A scheduled job, running weekly,
hard-deletes `REJECTED` testimonials (row, sections, and photo files) 30 days after rejection.

**Rationale:** Gives a buffer/audit trail against accidental admin rejects while still
converging on permanent removal, without manual cleanup. Weekly is frequent enough relative to
the 30-day window and keeps the job's DB load low.

## 4. Admin authentication

**Decision:** Exactly one admin account exists. Its email lives in config (`ADMIN_EMAIL` env
var) — no password, no `User` table. Login is passwordless via a 6-character alphanumeric OTP:
emailed to `ADMIN_EMAIL` in real environments (logged instead of sent in dev/test); held
in-memory in a single service bean (code, expiry, remaining attempts) — no DB table; TTL and
max attempts are both env-configurable; exceeding max attempts invalidates the OTP; no cap on
concurrent sessions (the admin may be logged in on multiple devices at once).

**Rationale:** Matches actual scope — exactly one real admin. Avoids password storage/reset
flows or multi-admin management nothing in scope asks for. In-memory OTP state is sufficient
since there's only ever one admin and one live OTP at a time — contrast with decision 17, where
the same in-memory idea has to be keyed and concurrent-safe because many visitors can hold a
live OTP at once.

## 5. Contact method model

**Decision:** Contact info is modeled as two tables, not a single field on `Testimonial`.
`ContactType` is a seed-only reference table (`slug`, `name` — the display name shown next to
each contact row, e.g. "WhatsApp" — `label`/placeholder, `value_pattern`, `display_order`,
`active`) following the same pattern as `Topic` (decision 11) but with no admin-catalog UI — a
new type is a direct database edit, same as `Country` (decision 1). `value_pattern` is a regular
expression, stored without `^`/`$` anchors, that a contact value (trimmed) must match in full to
be accepted — typically an alternation of the shapes that type accepts (e.g. a phone number, a
handle, or a profile link). The same pattern is checked server-side (`String.matches`) and
rendered as the contact input's HTML `pattern` attribute, so it must be valid in both Java and
browser (`v`-flag) regex syntax — e.g. `-`, `(`, `)` escaped inside character classes. It is
nullable: a type added by hand without a pattern only requires a non-blank value. Validation
lives in the database, not in code, so adding a type stays a database edit. `ContactMethod`
(`testimonial_id` FK cascade, `contact_type_id` FK, `value` — encrypted via the same converter
as email, `is_public`, `display_order`) lets a submitter add 0–N contact entries, each
independently marked public or private via its own `is_public` boolean. An entry stored with
`is_public = false` is never shown publicly, independent of the testimonial's approval status.

**Rationale:** Scope allows multiple contact methods per submitter (email, WhatsApp, Telegram,
...), each independently revealable — a single field can't represent that. Exposure should be
the submitter's explicit choice made per contact method (they may want their email public but
their Instagram private), not one all-or-nothing flag, and not an automatic consequence of
admin approval either way. No admin use case manages contact _types_, unlike topics/
achievements, so it doesn't need the heavier catalog-admin treatment (decision 9) —
seed-and-edit-directly is enough, matching `Country`.

## 6. Encryption and equality lookup

**Decision:** `Testimonial.email` and every `ContactMethod.value` are encrypted at rest via a
shared application-level `AttributeConverter` (AES, key from an env var) — the only fields
treated this way. Neither is included in a normal browse/detail response: contact-method values
are only decrypted through the dedicated reveal-contact request, and the email only for
admin-facing moderation views or the visitor's own login flow. `Testimonial.email` additionally
carries `email_lookup_hash` — a separate, deterministic HMAC-SHA256 column (server-side pepper
env var), unique and indexed — used only to answer "does a testimonial already exist for this
email" (UC-VISITOR-LOGIN). It never round-trips back to plaintext, it only proves equality. No
other field gets a lookup hash: contact-method values are never searched by equality, only ever
decrypted for direct display.

**Rationale:** Encrypting searchable fields (identity fields, section text) would break
free-text search; disk-level encryption alone wouldn't protect against direct DB access.
Scoping encryption to the fields that are sensitive and never full-text-searched, revealed only
on an explicit action, gets real protection without breaking search. Standard AES with a random
IV/nonce produces different ciphertext each time, so it can't be used for an equality lookup —
a deterministic HMAC-based lookup hash solves that without weakening the encryption used for
at-rest storage/display.

## 7. Mandatory data-processing consent

**Decision:** Submission requires a mandatory checkbox — "I agree to the personal data
processing policy." If unchecked, the form does not submit and nothing is persisted. This is a
separate consent from a submitter's per-entry choice to mark a contact method public (decision
5).

**Rationale:** Data may only be stored if the submitter has explicitly agreed to have it
processed at all; that's distinct from whether any particular contact method is published
later.

## 8. Search and filtering

**Decision:** The public gallery supports a country filter, free-text search (over section
answer text and the submitter's first/last name — decision 10), and a topic filter, all
combinable.

**Rationale:** Country-only filtering doesn't scale as the gallery grows; the topic filter
follows directly from testimonials now being organized into tagged topic sections.

## 9. Vertical slice boundaries

**Decision:** Feature slices are `gallery`, `submission` (including visitor login/session —
decision 17), `moderation`, `catalogadmin`, `adminauth`, and `analytics`, all depending only on
a shared `domain` package (entities/repositories) and `common` (cross-cutting infra). No slice
imports classes from another slice directly. `catalogadmin` specifically owns topic-group/
topic/achievement CRUD (create/rename/reorder/deactivate; `Topic` additionally supports
re-parenting — decision 11) — kept separate from `moderation` rather than folded into it.
Deactivating any catalog entry is always non-destructive: it stops being offered as a new pick,
but existing content referencing it keeps rendering unchanged.

**Rationale:** No slice depending on another's internals is what lets each be built, changed,
or deleted independently. The boundaries themselves follow the bounded contexts
`use-cases.md`'s own use-case diagram already draws: its "Submission" subgraph groups visitor
login with create/edit, and its "CatalogAdmin" subgraph is drawn separately from "Moderation" —
reference-data curation and testimonial-workflow moderation are different concerns even though
both are admin-only.

## 10. Identity fields and public display

**Decision:** A testimonial's identification fields are `first_name`, `last_name`,
`roll_number`, and `admission_year`, plus `email` (sourced from the visitor's login, decision
17, never typed on the form). All stay plain text — not specially encrypted, per `scope.md` —
and are visible in full only to the admin. The only public trace of identity is a label derived
from `first_name` + the first letter of `last_name` (e.g. "David J."), shown solely inside a
testimonial's already-open expanded article view (UC-EXPAND-TESTIMONIAL) — gallery cards/list
views show no name-derived label at all. Full-text search still matches against the full
first/last name.

**Rationale:** Roll number and admission year are distinct mandatory fields the admin needs for
manual moderation judgment (`scope.md` rules out automated registry verification). Splitting
name into first/last is what makes the derived "first name + last-initial" public label
possible. Keeping even that derived label off list/card views avoids identifying a submitter
from a quick scroll through the gallery; showing it only once a visitor has deliberately opened
one article is a smaller exposure.

Both fields are format-checked, not verified: `roll_number` must be in IITM's `AA00A000` shape
(two Latin letters, two digits, one Latin letter, three digits, e.g. `CS21B001`) — input is
trimmed and uppercased before validation, so `cs21b001` is accepted and stored as `CS21B001`;
`admission_year` must be between 1959 (IIT Madras's founding year) and the current year, the
upper bound taken from the application `Clock` rather than hardcoded. This catches typos and
obviously fake values without claiming the registry check that `scope.md` rules out.

## 11. Topic and topic-group catalog model

**Decision:** `Topic` is a database-driven reference table (`slug`, `label`, `guiding_prompt`,
`display_order`, `active`), not a Java enum or hardcoded list, optionally grouped: a nullable
`topic_group_id` FK to a `TopicGroup` table (`id`, `label`, `display_order`, `active`) — `null`
means standalone. Wherever topics are offered as a pick-list (submission form, gallery filter,
dashboard), only top-level entries (groups + standalone topics) are shown; picking a group
reveals all of its subtopics as optional input blocks at once. Adding, renaming, reordering,
deactivating, or re-parenting a topic (moving it between groups, or promoting it to standalone)
is done through the admin catalog screen (`catalogadmin`, decision 9), not a direct database
edit — no code change or redeploy required either way. Re-parenting is non-destructive:
existing `TestimonialSection` rows keep their `topic_id` regardless of the topic's current
group. The current seed list of topics lives in `use-cases.md` as a first draft, not a fixed
specification.

**Rationale:** Lets the actual topic/question wording be iterated on independently of code
changes, per explicit requirement — the architecture should not gate content changes behind a
deploy. A dedicated admin screen (rather than requiring direct DB access) is what actually makes
this safe for the admin to do day-to-day. Grouping keeps a flat list of ~46 leaf topics down to
~17 top-level picks (9 groups + 8 standalone), keeping the form approachable while still
letting a testimonial fill in as many subtopics as it wants once a group is picked.

## 12. Testimonial content model

**Decision:** A testimonial's content is not a single free-text field. It is a set of
`TestimonialSection` rows (`testimonial_id`, `topic_id`, `answer_text`), each optional, with at
least one required to submit. A testimonial matches a topic filter on the public gallery if it
has a non-empty section for that topic.

**Rationale:** A single blank textarea causes writer's block; guided, optional topic sections
make the form easier to start, and double as a natural browsing/filtering dimension for
visitors.

## 13. Achievement checklist

**Decision:** Separate from topic sections: `Achievement` is a database-driven reference table
following the same pattern as `Topic` (`slug`, `label`, `display_order`, `active`), joined to a
testimonial via `TestimonialAchievement`. Rendered as checkboxes at the very end of the
submission form, right before submit. No photos attach to achievements.

**Rationale:** A fast, low-effort complement to the narrative topic sections — quick boolean
signals (e.g. "made new friends here") that are cheap for the submitter to tick and directly
usable as aggregate stats.

## 14. Analytics slice

**Decision:** A dedicated `analytics` slice serves the homepage dashboard (a map of countries
with testimonials, plus numeric stat cards over achievements, recommendation scores, and
testimonial counts per top-level topic group/standalone topic) via direct, read-only queries
against the shared domain repositories. No event-driven or asynchronous read-model layer for
MVP.

**Rationale:** Keeps the dashboard's data always current and the implementation simple;
event-driven decoupling is real but adds complexity (async processing, eventual consistency)
this project doesn't need yet, given `analytics` already depends on nothing but `domain` per
decision 9.

## 15. Recommendation score

**Decision:** Every testimonial includes a required 0–10 recommendation score (one per
testimonial, not per section), shown as a slider with a fixed label and colour per point
(see `use-cases.md`), running from red (0) to green (10). On the form the slider starts at 10
for a new testimonial (and whenever a re-rendered form carries no readable score); the visitor
moves it down from there.

**Rationale:** A single, scannable signal alongside the narrative sections, reinforcing the
review-hub framing. Labels are deliberately worded so a merely lukewarm experience reads as
6–7 rather than 4–5, and the colour turns green starting at 6 — nudging the distribution toward
6–10, the way NPS-style scales do.

## 16. Future integration

**Decision:** A future merge with a separate, independently-developed sibling service (same
tech stack) is anticipated, targeting a single unified Spring Boot application.
`adminauth` is therefore designed as a self-contained, reusable module — the same admin
account and OTP mechanism would serve both services after the merge. No shared database
tables are planned between the two services' domains.

**Rationale:** Keeps `adminauth` decoupled from gallery-specific assumptions now, so it doesn't
need rework later — without taking on any dependency on, or responsibility for, the sibling
service's own scope.

## 17. Visitor authentication

**Decision:** A Visitor becomes an Authenticated Visitor via a passwordless, 6-character
alphanumeric email OTP (UC-VISITOR-LOGIN) — the same style of code as the admin's (decision 4),
but held differently: OTP state (`{code, expiresAt, attemptsRemaining}`) lives in an in-memory,
concurrent-safe store (e.g. a Caffeine cache) keyed by the plaintext email, with each entry's
TTL matching the OTP's own expiry — rather than the admin's single mutable field. The OTP
_request_ endpoint is additionally rate-limited per email and per IP (also in-memory, e.g. via
Bucket4j), and responds identically whether or not the email has an existing testimonial. On
successful verify, the system establishes a visitor-scoped session (a distinct principal/role
from the admin's) and looks up whether a testimonial already exists for that email (via
`email_lookup_hash` — decision 6) to route to create vs. edit. Submission doesn't separately
need a _dedicated_ anti-spam/email-verification step: this OTP requirement — every submitter
must verify their email before they can even open the create/edit form — serves that purpose
for free.

**Rationale:** Unlike the single admin (decision 4), many visitors can request/verify OTPs
concurrently, so a single mutable field isn't safe — a keyed, concurrent-safe map is the
minimal change that stays correct under concurrency. Staying in-memory (rather than a DB table)
is deliberate: it keeps the mechanism symmetric with the admin's and avoids a
persisted-secret table; the "encrypted at rest" rule (`scope.md`) doesn't apply to transient
in-process state, since NFR-CONTACT-CONFIDENTIALITY frames that rule around storage/backup
access, not the running application. The accepted trade-off is that an app restart drops all
in-flight visitor OTP requests, same as it already does for the admin's — just now affecting
potentially many visitors instead of one. Historically, skipping a dedicated
email-verification step was its own deliberate simplification, adopted before visitor login
existed; visitor OTP login was added later for a different reason — routing a login email to
its one testimonial, per `scope.md` — but it happens to satisfy that original concern too, so
no separate mechanism is needed.

## 18. Moderation workflow shape

**Decision:** Moderation is approve/reject one testimonial at a time (UC-APPROVE-TESTIMONIAL,
UC-REJECT-TESTIMONIAL) — there is no bulk-approve/bulk-reject action.

Separately, "what changed since the last approval" is tracked via explicit `modified` boolean
flags, not a stored snapshot diffed on read: `TestimonialSection.modified` is set true when an
edit changes that section's `answer_text` _or its photos_ (or adds a new section) — the flag
only matters, and is only ever read, once the testimonial has been approved at least once;
`Testimonial.identity_modified` is set true when any of
`first_name`/`last_name`/`roll_number`/`admission_year` changes. Both always drive full
re-moderation: editing an already-`APPROVED` testimonial resets it to `PENDING` if — and only
if — one of these two is set. `Testimonial.score_modified` is tracked the same way (set when
`recommendation_score` changes), but unlike the two above it never gates re-moderation by
itself — a score-only change on an `APPROVED` testimonial still short-circuits (see below); the
flag exists purely so an admin reviewing a testimonial for some other reason can see that the
score also changed. Changes to `country_code`, `TestimonialAchievement` rows, or any
`ContactMethod` are **not tracked at all** — no `country_modified`/`achievements_modified`/
`contacts_modified` columns exist: since none of them ever gates moderation and nothing else in
the system reads their change state, a dedicated flag for them would serve no purpose.

"Or its photos" specifically means: a photo added to a section, a photo removed from it, or a
change to an existing photo's free-form `PhotoTag` set (even with the underlying file
untouched) — tags are submitter-entered free text, the same kind of content `answer_text` is,
so retagging a kept photo sets `modified` exactly like editing the text would. On the write
side, an edit request identifies a kept photo by sending back its own current URL as that
photo's `fileRef` (instead of naming a new multipart file part) — this is what lets
`SubmissionService` tell "kept, maybe retagged" apart from "newly uploaded" without a separate
photo id in the API. Removing an entire section outright (leaving a topic out of the edit
request that had a filled section before) is different: the row is deleted via
`orphanRemoval` and does **not** set `modified` on anything — there's nothing left to carry the
flag, and removal itself needs no content-appropriateness review. The one required cross-check
this still leaves in place: a testimonial resulting from an edit must still have at least one
section (decision 12), so removing the last one is rejected the same way an empty submission
would be, independent of the `modified`/short-circuit machinery.

**Short-circuit:** if the testimonial's status was already `APPROVED` before this edit, and
neither `TestimonialSection.modified` (on any section) nor `identity_modified` is set, the
testimonial stays `APPROVED` — it never goes to `PENDING`, regardless of what else changed
(score, country, achievements, contacts). `SubmissionService` clears `score_modified` itself
immediately in this case (nothing entered the queue, so nothing needs to stay flagged), without
touching `reviewed_at` (no human reviewed it). Any edit that touches free-text content, photos,
or identity fields always goes back to `PENDING` for full re-moderation; so does any edit to a
testimonial that was `PENDING` or `REJECTED` before the edit, regardless of which fields
changed — it hasn't passed a full review yet, or was explicitly rejected, so any resubmission
gets one. `ModerationService` clears every flag back to `false` as part of a normal approve.

**Rationale:** `scope.md` specifies "approve/reject one at a time" for the MVP; a batch action
was speculative and isn't backed by any current use case — if a real moderation-volume problem
shows up later, it can be added as a new decision. Explicit flags (over a
snapshot-and-diff-on-read approach) are cheaper to query and have few, well-defined set/clear
points (one write path, one clear path), keeping staleness risk low in practice. The moderation
short-circuit exists because most of the admin's review burden is judging free-text/photo
content and identity claims for appropriateness — a country pick, a score, achievement
checkboxes, or a contact method's visibility/value carry no such judgment call, so gating those
edits behind a human review was pure overhead. Country/achievements/contacts get no tracking at
all; `score_modified` is kept as a tracked, admin-visible flag regardless — a deliberate choice,
not derived from the no-tracking-for-non-gating-fields reasoning above. Restricting the
short-circuit to already-`APPROVED` testimonials keeps it from ever skipping the _first_ real
review a testimonial gets.

## 19. Static analysis & architecture linting tooling

**Decision:** Four static-analysis tools, all invoked only through dockerized `make` targets
(`make fix`, `make checkstyle`, `make spotbugs`, `make archunit`, `make static-analysis`) via the
existing `maven` tooling service in `docker-compose.yml` — never on the host, never bound into the
default `mvn test`/`mvn package` lifecycle (ArchUnit is the one exception: it's plain JUnit 5
tests, so it runs as part of the normal test suite too).

- **ArchUnit** (`archunit-junit5` 1.4.2) — the architecture linter. Six rules in
  `src/test/java/com/iitm/beacon/architecture/`: no feature slice depends on another slice
  directly (one rule per slice, `SliceIsolationTest`); `domain` holds no `@Service`/`@Controller`
  classes (`DomainPurityTest`); `*Controller` classes don't depend on `*Repository` classes, are
  properly annotated, and live in a feature-slice package (`LayeringTest`); no field-level
  `@Autowired` injection, a regression guard (`InjectionStyleTest`); any `@EnableWebSecurity`
  class lives in `config..` (`SecurityConfigLocationTest`, added together with moving
  `SecurityConfig` there — see below).
- **Checkstyle** (`maven-checkstyle-plugin` 3.6.0, engine overridden to `checkstyle` 10.26.1) — a
  trimmed custom ruleset (`checkstyle.xml`, repo root) covering imports, whitespace, naming, and
  block hygiene, deliberately excluding the stock rulesets' Javadoc-coverage checks. Two naming
  exceptions codify existing, deliberate conventions rather than fighting them: the SLF4J `log`
  field name, and ArchUnit's `lower_snake_case` `@ArchTest` field idiom (the latter scoped to the
  `architecture` package only).
- **SpotBugs** (`spotbugs-maven-plugin` 4.9.3.0) with **FindSecBugs** (`findsecbugs-plugin`
  1.14.0) for security-focused bug patterns — relevant given this app's hand-rolled AES/HMAC
  crypto and OTP generation. Three narrow, justified exclusions (`spotbugs-exclude.xml`):
  `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` scoped to `domain..` only, for Lombok-generated JPA entity
  accessors; `EI_EXPOSE_REP2` on the `objectMapper` field of `RestAccessDeniedHandler`/
  `RestAuthenticationEntryPoint` specifically, a constructor-injected shared Spring bean, not
  attacker-controlled mutable state; the identical `EI_EXPOSE_REP2` false positive on three of
  M3's `submission`-slice fields (`SubmissionController.submissionService`,
  `SubmissionService.photoStorageService`, `VisitorAuthController.submissionService`) — same
  reasoning, each a constructor-injected Spring service bean, not attacker-controlled state.
- **Spotless** (`spotless-maven-plugin` 3.10.2) — not a checker, a fixer: `make fix` runs
  `spotless:apply` to mechanically correct import order and remove unused imports (the Java
  analogue of `eslint --fix`/`prettier --write`), configured with only the `importOrder`/
  `removeUnusedImports` steps so it can't introduce unrelated formatting diffs.

The one ArchUnit rule that was already violated when these tools were introduced
(`VisitorAuthController` calling `TestimonialRepository` directly, instead of through a not-yet-
built `SubmissionService`) is **left failing, not frozen** — `mvn test`/`make archunit` stay red
until `SubmissionService` exists; this is the only red check in the whole suite as of this
decision. Separately, introducing the `SecurityConfigLocationTest` rule surfaced a real
contradiction in this file's sibling `architecture.md` §3 (the `config/` bullet said Spring
Security config belongs there; the `adminauth/` bullet listed `SecurityConfig` as one of its own
files) — resolved by moving `SecurityConfig` (and its `H2ConsoleSecurityTest`) from `adminauth` to
`config`, matching `architecture.md`'s `config/` description, and correcting the `adminauth/`
bullet. All findings from Checkstyle/SpotBugs' first real run were fixed immediately rather than
left as debt: `make fix` handled the mechanical import issues; 7 over-length lines were rewrapped
by hand; `EncryptedValueConverter` was made `final` (the standard remedy for SpotBugs'
`CT_CONSTRUCTOR_THROW` — its constructor can throw, and a non-final class would leave that exposed
to a finalizer-attack subclass).

**Rationale:** ArchUnit is the standard, actively-maintained way to turn documented architecture
rules (decision 9's slice isolation, CLAUDE.md's layering/injection rules) into an executable,
continuously-enforced regression guard instead of a convention that only lives in a doc someone
has to remember to re-read. Checkstyle and SpotBugs were chosen over PMD as a second style/bug
linter because their coverage already overlaps enough at this codebase's size that a third tool
would mostly add noise and another config file to maintain, not new findings (BL-005 revisits
this once the codebase is bigger). Keeping every tool Docker/`make`-gated and out of the default
Maven lifecycle preserves this project's fast TDD loop (`mvn test`/`./mvnw test` unaffected by
Checkstyle/SpotBugs); ArchUnit is the deliberate exception because it's not an external tool, just
ordinary fast JUnit tests, so gating it separately would only add friction without protecting
anything. Leaving the one known violation failing (instead of using ArchUnit's `freeze()`) was a
deliberate choice for honesty over a clean build: a frozen violation is easy to forget, and this
one is already scheduled to be resolved by `SubmissionService` (M3).

## 20. Public achievements listing

**Decision:** `GET /submissions/achievements` is a public, unauthenticated endpoint listing
active `Achievement` entries (`slug`, `label`, ordered by `display_order`) — same pattern as
`ContactType`'s `GET /submissions/contact-types` (decision 5): seed-only reference data with no
admin-catalog UI dependency, served dynamically rather than hardcoded on the client, but with no
write side of its own.

**Rationale:** The submission form's achievement checklist (decision 13) must render in full
before or without any admin session — `Achievement` management itself stays admin-only
(`/catalog/achievements`, UC-MANAGE-ACHIEVEMENTS), but reading the current active list for the
submission form is a visitor-facing concern with no moderation/admin content in it, so it
belongs next to `ContactType`'s own public listing rather than behind `catalogadmin`'s
`adminSession` security requirement.

## 21. View layer: REST and server-rendered pages coexist

**Decision:** `gallery`, `moderation`, `submission`, and `adminauth` each gain a second,
separate `@Controller` ("View-Controller", e.g. `GalleryViewController`) alongside their
existing `@RestController`. A View-Controller calls its slice's own `Service` directly (a plain
Java method call, never HTTP/AJAX against the slice's own REST API) and returns a Thymeleaf
template name. The JSON contract in `api-spec.yaml` is unchanged by this — every existing REST
endpoint keeps its exact request/response shape; the View-Controller is a second caller of the
same `Service`, not a replacement for the first. No SPA: pages are plain server-rendered HTML
with ordinary `<form>` GET/POST navigation, and no page loads its content from the REST API via
XHR/`fetch`. Two small scripts do make background requests, each recorded as its own decision:
the session ping (decision 23) and the contact reveal, which fetches an HTML fragment from its
own page route (decision 27). In-page interactivity that a full page reload can't provide (the
submission form's topic picker and recommendation-score slider label, and future widgets like
them) uses **Alpine.js**, declared directly in the Thymeleaf templates' attributes, with larger
components registered via `Alpine.data(...)` in a page-specific script under `static/js/`.
Alpine is shipped as a Maven WebJar (`org.webjars.npm:alpinejs`, resolved version-less via
`webjars-locator-lite`) and served by the app itself under `/webjars/**` — no CDN, no Node build
step. The fullscreen photo viewer is PhotoSwipe, served the same way (decision 24). Templates
live under `src/main/resources/templates/<slice>/`, with shared chrome factored into
`templates/layout/` fragments (`shell.html` for `<head>`; `header-visitor.html` and
`header-admin.html` for the two nav-bar variants, which share one burger menu — decision 26),
and a single global stylesheet (`static/css/beacon.css`) built from the design tokens in
`ui-design/DesignSystem.dc.html`. `oge-logo.svg`, referenced by the visual mockups,
does not exist in this repository; both header fragments render a plain text wordmark
("IITM Beacon") in its place until a real logo asset is supplied.

A View-Controller catches the same domain exceptions its REST sibling lets propagate to
`GlobalExceptionHandler` (`NotFoundException`, `TestimonialNotPendingException`,
`SubmissionValidationException`, `TooManyRequestsException`, OTP-verification failures) and
turns each into a redirect or a re-rendered form with an inline error, instead of letting the
`@RestControllerAdvice` write a JSON body to what's supposed to be a browser navigation or a
form-POST response — `GlobalExceptionHandler` itself is unchanged and still owns every REST
error response.

Submission-form validation failures are reported per field, not as one generic banner.
`SubmissionValidationException` carries a list of `FieldViolation(field, message)` — every rule
one submission breaks, collected in a single pass (Bean Validation on
`TestimonialSubmissionRequest` plus `SubmissionService`'s own business rules) instead of stopping
at the first. The form adapter maps each violation's request-side path (`sections[i]…`,
`contactMethods[j]…`, indexed over the filtered request lists) back to the form field it came
from, and adds the form-only rules (custom tags sent for a photo that wasn't uploaded, a
"public" checkbox on a contact row with no value). `SubmissionViewController` then adds them to the
form's `BindingResult`, so each message renders next to its own field, with the top banner kept
as a summary. The browser can't re-fill `<input type="file">`, so when a rejected submission
carried new photos, the banner also tells the visitor to re-attach them. REST callers still get
the same violations, joined into `ErrorResponse.message`.

The submission form posts plain multipart `<form>` fields (flat/indexed, `@ModelAttribute`
bound), not the JSON-plus-`fileRef` `payload` part the REST endpoint expects. Rather than
changing that already-shipped REST contract or duplicating `SubmissionService.create`/`edit`'s
validation and persistence logic, `SubmissionService` gains two adapter methods,
`createFromForm`/`editFromForm`, that translate the form-bound command object into the exact same
`TestimonialSubmissionRequest` + file-map shape and then call the existing, unmodified
`create`/`edit`. `SubmissionService` also gains two form-only read methods, `listAllCountries`
and `listTopicCatalog` — unlike `gallery`'s equivalents, these return every active entry
regardless of whether it already has an approved testimonial, since a picker needs the full
catalog, not just what's already in the gallery; per decision 9, these are `submission`-local
types, not a shared import from `gallery`.

The form is assembled by the visitor, per UC-CREATE-TESTIMONIAL: a row of toggle chips offers
only the top-level catalog entries (topic groups and standalone topics), and picking one reveals
its input blocks (every subtopic of a group, or the standalone topic's own block). `general` is
pre-picked on load but can be un-picked like any other chip. Each pick's blocks sit in their own
`<fieldset>`, which Alpine disables while the pick is unselected, so an un-picked topic's fields
are never submitted — in edit mode, un-picking a topic therefore removes its sections, which is
exactly what `edit` already does for any section missing from the request. Without JavaScript
the form falls back to showing every block at once.

The HTML form's multipart request can carry several hundred parts (every visible subtopic's
text, photo inputs, and photo tags), far beyond Spring Boot 3.5's default
`server.tomcat.max-part-count` of 50, and the Boot-default multipart size limits (1MB per file,
10MB per request) sit below the app's own photo limits (decisions 2, 22). `application.yml`
therefore sets `max-part-count`, a per-file limit above the business photo-size limit (so an
oversized-but-plausible photo still gets `PhotoStorageService`'s own actionable validation
message), and a per-request limit sized for the maximum photo count, each overridable by an
environment variable. Multipart parsing is
resolved lazily, so a request that still breaks these limits fails inside the handler:
`SubmissionViewController` redirects back to the form with an inline error, and
`GlobalExceptionHandler` answers REST callers with 413 (size) or 400 (other multipart failures)
instead of a generic 500.

Three supporting infrastructure changes fall out of the same work, all pre-existing gaps this
batch happened to touch rather than new problems it introduced:
- `PhotoStorageService`'s one-line `urlFor` (`"/uploads/" + relativePath`) is promoted out of
  `submission` into `config.PhotoUrlResolver`, since `gallery` and `moderation` need the exact
  same mapping to build photo URLs and neither may import a class from `submission` (decision 9).
  `PhotoStorageService` itself now just delegates to it.
- Rejecting a testimonial (`ModerationService.reject`) needs to send an arbitrary
  subject/body email, which `config.OtpMailer`'s single `sendOtp(to, code)` method can't express.
  `config.NotificationMailer` (`send(to, subject, body)`) is added as a sibling interface, with
  the same `Smtp*`/`Logging*`, prod/non-prod profile split as `OtpMailer` — kept separate rather
  than widening `OtpMailer`'s contract, since sending an arbitrary notification isn't an OTP.
- Approving or rejecting a testimonial that isn't currently `PENDING` (e.g. a second admin
  double-clicking, or a stale page) now returns 409 via a new `TestimonialNotPendingException`,
  the same treatment `TestimonialAlreadyExistsException` already gets — `api-spec.yaml` only
  documented 404 for these two endpoints, but a wrong-state row is a different condition from a
  missing one and deserves its own status code, matching the project's existing 409 precedent.

Building real pages also closed a latent gap in `SecurityConfig`: its catch-all tail was
`.anyRequest().authenticated()`, which accepts *any* authenticated role — a `VISITOR` session
could reach a route with no explicit matcher. Now that every real route (REST and view) is
listed explicitly, the tail is `.anyRequest().denyAll()`.

**Rationale:** `scope.md`'s tech stack and `architecture.md`'s container diagram (§2.2) always
described this app as serving "REST API + Thymeleaf pages," and `pom.xml` already carried
`spring-boot-starter-thymeleaf` from the very first milestone — M1–M3 simply hadn't built the
page layer yet, so this isn't a new architectural direction, only the first time it's actually
wired up. Keeping the REST contract untouched and adding a second caller of the same `Service`
(rather than routing pages through the JSON API internally, or replacing the JSON API with
HTML responses) is the simplest option that satisfies both "no SPA" and "don't touch a
already-shipped, already-tested contract": no internal HTTP round-trip, no content-negotiation
branching in one controller, and `api-spec.yaml` stays the single source of truth for the JSON
shape. Catching domain exceptions locally in the View-Controller (rather than teaching
`GlobalExceptionHandler` to branch on `Accept`/response type) keeps the one existing,
well-tested error-handling path exactly as it is for the API and adds a narrow, view-specific
handling only where a browser navigation actually needs different treatment. Two new adapter
methods on `SubmissionService`, rather than reworking `create`/`edit` to accept either shape,
avoids touching an already-tested code path for a second, unrelated caller.

Alpine.js over the alternatives considered (Stimulus, htmx, petite-vue, hand-written vanilla JS):
it's built for sprinkling reactive state onto server-rendered HTML with no build step (~19KB
gzipped), and a chip picker or a live slider label is a few attributes in the template. Stimulus
needs a controller class per widget for the same result; htmx is aimed at server round-trips for
HTML fragments, a poor fit for purely client-side UI state like a slider; petite-vue has been
unmaintained since 2022. Serving it as a WebJar keeps its version in `pom.xml` alongside every
other dependency and avoids a runtime dependency on a third-party CDN.

## 22. Photo normalization pipeline and upload limits

**Decision:** Every uploaded photo is converted on upload; the uploaded file itself is never
stored. `submission.PhotoImageProcessor`:
- accepts any image a registered `ImageIO` reader can decode — the JDK's plus the TwelveMonkeys
  plugins (JPEG incl. CMYK, PNG, GIF, BMP, TIFF, WebP, PSD, PNM, TGA) — detecting the format from
  the bytes (decision 2, NFR-UPLOAD-SPOOFING). Nothing in use decodes HEIC/HEIF or AVIF, so
  those are refused like any unreadable file: "Unsupported image format. Please upload JPEG,
  PNG, WebP, GIF, TIFF or BMP.";
- reads every image's dimensions from the file header first and rejects a file declaring one
  larger than `max-pixels` (default 250 megapixels) before decoding anything — a
  decompression-bomb guard — with "The image is too large: at most 250 megapixels are allowed.";
- of a file holding several images (multi-page TIFF, a DNG's previews, GIF frames; at most 32
  are considered) uses the largest one that decodes, decoded with source subsampling so its long
  edge still covers the full-size target but a 48 MP photo never sits in the heap at full
  resolution;
- converts it to 8-bit sRGB from its embedded colour profile (e.g. an iPhone's Display P3; a
  PNG's `iCCP` profile is applied explicitly, since the JDK's PNG reader ignores it), keeps
  alpha, applies the EXIF orientation (1–8), and downscales it (progressive halving, then
  bicubic) to a full-size image of at most 2560 px and a thumbnail of at most 640 px on the long
  edge — never upscaling;
- encodes both as lossy WebP (quality 82 for the full size, 75 for the thumbnail) with the
  native libwebp bundled in `webp-imageio`, the only WebP writer for `ImageIO`. No metadata of
  the original — EXIF including GPS, XMP, ICC profile — is copied.

`PhotoStorageService` writes the pair as `<uuid>.webp` and `<uuid>-thumb.webp`, and `Photo`
gains nullable `thumbnail_path`, `width` and `height` (the full-size image's pixel size, which
the viewer needs before the image loads — decision 24). The app refuses to start if the WebP
encoder doesn't work on its platform, rather than failing the first upload. Edges, qualities
and the pixel limit are all `beacon.storage.*` settings with env overrides.

Limits, all env-configurable (defaults): at most 20 MB per uploaded file, checked before the
file is read (`BEACON_PHOTO_MAX_SIZE_BYTES`); at most 5 photos per topic section
(`BEACON_PHOTO_MAX_PER_SECTION`) and 50 per testimonial (`BEACON_PHOTO_MAX_COUNT`), both counting
kept and new photos together and both checked, for the form and the REST API alike, before any
file is converted or stored ("At most 5 photos per topic." at that topic's photos; "Too many
photos: maximum is 50."). The servlet's multipart limits sit above these so the app's own
messages win: 25MB per part, 1010MB per request (50 × 20 MB plus form text), 500 parts
(decision 21).

Photos stored before this pipeline are converted once by `submission.LegacyPhotoBackfill`, an
`ApplicationRunner` that runs synchronously at startup (on by default,
`BEACON_PHOTO_BACKFILL_ENABLED`). It walks the photos without a thumbnail in id order, 50 per
batch; converts each and writes the new files; then, in that photo's own transaction, re-points
the row at them only if the row is still unchanged; and deletes the original only after that
commit. A photo it can't convert (missing or undecodable file) is logged as a WARN and left
exactly as it was — row and file — to be retried on the next start; each pass logs a summary.
It writes nothing but the photo row's file and size columns: no testimonial status, `modified`
flag or timestamp changes. Until it is converted, a legacy photo is served with its original
file as its own thumbnail and no known size.

**Rationale:** Students mostly write from their phones, and a current phone photo was often
above the old 5 MB limit, so iPhone photos didn't upload at all. Serving the originals was also
a privacy leak — their EXIF carried the GPS position of where each photo was taken, publicly —
and made phone visitors download multi-megabyte files to show 200-pixel thumbnails. Re-encoding
every upload fixes all three at once: a size cap that fits real phone photos, no metadata ever
reaching the public `/uploads/**`, and a bounded full size plus a small thumbnail per photo. It
also strengthens spoofing resistance: whatever a file carries besides its pixels never reaches
the volume. WebP is supported by every current browser, is much smaller than JPEG at the same
visual quality, and keeps alpha; TwelveMonkeys fills the JDK's reader gaps (CMYK JPEG, more TIFF
flavours, WebP input). Converting existing photos at startup, rather than leaving them as they
were, gives every photo already in the gallery the same metadata stripping and sizes right away,
and doing it per photo with an after-commit delete means an interrupted or failed pass never
loses a file.

## 23. Page login redirect, return to the requested page, 24-hour session

**Decision:** An unauthenticated request — no session, or an expired one — to a page that needs
a login is redirected to that role's login page by a `DelegatingAuthenticationEntryPoint` in
`SecurityConfig`: `/moderation/**` to `/admin/login`, `/submissions/form` and
`/submissions/confirmation` to `/submissions/login`. Every other unauthenticated request — all of
`/api/**`, and anything that falls through to the `denyAll()` tail — still gets
`RestAuthenticationEntryPoint`'s JSON 401. The redirect's `Location` is relative
(`/admin/login`), not built from the request's own scheme and host. Before redirecting, a GET of
one of those pages is saved in the session: the `HttpSessionRequestCache` saves exactly those
GETs and nothing else, without a `?continue` marker. After a successful OTP verify,
`common.security.PostLoginRedirect` takes the saved request out of the session (always removing
it) and redirects to its path and query only if it was a GET, is a well-formed site-relative
path without dot segments, and lies under one of the role's own pages on a path-segment
boundary (admin: anything under `/moderation/`; visitor: `/submissions/form`,
`/submissions/confirmation`); otherwise to the role's default page (`/moderation/queue`,
`/submissions/form`). A form POST is never saved, so a submission sent with an expired session
lands on the form after the login, and what was typed in it is lost.

The HTTP session's idle timeout is 24 hours for both roles (`BEACON_SESSION_TIMEOUT`, Spring
Boot's default is 30 minutes). Every page that needs a login also carries
`static/js/session-check.js` (fragment `layout/session-check.html`): when its tab becomes
visible again — `visibilitychange`, no other trigger — it pings its role's session endpoint,
`GET /api/moderation/session` or `GET /api/submissions/session`, which answers 204 while the
session lives (the ping itself keeps it alive) and the usual JSON 401 (none or expired) or 403
(the other role's session) otherwise. On 401 or 403 the script reloads the page, which then goes
through the login redirect above and comes back. It never runs on the login pages.

**Rationale:** An expired session used to show the browser a raw JSON 401 body on the next page
load, with no way on but typing the login URL, and the 30-minute default logged visitors out
while they were still writing their testimonial on a phone. The request cache is deliberately
narrow: only the pages a login protects are worth returning to, and saving anything else — the
JSON API, or a stray request such as a browser's icon probe hitting `denyAll()` between the
redirect and the login — would overwrite the page the user actually wanted. Checking the saved
URL before using it (a GET of the role's own pages, path and query only, never a scheme or host)
keeps the return trip from becoming an open redirect or a hop into the other role's pages. The
relative `Location` keeps the browser on `https://` behind a proxy that terminates TLS, where the
app itself sees plain `http`. Pinging when the user comes back to a tab, instead of polling,
catches the moment it matters — before they type more into a page whose session is gone — and
costs nothing while the tab sits idle.

## 24. Fullscreen photo viewer: PhotoSwipe

**Decision:** The fullscreen photo viewer (UC-VIEW-PHOTOS-FULLSCREEN) is PhotoSwipe 5, served as
a WebJar (`org.webjars.npm:photoswipe`) the same way as Alpine (decision 21) and driven by
`static/js/photo-viewer.js`, an ES module (PhotoSwipe's core is only loaded on first open). The
markup contract lives in `layout/photo-viewer.html`: each thumbnail is a plain link to the
full-size photo carrying its pixel size (`data-pswp-width`/`-height`) and its caption
(`data-caption`), followed by the photo's tag chips — so without JavaScript a click just opens
the full image. Every element marked `data-photo-gallery` is one gallery the arrows step
through: the whole article on the gallery detail page, and each testimonial separately in the
moderation queue, where admins now open photos fullscreen and see their tags too. On top of
PhotoSwipe's own keyboard paging (←/→/Esc), swipe, pinch and double-tap zoom and focus handling,
the app adds a caption bar at the bottom (the topic, the photo's custom tags, an "i / n"
counter), keeps the photo clear of it, and pins the prev/next arrows to the bottom-left and
bottom-right corners level with the caption, on touch screens too — they never move with the
photo's size or the caption's length. A legacy photo with no stored size takes it from its
thumbnail. The earlier hand-written viewer, its markup skeleton in `layout/shell.html`, and its
CSS are gone.

**Rationale:** Most visitors are on phones, where a photo viewer needs swipe, pinch-zoom and
double-tap zoom; the hand-written viewer had none of them, no keyboard paging, and its arrows
moved with each photo's width. PhotoSwipe was chosen over extending that own vanilla viewer,
over GLightbox, and over an Alpine component for its mobile UX — pinch-zoom, swipe and focus
handling, built in and mature. The cost is that it needs each photo's pixel dimensions before
the photo loads, which is why the photo pipeline now records `width`/`height` (decision 22).

## 25. Browser end-to-end tests: Playwright in Docker, screenshot baselines

**Decision:** Browser end-to-end tests use Playwright for Java (`com.microsoft.playwright`,
test scope; JUnit 5 `@Tag("e2e")`, package `com.iitm.beacon.e2e`) and run only inside Docker: the
compose service `e2e` (profile `tools`), built from `Dockerfile.e2e` — the
`mcr.microsoft.com/playwright/java` image, whose tag must equal `playwright.version` in
`pom.xml`, with the project's Temurin 21 JDK copied in — started by `make e2e`;
`make e2e-update-screenshots` (re)writes the baselines that are missing or out of tolerance.
Surefire excludes the `e2e` tag by default, and the Maven profile `e2e` runs only that tag.
`E2eTestBase` boots the real app on a random port (test configuration, H2), captures OTP codes
from a mocked `OtpMailer`, and opens isolated desktop (1280×800) or mobile (390×844, touch)
browser contexts with a pinned locale, time zone and colour scheme. A test drives the page into
a state (clicks, keys, swipes) and asserts only with a screenshot — no hard-coded colours,
coordinates, computed styles or other DOM measurements; what isn't visible on the page (statuses,
headers, security checks) is tested with MockMvc instead. Screenshots are compared by the
project's own `ScreenshotAssert` against baselines in
`src/test/resources/e2e-screenshots/<TestClass>/<name>.png` (default tolerance: at most 0.1% of
the pixels may differ by more than 8/255 in a channel; on a mismatch the actual image and a diff
land in `target/e2e-screenshots/`). Every new baseline is reviewed by eye before it's accepted.
These tests are written after the implementation, not test-first (`CLAUDE.md`,
`test_plan.md` §1).

**Rationale:** MockMvc only sees rendered markup, never what the page's JavaScript does (the
Alpine form, the viewer, the burger menu, the session check). Screenshots are only comparable
when the same browser build and fonts render them, which the pinned Playwright image guarantees
on any machine — hence Docker only. The Java client keeps these tests in the project's one
Maven/JUnit toolchain, with no Node; it has no `toHaveScreenshot()` of its own (that is
Playwright Test for Node), hence `ScreenshotAssert`. One screenshot checks markup, layout and
behaviour at once. They can't be written test-first: there is nothing to screenshot before the
UI exists.

## 26. Responsive layout and a reusable burger menu

**Decision:** Every page is laid out for phones as well as desktops, with plain media queries in
`beacon.css` — no CSS framework: one breakpoint for phones and small tablets (≤720px) and one for
small phones (≤480px), plus the gallery list's own grid steps (4 cards per row from 1200px, 3
from 900px, 2 from 560px, 1 below). On a phone the article's sidebar moves under the content,
form rows stack, form controls use 16px text (so iOS doesn't zoom into a focused field), controls
get finger-sized tap targets, and long user-typed text wraps instead of widening the page. The
header nav collapses behind a burger button: `layout/nav-toggle.html :: toggle(navId)` plus
`static/js/nav-toggle.js`, a vanilla script driven only by markup (`data-nav-toggle`,
`aria-controls`, `aria-expanded`) that closes the menu on Escape, on a click outside it, or when
one of its links is followed. Both header fragments use it. It is progressive enhancement:
without JavaScript the button stays hidden and the nav stays visible, wrapping onto a new line.

**Rationale:** Students will mostly write their testimonials, and read others', on phones. A
markup-driven script rather than per-header Alpine state lets any future header reuse the menu
with one fragment include, on pages that don't load Alpine at all (decision 21 loads it only
where needed).

## 27. Contact reveal only from the article page, behind a same-origin check

**Decision:** A testimonial's public contact methods (UC-REVEAL-CONTACT) are revealed only
through its article page: `POST /gallery/{id}/contact` in `GalleryViewController` (an id of 1–18
digits; anything else is no such route). No JSON endpoint serves contacts any more —
`GET /api/gallery/testimonials/{id}/contact` is removed, and so is the old `?reveal=true` page
link. The article's "Reveal contact info" button is a plain POST form:
`static/js/contact-reveal.js` sends it with `X-Requested-With: fetch` and swaps the returned HTML
fragment (`gallery/contact-card.html`) in place — a spinner and `aria-busy` while waiting, no
scroll jump, focus moved to the card, an inline error with a retry on failure. Without JavaScript
the form submits normally and the server answers with the whole article, the card in place of
the button (`#contact`). An unknown or unapproved testimonial, or one without a public contact,
answers 404 "Contact info isn't available." — the three cases stay indistinguishable, as before.

The endpoint is protected only by a same-origin header check — no token, captcha or login, by
explicit choice. `@SameOriginOnly` on the handler is enforced by
`common.web.SameOriginInterceptor` using `SameOriginGuard`: the `Origin` header must be present
and equal this site's origin, and `Sec-Fetch-Site`, when the browser sends it, must be
`same-origin`; anything else gets a 403 with a one-line plain-text reason before the handler
runs. This site's origin is each request's own scheme, host and port, unless
`beacon.web.allowed-origins` (`BEACON_ALLOWED_ORIGINS`, comma-separated) lists the public
origin(s), which then replace it — needed behind a reverse proxy, where the app sees the
proxy's request. `server.forward-headers-strategy` is deliberately not enabled to derive that
origin from `X-Forwarded-*` headers: Tomcat would then trust them from the Docker bridge
network's private addresses, which would also let a client spoof the IP address the per-IP OTP
request limits count (decisions 4, 17). `SecurityConfig` narrows the gallery to match: only GET
and HEAD on `/`, `/gallery` and `/gallery/*`, and POST only on `/gallery/*/contact`. CSRF
protection stays off globally (BL-004); for this endpoint the Origin check is the protection.

**Rationale:** A public GET per id let anyone harvest every public contact in the gallery with a
loop over ids. The check is knowingly bypassable — a script that sets a forged `Origin` header
gets through. Its only purpose is to stop other websites (a browser never lets a page set
`Origin` or `Sec-Fetch-Site`) and naive scraping, at no cost to real visitors: no login, no
captcha, and no token that could expire on a page left open. It is a POST because browsers send
`Origin` on every POST, `fetch` and plain form submission alike. Having the script fetch the
server-rendered card keeps one template for the JavaScript and no-JavaScript paths.
