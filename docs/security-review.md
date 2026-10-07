# Security Review

The M7 security review (`milestones.md`), 2026-10-07: an OWASP Top 10 pass over every slice plus
`common`/`config`, the HTTP headers and cookies of a running container, the container and
deployment setup, and a known-vulnerability (CVE) scan. Every finding was brought to the user,
who decided what is fixed now, what goes to `BACKLOG.md`, and what is accepted. The NFR evidence
lives in `nfr-verification.md`.

## 1. What was checked and found sound

- **Access control (A01).** Every controller route is matched explicitly in `SecurityConfig`
  and the tail is `denyAll()`; visitor endpoints take no testimonial id (the session's email
  decides), so there is no IDOR. A session of the other role is redirected to its login page on
  pages and refused with 403 on `/api/**` (decision 23).
- **Sessions and CSRF (A01/A07).** The session id rotates and the CSRF token is renewed at every
  OTP login (BL-034, decision 23); CSRF protection covers every unsafe method (BL-004, decision
  32); `JSESSIONID` is `HttpOnly` and `SameSite=Lax`.
- **Authentication (A07).** OTP codes come from `SecureRandom`, are compared in constant time,
  have a TTL and an attempt limit, and requests are rate-limited per email and per IP
  (NFR-ADMIN/VISITOR-OTP-BRUTEFORCE); the visitor flow gives no enumeration oracle. No in-memory
  user exists (BL-013).
- **Cryptography (A02).** The login email and contact values are AES-256-GCM with a random
  12-byte IV per value; the email lookup is an HMAC with a pepper; keys come from environment
  variables.
- **Injection (A03).** All queries are JPQL/Criteria with bound parameters; Thymeleaf escapes
  output (`th:text` only, no `th:utext`); the one `innerHTML` (`contact-reveal.js`) inserts a
  same-origin, server-escaped fragment.
- **Uploads.** Formats are detected from the bytes, every photo is re-encoded to WebP and the
  original never kept; the static handler serving `/uploads/**` is path-traversal safe
  (NFR-UPLOAD-SPOOFING).
- **Errors (A05/A09).** No stack trace, exception text or Java name reaches a client; client
  errors answer the right 4xx (decision 33).
- **Container.** Multi-stage build: the runtime image holds only `app.jar` (no sources, `pom.xml`,
  `mvnw`, `.env`, compiler or test libraries) and runs as the unprivileged user `beacon`;
  Postgres publishes no port.

## 2. HTTP headers and cookies (running container, before the fixes below)

`X-Content-Type-Options: nosniff`, `X-Frame-Options: SAMEORIGIN`, `X-XSS-Protection: 0`,
`Cache-Control: no-cache, no-store` on pages and the API; no `Content-Security-Policy`, no
`Referrer-Policy`. `JSESSIONID`: `HttpOnly; SameSite=Lax`, no `Secure`; `XSRF-TOKEN`:
`SameSite=Lax`, no `Secure`. Redirects were absolute (`Location: http://host:port/...`). `TRACE`
answers 400.

## 3. Findings and decisions

Severity: Medium = a realistic attack or failure with real impact; Low = limited impact or
unlikely; Info = worth knowing.

