.PHONY: fix checkstyle spotbugs archunit static-analysis

# All targets run through the `maven` tooling service already defined in
# docker-compose.yml (profile "tools") — no static analysis runs on the host.
MVN := docker compose --profile tools run --rm maven

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
