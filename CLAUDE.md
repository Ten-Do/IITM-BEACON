# IITM Beacon — Exchange Student Testimonial & Photo Gallery

Web gallery: prospective IITM exchange students browse alumni testimonials/photos, filterable by country.

## Tech stack

- Java + Maven, Spring Boot (Spring Web, Spring Data JPA, Thymeleaf), MultipartFile for uploads
- H2 for dev/test, PostgreSQL for prod
- Must run containerized: Dockerfile for the app + docker-compose for app+Postgres locally

## Actors

- **Visitor** — no login. Browses approved testimonials.
- **Authenticated Visitor** — a Visitor who logged in with an email OTP (only required to
  submit/edit, never to browse). Can submit a new testimonial or fully edit their own existing
  one (goes back to pending).
- **Admin** — approves/rejects pending testimonials.

Full permission/workflow detail: `docs/scope.md` (actors, constraints) and `docs/use-cases.md`
(workflow, as use-case scenarios).

## Rules

- A testimonial is never publicly visible until an admin approves it.
- Architecture, DB schema, API contracts, and design decisions live in `docs/`.
- Speak with me on Russian (plans and questions should be written on Russian), but all text in repository should be on English.

## Design principle

- Layered (Controller → Service → Repository) _and_ sliced by feature — each layer is split into vertical slices, not one monolithic package per layer. Concrete slice boundaries get defined during architecture design (`docs/architecture.md`), not assumed here.

## Docs & backlog

- `docs/scope.md` — goals, in/out of scope, actors, constraints (budget/technical/time/legal).
- `docs/use-cases.md` — use-case diagram + functional requirements as use-case scenarios.
- `docs/nfr.md` — non-functional requirements as Quality Attribute Scenarios (QAS).
- `docs/decisions.md` — ADR-style log of accepted decisions only — no status field; nothing is
  recorded until it's actually decided.
- `docs/api-spec.yaml` — OpenAPI 3 contract for every endpoint.
- `docs/architecture.md` — layered+sliced design, C4 diagrams, DB schema, entity relationships.
- `BACKLOG.md` — the task backlog. See workflow rules below.

## Workflow

- We're currently in the architecture design phase. Don't write implementation code unless explicitly told to.
- When a concrete unit of work comes up (a class, endpoint, migration, decision to revisit), **stop and ask** what to do with it: add to `BACKLOG.md`, implement now, or something else. Never decide this on your own.
- If, mid-task, you find something out of scope for the current task, add it to `BACKLOG.md` as a new entry rather than doing it now or leaving a silent `// TODO` in code.
- Keep the backlog lean: one entry = one concrete, actionable unit of work. No vague entries, no duplicates. If an entry becomes irrelevant, remove it.
- New BACKLOG units should be added to the top.
- BACKLOG.md entries are in scope: they're work that still needs designing/implementing, not
  exclusions. Never describe a backlog item as "out of scope" in `scope.md`.

## Ask, don't assume

- If a prompt is vague or ambiguous, mandatory ask clarifying questions before writing any code. Do not guess at intent.
- Never decide unilaterally on anything you're unsure about — requirements, naming, schema shape, architecture, edge-case behavior, trade-offs. Ask. All decisions belong to the user.
- If you find any contradiction in the documentation or architecture, report it and ask how to resolve it.

## Testing

- Work is done TDD: failing test before implementation, then make it pass. No untested module gets merged. Full testing methodology lives in the `tdd-enforcer` subagent — invoke it for implementation work.
- Exception: browser end-to-end tests (Playwright, `@Tag("e2e")`, run in Docker via `make e2e`) are NOT written TDD-style. There is nothing to screenshot before the UI exists, so they're written *after* the implementation, as the final check that the finished feature works end to end. TDD still applies to everything else, including the unit/MockMvc tests of the same feature.
- E2e tests assert ONLY with screenshots: drive the page into a state (navigate, click, type, press keys, swipe, wait for it to settle), then compare a screenshot against its baseline — that one image checks the presence, layout and look of every element at once. No hard-coded colours, pixel coordinates, bounding boxes, computed styles, scroll offsets, element counts or other DOM measurements as assertions; waits used only to synchronise are fine. Anything that isn't visible on the page (HTTP status codes, headers, security checks, request counts) belongs in unit/MockMvc tests, not e2e. The user reviews the baselines by eye.

## Java/Spring baseline requirements

- Layered architecture: Controller -> Service -> Repository -> DB. Controllers stay thin, no business logic in them. Logic in Service.
- Use DTOs at the web boundary; never expose JPA entities directly in request/response bodies.
- Validate all inputs with Bean Validation (`@Valid` + `jakarta.validation` annotations) on DTOs. Validate file uploads server-side (type, size) — never trust the client-sent content-type.
- Centralized error handling via `@RestControllerAdvice`: meaningful HTTP status codes, never leak stack traces or internal messages to the client.
- Constructor injection only — no field `@Autowired`.
- Logging via SLF4J, never `System.out.println`.
- Config through `application.yml`/properties + profiles (dev/prod). No hardcoded secrets or credentials — secrets come from environment variables.
- Once past H2 prototyping, manage schema changes with migrations (Flyway/Liquibase); don't rely on `ddl-auto=update` in production.
- Prefer `Optional<T>` over returning `null` from service/repository methods.
