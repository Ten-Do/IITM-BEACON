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
`PhotoTag` table, independent of the automatic topic tag it inherits from its section. Both the
maximum number of photos per testimonial and the maximum size of a single photo file are
enforced server-side and set via environment variables — no specific number is fixed in this
documentation.

**Rationale:** Filesystem storage matches a lightweight, containerized deployment target and
avoids bloating Postgres/H2 with binary data — simpler than external object storage for MVP
scope. Attaching photos to the specific section they illustrate (rather than to one
undifferentiated pile per testimonial) is both a better authoring experience and gives every
photo a meaningful topic tag for free; the free-form tag then lets the submitter add finer
detail (e.g. a specific dish, a specific event) that no fixed topic list could anticipate.
Count/size limits live in env vars, not code, so they can change without a redeploy.

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
`ContactType` is a seed-only reference table (`slug`, `label`/placeholder, `display_order`,
`active`) following the same pattern as `Topic` (decision 11) but with no admin-catalog UI — a
new type is a direct database edit, same as `Country` (decision 1). `ContactMethod`
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
(see `use-cases.md`), running from red (0) to green (10).

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
  crypto and OTP generation. Two narrow, justified exclusions (`spotbugs-exclude.xml`):
  `EI_EXPOSE_REP`/`EI_EXPOSE_REP2` scoped to `domain..` only, for Lombok-generated JPA entity
  accessors; `EI_EXPOSE_REP2` on the `objectMapper` field of `RestAccessDeniedHandler`/
  `RestAuthenticationEntryPoint` specifically, a constructor-injected shared Spring bean, not
  attacker-controlled mutable state.
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
