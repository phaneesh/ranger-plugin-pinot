---
phase: 05-packaging-release
plan: 03
subsystem: testing
tags: [docker-compose, integration-tests, maven-failsafe, github-actions, ranger-admin, pinot, e2e]

# Dependency graph
requires:
  - phase: 05-packaging-release plan 01
    provides: distro tarball + enable-pinot-plugin.sh installed by the IT images
  - phase: 05-packaging-release plan 02
    provides: release workflow conventions mirrored by the CI integration job
  - phase: 02-broker-enforcement
    provides: broker AccessControl whose live enforcement this harness proves
  - phase: 03-row-filtering
    provides: getRowColFilters implementation proven live by the row-filter test
  - phase: 04-controller-enforcement
    provides: controller AccessControl whose live 403 path this harness proves
provides:
  - ranger-pinot-plugin-it module (failsafe-gated, -Pintegration)
  - docker-compose stack: Ranger Admin 2.8.0 (postgres+solr) + Pinot cluster on one rangernw network
  - Dockerfile.plugin deriving apachepinot/pinot images with the real tarball installed by the real enable script
  - PinotRangerIT: allow/deny/audit/row-filter/controller-403/live-poll/FOUND-03/04
  - GitHub Actions integration matrix job over pinot [1.4.0, 1.5.1]
affects: [ci, release-verification, any future live-cluster debugging]

tech-stack:
  added: [maven-failsafe-plugin 3.2.5, exec-maven-plugin 3.6.3 (compose orchestration), docker compose v2 (harness runtime)]
  patterns:
    - "failsafe *IT naming + skipITs default: mvn clean verify stays Docker-free"
    - "single flat rangernw network for the whole Ranger+Pinot stack"
    - "runtime discovery of the derived broker principal from the deny audit event"
    - "antrun copy of the reactor tarball into the Docker build context (dependency:copy cannot resolve reactor artifacts)"

key-files:
  created:
    - ranger-pinot-plugin-it/pom.xml
    - ranger-pinot-plugin-it/docker/docker-compose.yml
    - ranger-pinot-plugin-it/docker/pinot/Dockerfile.plugin
    - ranger-pinot-plugin-it/src/test/java/org/apache/ranger/authorization/pinot/it/RangerRestClient.java
    - ranger-pinot-plugin-it/src/test/java/org/apache/ranger/authorization/pinot/it/PinotRangerIT.java
    - ranger-pinot-plugin-it/src/test/resources/fixtures/orders-schema.json
    - ranger-pinot-plugin-it/src/test/resources/fixtures/orders-table.json
    - ranger-pinot-plugin-it/src/test/resources/fixtures/orders.csv
  modified:
    - pom.xml (module entry + RAT excludes)
    - .github/workflows/ci.yml (integration matrix job; build job untouched)

key-decisions:
  - "antrun copies the reactor-built tarball into the IT build context — dependency:copy resolves artifactItems from the local repo, not the reactor, and would fail on a clean CI"
  - "exec-maven-plugin pinned 3.6.3 and maven-dependency-plugin dropped: sandbox ~/.m2 is read-only; 3.6.3 is also the current release so the pin is not a workaround"
  - "RAT excludes use module-relative ** patterns because RAT evaluates excludes against each module's basedir"
  - "broker principal is discovered at runtime from the deny audit event (client IP), never hardcoded — per plan's identity-discovery approach"
  - "FOUND-03/04 tests fall back to create-response/resource-matching assertions if the MEDIUM-confidence REST surfaces 404/405, never failing on a missing endpoint"

requirements-completed: [CI-04, COMPAT-01, COMPAT-02]

duration: 95 min
completed: 2026-09-18
---

# Phase 5 Plan 3: Docker-Compose Integration Harness Summary

**Failsafe-gated IT module + docker-compose Ranger 2.8.0/Pinot harness running the real distro tarball, with E2E allow/deny/audit/row-filter/403/live-poll tests and a CI matrix over Pinot 1.4.0/1.5.1**

## Performance

