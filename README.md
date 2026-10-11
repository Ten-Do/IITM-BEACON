# IITM Beacon

A web gallery of testimonials and photos from IITM exchange students, browsable by prospective
students and filterable by country. The exchange experience is currently an opaque, intimidating
black box for someone deciding whether to go — IITM Beacon turns it transparent by collecting
real alumni accounts (guided topic sections, photos, a recommendation score, optional contact
info) and letting an admin moderate them before they go public.

Built with Java, Spring Boot, and PostgreSQL, fully containerized with Docker.

Full design documentation — scope, use cases, architecture, decisions, risks, and the
milestone/test plan — lives in [`docs/`](docs/);

## Running it

Everything needs a running Docker daemon: PostgreSQL 16 is the only database, in production,
in development and in the tests.

- **Production-like, in Docker:** copy `.env.example` to `.env`, fill it in, then
  `docker compose up --build` — the app and its PostgreSQL, on <http://localhost:8081>.
- **Local development:** `./mvnw spring-boot:test-run -Dspring-boot.run.profiles=dev` — the app
  on <http://localhost:8080>, on a throwaway PostgreSQL 16 container that Testcontainers starts
  and removes again when the app stops, so every run starts from an empty, freshly migrated
  database. Login codes are written to the console log instead of being mailed.
- **Tests:** `./mvnw test` (every test context runs on one shared PostgreSQL 16 container);
  browser end-to-end tests with `make e2e`; static analysis with `make static-analysis`.

## Author

Iurii Rudenko — solo-team student, roll number **GE26Z857**.
