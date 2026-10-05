.PHONY: fix checkstyle spotbugs archunit static-analysis e2e e2e-update-screenshots

# All targets run through the `maven` tooling service already defined in
# docker-compose.yml (profile "tools") — no static analysis runs on the host.
MVN := docker compose --profile tools run --rm maven

# Browser end-to-end tests run through the separate `e2e` service (profile
# "tools", image built from Dockerfile.e2e): pinned Chromium + fonts, so
# screenshots are pixel-identical on every machine. `--build` keeps the image
# in sync with Dockerfile.e2e (a no-op rebuild from cache otherwise).
E2E := docker compose --profile tools run --rm --build e2e

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