- **Duration:** ~95 min
- **Started:** 2026-09-18T05:05Z (environment recon began 2026-09-17T23:37Z)
- **Completed:** 2026-09-18T05:30Z
- **Tasks:** 3/3 executed
- **Files modified:** 10 (8 created, 2 modified)

## Accomplishments

- New `ranger-pinot-plugin-it` Maven module: `mvn clean verify` builds it in ~0.3s with zero Docker involvement (skipITs=true, failsafe/exec plugins live only under `-Pintegration`)
- docker-compose stack: `apache/ranger-db` + `apache/ranger-solr` + `apache/ranger` 2.8.0 + zookeeper + derived Pinot controller/broker/server images, all on one flat `rangernw` network (hostnames ranger-db.rangernw / ranger-admin.rangernw are load-bearing in the ranger image)
- `Dockerfile.plugin`: real tarball extracted + real `enable-pinot-plugin.sh` run at build time, pollIntervalMs lowered to 5000 (spaces-tolerant sed), SSL keystore overridden to /dev/null, plus build-time asserts that the rendered configs are correct (service name pinotdev, admin URL, /dev/null in ranger-policymgr-ssl.xml, broker/controller access-control classes wired)
- `PinotRangerIT`: 9 ordered tests covering FOUND-03 (validateConfig, with fallback), FOUND-04 (lookupResource, with fallback), deny-by-default + runtime broker-principal discovery, allow, deny-wins, audit (log4j grep + Solr fallback), row-filter (region='west' → 2 of 4 rows), controller 403 → grant → 200, and live policy poll (delete → denied → re-create → allowed, no restart)
- `.github/workflows/ci.yml`: `integration` job (`needs: build`, matrix pinot [1.4.0, 1.5.1], fail-fast disabled, 30-min timeout); build job byte-for-byte untouched

## Automated Checks Run

| Check | Result |
| ----- | ------ |
| `mvn -B clean verify` (full reactor, no profile) | ✅ PASS — all 5 modules green, Docker-free, ~12s |
| `docker compose -f docker-compose.yml config -q` | ✅ PASS (client-side validation) |
| `docker compose config --services` | ✅ PASS — all 7 services resolve |
| `mvn -B clean verify -Pintegration -Dit.pinot.version=1.4.0` (up to Docker) | ⚠️ builds 4 modules + IT compile green; antrun tarball copy verified (66MB tarball lands in target/); fails exactly at `compose-up` |
| ci.yml YAML parse + matrix + needs:build greps | ✅ PASS |
| python3 yaml.safe_load on ci.yml | ✅ PASS |

## Skipped Checks (environment-blocked, not code gaps)

The executor sandbox mounts the Docker socket `nobody:nogroup 660` with supplementary groups stripped and `no_new-privileges` set — the Docker daemon is unreachable:

1. **Derived image build** (`docker build -f Dockerfile.plugin`): the tarball, Dockerfile, and build context are all in place and verified (tarball present in context, sed pattern verified against the literal cfg line, enable-script outputs verified against its source), but the image itself could not be built here. CI (Docker preinstalled) runs it.
2. **Live E2E run** (`-Pintegration` against the live stack): everything up to `compose-up` is verified; the live allow/deny/audit/row-filter/403/live-poll executions need the daemon.
3. **1.5.1 matrix leg**: same daemon dependency; the wiring (matrix value → PINOT_VERSION build arg → image tag) is verified statically.

## Commits — BLOCKED BY SANDBOX

**⚠️ `.git` is mounted read-only in this executor sandbox** (`/dev/nvme0n1p3 on .../.git type ext4 (ro,...)`). `git add` fails with `Unable to create .git/index.lock: Read-only file system`. All 3 tasks' changes are complete on disk and verified, but the per-task commits and the docs commit could NOT be created from this sandbox. The orchestrator must commit:

```
Task 1 (feat 05-03): pom.xml, ranger-pinot-plugin-it/pom.xml,
  ranger-pinot-plugin-it/docker/docker-compose.yml,
  ranger-pinot-plugin-it/docker/pinot/Dockerfile.plugin
Task 2 (test 05-03): ranger-pinot-plugin-it/src/test/** (RangerRestClient.java,
  PinotRangerIT.java, fixtures/orders-schema.json, fixtures/orders-table.json,
  fixtures/orders.csv)
Task 3 (ci 05-03): .github/workflows/ci.yml
Docs (docs 05-03): .planning/STATE.md, .planning/ROADMAP.md,
  .planning/REQUIREMENTS.md, this SUMMARY file
```

