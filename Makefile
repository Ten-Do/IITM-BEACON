.PHONY: fix checkstyle spotbugs archunit static-analysis e2e e2e-update-screenshots perf cve-scan

# All targets run through the `maven` tooling service already defined in
# docker-compose.yml (profile "tools") — no static analysis runs on the host.
MVN := docker compose --profile tools run --rm maven

# Browser end-to-end tests run through the separate `e2e` service (profile
# "tools", image built from Dockerfile.e2e): pinned Chromium + fonts, so
# screenshots are pixel-identical on every machine. `--build` keeps the image
# in sync with Dockerfile.e2e (a no-op rebuild from cache otherwise).
E2E := docker compose --profile tools run --rm --build e2e

# Known-vulnerability scanner (pinned). Its vulnerability database is cached in
# the `trivy-cache` Docker volume between runs.
TRIVY := docker run --rm -v trivy-cache:/root/.cache/ -v "$(CURDIR)/target:/scan" \
	-v "$(CURDIR)/.trivyignore:/.trivyignore:ro" aquasec/trivy:0.75.0
CVE_IMAGE := iitm-beacon:cve-scan

## Auto-fix the safely-mechanical subset of Checkstyle findings (import
## order, unused imports) — the Java equivalent of `eslint --fix`/`prettier
## --write`. Run this before `make checkstyle` to shrink its output to
## findings that need a human decision.
fix:
	$(MVN) spotless:apply

## Style/convention check (Checkstyle) — source only, no compile needed.
checkstyle:
	$(MVN) checkstyle:check

## Bug-pattern check (SpotBugs + FindSecBugs) — needs compiled bytecode first.
spotbugs:
	$(MVN) compile spotbugs:check

## Architecture rules only (fast, scoped) — the full suite runs them too via `mvn test`.
archunit:
	$(MVN) test -Dtest="com.iitm.beacon.architecture.**"

## Everything above, one call.
static-analysis: checkstyle spotbugs archunit
	@echo "All static analysis checks passed."

## Browser end-to-end tests only (`mvn -Pe2e test` in the Playwright image) —
## plain `mvn test` never starts a browser. On a screenshot mismatch the
## actual/diff PNGs land in target/e2e-screenshots/<TestClass>/.
e2e:
	$(E2E)

## Same run, but (re)writes every missing or changed screenshot baseline in
## src/test/resources/e2e-screenshots/ instead of failing. Review the PNGs
## before committing them.
e2e-update-screenshots:
	$(E2E) -De2e.updateScreenshots=true

## Manual performance pass (`./mvnw -Pperf test`, the @Tag("perf") tests only;
## plain `mvn test` skips them): seeds a production-sized dataset into
## PostgreSQL 16 and measures the gallery, filter/search, moderation-queue and
## dashboard endpoints over real HTTP against their p95 NFR thresholds
## (docs/nfr.md). Runs on the HOST, not in the `maven` tools container:
## Testcontainers needs the host's Docker daemon to start PostgreSQL. Run it
## with nothing else heavy on the machine. Results are logged and written as a
## Markdown table to target/perf/results.md; record them in
## docs/nfr-verification.md.
perf:
	./mvnw -Pperf test

## Known-vulnerability (CVE) scan with Trivy, in Docker: builds the app image
## from Dockerfile and scans it — the OS packages of the runtime image and every
## library inside the Spring Boot jar. Lists HIGH and CRITICAL findings and
## fails if there are any not accepted in .trivyignore (each entry with its reason and
## review date); record the result in docs/security-review.md. The
## image tarball is removed again afterwards (target/ itself is kept out of the
## build context by .dockerignore).
cve-scan:
	docker build --pull -t $(CVE_IMAGE) .
	mkdir -p target
	docker save $(CVE_IMAGE) -o target/cve-scan-image.tar
	$(TRIVY) image --input /scan/cve-scan-image.tar --scanners vuln \
		--severity HIGH,CRITICAL --exit-code 1 --timeout 30m --ignorefile /.trivyignore; \
	status=$$?; rm -f target/cve-scan-image.tar; exit $$status
