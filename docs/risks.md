# Risks

Risks discussed and settled with the user directly, not derived from a generic template — each
one is grounded in this project's actual constraints (`scope.md`, `decisions.md`) and its Plan B
is checked against what's actually already built into the architecture, not just plausible-
sounding.

---

## 1. Schedule risk

A broad MVP — 6 vertical slices, two independent OTP systems (admin + visitor), application-level
encryption, database-driven catalogs, an analytics dashboard — built by a solo developer against
two fixed external dates (mid-demo 9 Oct 2026, final submission 6 Nov 2026 — `scope.md`
Constraints/Time), under a strict TDD discipline that adds real, non-optional overhead per
`CLAUDE.md`.

- **Probability:** High. ~4 weeks from today to the mid-demo, solo, with no second developer to
  parallelize slices across.
- **Impact:** High. A missed or visibly broken mid-demo is hard to recover from with only ~4
  weeks left before the final submission.
- **Plan B:** Scope flexes before quality does. The mid-demo has already been narrowed to the
  **core loop only** — `gallery` + `submission` + `moderation` (see `milestones.md`) —
  `catalogadmin` and `analytics` are deliberately pushed past it, not squeezed in. If even the
  core loop is at risk by Oct 9, demo whichever subset of
  UC-BROWSE-APPROVED → UC-CREATE-TESTIMONIAL → UC-APPROVE-TESTIMONIAL is actually working
  end-to-end, and say plainly what's still missing rather than presenting a broken flow as done.

## 2. Personal-data / privacy risk

The app stores real identifying and contact data about exchange students — names, roll numbers,
admission years, login email, and optional contact methods (`scope.md` Constraints/Legal;
decisions 5, 6, 10).

- **Probability:** Medium. Most of this is already designed down: encryption at rest for the
  login email and every `ContactMethod.value` (decision 6), a mandatory data-processing consent
  checkbox before anything is even persisted (decision 7), public display capped at a derived
  "First L." label that never appears on list/card views (decision 10), and contact values never
  included in a normal browse/detail response — only returned via the explicit reveal action, and
  only the entries marked public (decision 5 / UC-REVEAL-CONTACT). What's left as residual risk
  is operational (encryption-key and HMAC-pepper handling in deployment) and a genuine scope gap:
  there is no in-app full-erasure flow.
- **Impact:** High. This is real personal data about real students; a mishandling incident carries
  both legal and reputational weight, regardless of how unlikely it is.
- **Plan B:** A submitter can already remove any single contact method from public visibility at
  any time, by editing their testimonial and flipping that entry's `is_public` flag off (decision
  5) — this takes effect without re-entering moderation, since contact-method changes never gate
  the moderation short-circuit (decision 18). Full removal of a submitter's data from the system
  is **not** a self-service or admin in-app feature for MVP — that request goes to the OGE head,
  who resolves it manually, outside the application. This is a deliberate scope boundary, not an
  oversight, and it's the honest current answer rather than a promised feature that doesn't exist.

## 3. Moderation-bottleneck risk

Exactly one admin account exists, and moderation is strictly one testimonial at a time by design
— decision 18 explicitly rejected a bulk-approve/bulk-reject action for MVP.

- **Probability:** Medium. Depends entirely on submission volume, which is unpredictable before
  launch. The one-at-a-time constraint is a deliberate MVP simplification, not a technical limit
  that happens to be there.
- **Impact:** Medium. `NFR-MODERATION-QUEUE-PERFORMANCE` guarantees the *queue view itself* stays
  fast to load even with dozens of items pending, but that doesn't change that a human reviews one
  at a time — a growing backlog simply delays how fast new testimonials go public.
- **Plan B:** Honestly, there is no already-built feature that solves this — batch approval does
  not exist and was deliberately left out (decision 18). If this risk actually materializes, the
  right move is to revisit decision 18 and add bulk-approve/reject as a new, explicitly recorded
  decision — `decisions.md` already names this as the intended path ("if a real moderation-volume
  problem shows up later, it can be added as a new decision"). Until then, the existing
  requirement to verify an email via OTP before even opening the submission form (decision 17)
  already acts as a natural throttle against drive-by or junk submissions inflating the queue.