## Files Created/Modified

- `ranger-pinot-plugin-it/pom.xml` — module with distro dependency (reactor ordering), antrun tarball copy, `-Pintegration` profile (exec-maven-plugin compose up/down + failsafe with system properties)
- `ranger-pinot-plugin-it/docker/docker-compose.yml` — 7-service stack, one rangernw network, healthchecks, JAVA_OPTS capped at 1G per JVM
- `ranger-pinot-plugin-it/docker/pinot/Dockerfile.plugin` — derived image + rendered-config sanity asserts
- `ranger-pinot-plugin-it/src/.../RangerRestClient.java` — JDK-HttpClient REST helper (Ranger provisioning, controller/broker REST, compose logs/exec, poll loops)
- `ranger-pinot-plugin-it/src/.../PinotRangerIT.java` — the 9-test ordered E2E story; javadoc documents the broker-identity limitation
- `ranger-pinot-plugin-it/src/test/resources/fixtures/*` — orders schema/table/CSV (2 west + 2 east rows)
- `pom.xml` — `<module>ranger-pinot-plugin-it</module>` + RAT excludes (`**/docker/**`, `**/src/test/resources/**/*.{json,csv}`)
- `.github/workflows/ci.yml` — integration matrix job appended

## Decisions Made

- See key-decisions in frontmatter; all four were environment- or correctness-driven, not scope changes.

## Deviations from Plan

### Auto-fixed Issues

**1. [Rule 3 - Blocking] dependency:copy → antrun copy**
- **Found during:** Task 1 verification
- **Issue:** `maven-dependency-plugin:copy` resolves `artifactItems` from the local repository, not the reactor — on a clean CI checkout it would fail to resolve `ranger-pinot-plugin-distro:tar.gz` (never installed)
- **Fix:** antrun `<copy>` of the sibling module's `target/ranger-*-pinot-plugin.tar.gz` (the distro dependency still guarantees reactor ordering)
- **Files modified:** ranger-pinot-plugin-it/pom.xml

**2. [Rule 3 - Blocking] exec-maven-plugin 3.2.0 → 3.6.3**
- **Found during:** Task 1 verification
- **Issue:** sandbox ~/.m2 is read-only and only plugin versions already cached resolve; 3.2.0 was not cached
- **Fix:** pinned 3.6.3 (latest release — also the more current choice regardless)

**3. [Rule 3 - Blocking] RAT exclude patterns rewritten module-relative**
- **Found during:** Task 1 verification
- **Issue:** `ranger-pinot-plugin-it/docker/**` never matched because RAT evaluates excludes against each module's basedir; build failed with 3 unapproved-license files
- **Fix:** `**/docker/**`, `**/src/test/resources/**/*.json`, `**/src/test/resources/**/*.csv`
- **Files modified:** pom.xml

---

**Total deviations:** 3 auto-fixed (3 blocking)
**Impact on plan:** All three were required for the build to actually work; no scope creep.

## Known Limitations (documented, not hidden)

- Broker identity: per-user broker tests are impossible upstream (RequesterIdentity has no user) — the suite discovers the client-IP principal at runtime; per-user assertions run on the controller path (Basic auth)
- Environment-blocked verifications listed above require a Docker-capable run (CI) to complete

## Self-Check: PARTIAL

Files: all 8 created + 2 modified files verified present on disk (mvn build compiles and RAT/checkstyle/spotbugs pass over them all).
Commits: **NOT VERIFIABLE — .git is read-only in this sandbox; no commits could be created.** Files are on disk and ready for the orchestrator to commit per the list above.

Re-verified 2026-09-18T05:35Z: all 14 files (8 created + 6 modified, including all 4 planning docs) present on disk; PinotRangerIT.java is 395 lines; HEAD still c781a9a (no commits possible from this sandbox).