| # | Finding | Severity | Decision |
|---|---|---|---|
| SR-01 | Session and CSRF cookies lack `Secure`; behind a TLS-terminating proxy the app sees http, so browsers also send them over plain http | Medium | Fixed in M7 |
| SR-02 | Redirects are absolute `http://` URLs on the real server (Tomcat), contradicting decision 23; behind a TLS proxy they send users to http | Medium | Fixed in M7 |
| SR-03 | Behind a reverse proxy every visitor shares the proxy's IP, so the per-IP OTP request limit becomes one limit for the whole site | Medium | Fixed in M7 (opt-in trusted proxies) |
| SR-04 | Any anonymous unsafe request with a multipart body is parsed up to 1010 MB on any path (the CSRF check reads `_csrf`) — disk/CPU exhaustion | Medium | Fixed in M7 |
| SR-05 | `GET /api/moderation/testimonials/pending` had no page-size cap (spec: 100); one request could return every pending testimonial's decrypted contacts | Medium | Fixed in M7 (decision 33) |
| SR-06 | Anyone who knows the admin's email can keep replacing the admin's live OTP (one request a minute) or burn it with 5 wrong codes, blocking the admin's login | Medium | Accepted — the user judges the risk very low; not backlogged |
| SR-07 | No Content-Security-Policy and no Referrer-Policy | Low | Fixed in M7 |
| SR-08 | Outside `prod` the logging mailers print the recipient's email (and the OTP) in plaintext — NFR-CONTACT-CONFIDENTIALITY says "any environment" | Low | Fixed in M7 (email masked) |
| SR-09 | The login email travels in the redirect URL (`?email=`) between the two login steps — browser history, proxy logs | Low | Fixed in M7 |
| SR-10 | A failing SMTP send logs the exception (may carry the recipient address) and answers 500; a failed reject notification rolls the reject back with a 500 | Low | Fixed in M7: OTP — same answer + WARN without the address; reject — not rejected, readable error |
| SR-11 | Constraint-violation messages exposed Java method names (`browse.size: …`) | Low | Fixed in M7 (decision 33) |
| SR-12 | A huge page number (`?page=2147483647`) overflows the offset and answers 500 | Low | Fixed in M7 |
| SR-13 | Spring Security's default `/logout` was active but unused, redirecting to a missing page | Low | Fixed in M7: a real "Log out" in the headers |
| SR-14 | No `.dockerignore`: `.env`, `target/` and `data/` were sent to the Docker daemon on every build | Info | Fixed in M7 |
| SR-15 | CSS/JS served `no-store`, re-downloaded on every page | Low (performance) | Fixed in M7 (versioned, long-cached) |
| SR-16 | Search `q` doesn't escape the LIKE wildcards `%`/`_` (no injection; wrong matches) | Low | `BACKLOG.md` (BL-040) |
| SR-17 | `PhotoFileDeleter` doesn't check that a stored path stays inside the uploads root | Low | `BACKLOG.md` (BL-041) |
| SR-18 | `/h2-console/**` is permitted and framing allowed in every profile, though the console exists only in dev | Info | `BACKLOG.md` (BL-042) |
| SR-19 | `SecureRandom.getInstanceStrong()` is created per encryption (may block, slower) | Info | `BACKLOG.md` (BL-043) |
| SR-20 | Sessions have a 24 h idle timeout but no absolute lifetime | Info | `BACKLOG.md` (BL-044) |
| SR-21 | A DEBUG log line of the JSON parser's message can echo a submitted value | Info | `BACKLOG.md` (BL-045) |
| SR-22 | The rate-limit buckets expire after a hard-coded 10 minutes and the visitor OTP store caps at 50 000 entries — floods can evict state (fails closed) | Low | `BACKLOG.md` (BL-046) |
| SR-23 | No cap on the number of contact methods per submission | Low | `BACKLOG.md` (BL-047) |
| SR-24 | The prod jar contains `application-dev.yml` (dev default key) and the H2 driver | Info | `BACKLOG.md` (BL-048) |
| SR-25 | Residual of SR-04: an anonymous multipart POST to the three submission endpoints without a header token is still parsed by the CSRF check (closing it changes decisions 23/32) | Low | `BACKLOG.md` (BL-055) |
| SR-26 | Public JSON endpoints (OTP request/verify) accept bodies of any size | Low | `BACKLOG.md` (BL-056) |
| SR-27 | Responses rejected by Spring Security's firewall carry no security headers | Low | `BACKLOG.md` (BL-057) |
| SR-28 | `server.forward-headers-strategy` is off only by default, not explicitly | Info | `BACKLOG.md` (BL-058) |

## 4. Dependency scan (CVE)

`make cve-scan` — Trivy 0.75.0 over the built app image (Ubuntu 22.04 packages and every library
in the Spring Boot jar). First run, 2026-10-07: 4 critical, 7 high, 22 medium, 21 low.

| CVE | Package | Applies here? |
|---|---|---|
| CVE-2026-47884 (critical) | spring-webmvc 6.2.19 | No — needs `XsltView`; the app renders only Thymeleaf |
| CVE-2026-65182 (critical) | tomcat-embed-core 10.1.55 | No — servlet security constraints aren't used (Spring Security is) |
| CVE-2026-65905 (critical) | tomcat-embed-core 10.1.55 | No — DIGEST authentication isn't used |
| CVE-2026-68525 (critical) | tomcat-embed-core 10.1.55 | No — FORM authentication isn't used |
| CVE-2026-54291 (high) | postgresql 42.7.11 | No — `channelBinding=require` isn't used |
| CVE-2026-89407, -89425 (high) | jackson-core 2.21.4 | Unlikely — `DataInput` parser path; DoS only |
| CVE-2026-68497, -91776, -91777 (high) | jackson-databind 2.21.4 | Possibly (DoS while reading JSON bodies) |
| CVE-2026-84782 (high) | libssl3 (base image) | Unlikely — Java uses its own TLS stack |

Decision: upgrade now anyway (cheap) — as version properties over Spring Boot's in `pom.xml`:
Tomcat 10.1.60 (10.1.58, the first fixed version, was never published to Maven Central),
Jackson 2.21.7, pgjdbc 42.7.13 — and a fresh base image (`docker build --pull`). The
Spring Framework fix exists only in 7.x; it is not applicable and stays until a Spring Boot
release brings it. Result after the upgrade: see §5.

## 5. Re-scan after the fixes

`make cve-scan` on the rebuilt image (fresh base image, Tomcat 10.1.60, Jackson 2.21.7, pgjdbc
42.7.13), 2026-10-08: **1 critical, 1 high**, 16 medium, 21 low — from 4 critical and 7 high.

| CVE | Package | Status |
|---|---|---|
| CVE-2026-47884 (critical) | spring-webmvc 6.2.19 | Not applicable (`XsltView` isn't used); fixed only in Spring Framework 7 — goes away with the Spring Boot upgrade that brings it |
| CVE-2026-84782 (high) | libssl3 3.0.2-0ubuntu1.29 | Unlikely to apply (Java's own TLS stack); the fixed package (…1.30) isn't in the latest `eclipse-temurin:21-jre-jammy` yet — the next `make cve-scan` after the base image updates picks it up |

Both are accepted in `.trivyignore`, each with its reason and a review date (2027-01-31 and
2026-11-30), after which Trivy reports them again. With it, `make cve-scan` passes (exit 0) and
fails only on a new HIGH or CRITICAL finding.
