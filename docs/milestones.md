# Milestone Plan

Built backward from the two fixed external dates in `scope.md` (Constraints/Time): mid-demo
**9 Oct 2026**, final submission **6 Nov 2026**. Dates below are weekly-cadence targets for a
solo developer working TDD, not hard commitments — see `risks.md` #1 (schedule risk) for what
happens if a target slips.

## Revised in light of scoping feedback

The first pass assumed all 6 vertical slices (`gallery`, `submission`, `moderation`,
`catalogadmin`, `adminauth`, `analytics`) would be demo-ready by 9 Oct. Discussing schedule risk
against the actual solo/TDD pace made that unrealistic for ~4 weeks of work. The plan below
narrows the mid-demo to the **core loop only** — a visitor can submit, an admin can moderate, and
approved testimonials are browsable — and deliberately defers `catalogadmin` and `analytics` to
the final submission. Nothing about the MVP scope itself (`scope.md`) changed — only what's
demoed on which date.

## Milestones

- **M0 — Design baseline locked** (done, 2026-09-10/11)
  `scope.md`, `use-cases.md`, `nfr.md`, `decisions.md`, `architecture.md`, `api-spec.yaml`, plus
  this risk/milestone/test-plan pass.

- **M1 — Project skeleton & domain layer** (target 2026-09-17)
  Maven/Spring Boot project scaffold; `Dockerfile` + `docker-compose.yml` (app + Postgres);
  `application.yml` dev/prod profiles; Flyway migrations seeding `Country`, `TopicGroup`, `Topic`,
  `ContactType`, `Achievement`; `domain` entities + repositories; `common` (error handling,
  `EncryptedValueConverter`, `EmailLookupHashService`).

- **M2 — Auth foundations** (target 2026-09-24)
  `adminauth` (OTP request/verify/session) and `submission`'s visitor-OTP half
  (`VisitorOtpService`, per-email/per-IP rate limiting). Covers UC-ADMIN-OTP-REQUEST,
  UC-ADMIN-OTP-VERIFY, UC-VISITOR-LOGIN.

- **M3 — Submission core** (target 2026-10-01)
  Create/edit-testimonial flow: `SubmissionController`/`SubmissionService`, upload validation,
  `PhotoStorageService`, the modified-flag and moderation-short-circuit logic (decision 18).
  Covers UC-CREATE-TESTIMONIAL, UC-EDIT-TESTIMONIAL.

- **M4 — Moderation + gallery, closing the core loop** (target 2026-10-08, one-day buffer before
  the demo)
  `moderation` (pending queue, approve/reject one at a time) and `gallery` (browse/filter/search/
  paginate, expand, fullscreen photos, reveal-contact). Covers UC-VIEW-PENDING-QUEUE,
  UC-APPROVE-TESTIMONIAL, UC-REJECT-TESTIMONIAL, UC-BROWSE-APPROVED, UC-FILTER-COUNTRY,
  UC-SEARCH-KEYWORD, UC-FILTER-TOPIC, UC-EXPAND-TESTIMONIAL, UC-VIEW-PHOTOS-FULLSCREEN,
  UC-REVEAL-CONTACT.

### 🎯 Mid-demo — 2026-10-09

Full core loop live end-to-end, containerized: submit → moderate → browse. Topics/achievements
present only as seeded data (no catalog UI yet). No homepage dashboard yet. Explicitly **not**
ready at this point: `catalogadmin`, `analytics`, `RejectedTestimonialCleanupJob`.

- **M5 — Catalog admin** (target 2026-10-16)
  `catalogadmin`: create/rename/reorder/deactivate/re-parent/delete for topic groups, topics,
  and achievements, with deactivation and delete cascading to existing testimonials (decision
  28), and the gallery's separate `groupIds`/`topicIds` filter (decision 29). Covers
  UC-MANAGE-TOPIC-GROUPS, UC-MANAGE-TOPICS, UC-MANAGE-ACHIEVEMENTS.

- **M6 — Analytics dashboard** (target 2026-10-23)
  `analytics`: country map + achievement/score/topic-group stat cards. Covers UC-VIEW-DASHBOARD.

- **M7 — Retention job & hardening** (target 2026-10-30)
  `RejectedTestimonialCleanupJob` (UC-PURGE-REJECTED, decision 3); a full NFR verification pass
  (performance + security NFRs, see `test_plan.md`); a security review pass.

- **M8 — Final polish & submission** (target 2026-11-06)
  Deployment docs, full automated test suite green, buffer for whatever the hardening pass turns
  up.

### 🎯 Final submission — 2026-11-06

All 6 slices complete and demoed together: the mid-demo's core loop plus `catalogadmin`,
`analytics`, the retention job, and the full NFR/test-plan acceptance gate (see
`test_plan.md` §3).
