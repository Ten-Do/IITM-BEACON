# Backlog

## TODO

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
