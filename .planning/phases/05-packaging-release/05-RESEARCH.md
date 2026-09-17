# Phase 5: Integration Test Matrix (plan 05-03) - Research

**Researched:** 2026-09-17
**Domain:** Dockerized Ranger Admin 2.8.0 + Pinot 1.4.x/1.5.x integration harness; Maven failsafe gating; GitHub Actions matrix CI
**Confidence:** HIGH (Ranger/Pinot/Maven/GHA facts verified from official repos, images, and docs)

## Summary

The blocker that killed the prior attempt (running `apache/ranger:2.8.0` standalone) is now explained and solved. The `apache/ranger` image family is **not standalone**: the official run recipe (apache/ranger-tools `release/README.md`) requires a `rangernw` docker network with **PostgreSQL** sidecar `apache/ranger-db` (+ optional Solr for audits), with the admin container's hostnames being load-bearing (`ranger-db.rangernw`, `ranger-admin.rangernw`). The prior attempt used MySQL (the image's install properties on Docker Hub for 2.8.0 are Postgres-based; the log4jdbc MySQL URL error came from mixing the old 2.4-era takezoe/ranger-docker MySQL recipe with a newer image) and an unresolvable `ranger-admin.rangernw` hostname. The fix: pin `apache/ranger:2.8.0` + `apache/ranger-db:2.8.0` (+ `apache/ranger-solr:2.8.0` for audit verification, matching ranger version 2.8.0) on a user-defined network with correct hostnames.

For Pinot, the official `apachepinot/pinot` multi-component docker-compose pattern (ZK + controller + broker + server) from docs.pinot.apache.org works for both 1.4.0 (`apachepinot/pinot:1.4.0`) and 1.5.1 (`apachepinot/pinot:release-1.5.1` or `1.5.1`). Our 05-01 enable script installs into `$PINOT_HOME` (defaults to image path `/opt/pinot`) — mount the distro tarball, extract, run enable-pinot-plugin.sh inside the container (or a derived image).

Testcontainers has **no Pinot module** (rejected upstream, PR testcontainers-java#3917), so use docker-compose files (v2 syntax, preinstalled on GitHub runners) driven by a JUnit 5 orchestrator, or `GenericContainer` if compose orchestration proves awkward. Either way, Maven failsafe (`*IT.java`, default-skip property + `-Pintegration` profile) keeps `mvn clean verify` fast and Docker-free. GitHub Actions: separate `integration` job with `matrix: pinot: [1.4.0, 1.5.1]`, passing `-Dpinot.version` — but note the plugin build's `pinot.version` property pins the compile-time `provided` deps; the runtime matrix is the image tag, so matrix runs the same artifact against two images.

**Primary recommendation:** docker-compose (Ranger stack + Pinot stack, one compose file per Pinot version driven by env var) + JUnit 5 `*IT` tests (failsafe, `-Pintegration`) + a dedicated `integration` GitHub Actions matrix job. Use `apache/ranger:2.8.0` + `apache/ranger-db:2.8.0` + `apache/ranger-solr:2.8.0` on a `rangernw`-named network with exact hostnames.

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
| --- | --- | --- |
| CI-04 | Integration-test job stands up a real Pinot cluster and Ranger Admin instance (docker-compose or testcontainers) and verifies allow/deny/audit/row-filter behavior end-to-end | Compose recipe for Ranger stack (apache/ranger:2.8.0 + ranger-db + ranger-solr on rangernw network) and Pinot stack (official compose from pinot docs); REST-based provisioning (service-def, service, policies via PublicAPIsv2); broker SQL via POST /query SQL; audit log4j file assertions or Solr query |
| COMPAT-01 | Plugin builds once, runs unmodified against both Pinot 1.4.x and 1.5.x | Same artifact (distro tarball) installed into both apachepinot/pinot:1.4.0 and :1.5.1 containers; verified image tags exist on Docker Hub |
| COMPAT-02 | CI integration-test matrix runs against both a Pinot 1.4.x and a 1.5.x target | GitHub Actions matrix strategy (pinot: [1.4.0, 1.5.1]) over the same build + IT job |
</phase_requirements>

<user_constraints>
## User Constraints (from ROADMAP / REQUIREMENTS / PROJECT.md)

### Locked (project constraints)
- Reference fidelity to apache/ranger conventions (PROJECT.md Constraints) — harness naming/structure should mirror how Ranger itself tests (`dev-support/ranger-docker` style compose, `install.properties`-driven enable scripts)
- Pinot version support: must work against BOTH 1.4.x and 1.5.x — the IT matrix must run both, not one
- Build: multi-module Maven, JDK 17 build, `mvn clean verify` MUST stay green and fast **without Docker** (CI-01..03 rely on the fast job; the CI-04 job is separate)
- Dependency isolation: impl jars only via `lib/ranger-pinot-plugin-impl/` next to the shim jar — the IT harness must install via the real 05-01 distro tarball + `enable-pinot-plugin.sh`, not by dumping impl jars onto the Pinot classpath
- Deferred verification items this harness must also serve: FOUND-03/04 (RangerServicePinot validateConfig/lookupResource against real controller), BROKER-05 (live poll path), Phase 4 live 403 checks, Phase 3 row-filter live check — design one harness serving all

### Known blocker to design around
- Broker identity: Pinot `RequesterIdentity` has no user concept; plugin currently derives user from `getClientIp()`. Integration tests must exercise broker-side auth via whatever identity channel the config enables, and controller-side auth via Basic-auth headers (`RangerPinotAccessControl.extractUser` decodes Basic auth). Policies granting to the test user names work on the controller path today; broker-path per-user identity remains the documented stand-in (client IP as principal) — grant policies accordingly (e.g. user name = the observed clientIp, or a wildcard user) or route broker assertions through the enable-script's identity seam.

### Out of scope
- Atlas tag-sync live infrastructure (tag policies were unit-verified with RangerFileBasedTagRetriever in Phase 4; live Atlas is not part of CI-04)
- Column masking (MASK-02 infeasible — no SPI channel)
</user_constraints>

## Standard Stack

### Core (harness additions)

| Component | Version | Purpose | Why |
| --- | --- | --- | --- |
| `apache/ranger` image | 2.8.0 | Ranger Admin for policy authoring + plugin policy download | Matches plugin's `ranger.version=2.8.0`; published on Docker Hub (multi-arch), verified tags 2.4.0–2.9.0 |
| `apache/ranger-db` image | 2.8.0 | PostgreSQL 13.16 sidecar, pre-init script creates ranger DB + user | Required by ranger image's install properties (`DB_FLAVOR=POSTGRES`, `db_host=ranger-db`) |
| `apache/ranger-solr` image | 2.8.0 | Solr 8.11.3 with `ranger_audits` configset preloaded | Admin's `audit_store=solr` config points at `http://ranger-solr:8983/solr/ranger_audits`; also lets ITs query audit events via Solr REST |
| `apachepinot/pinot` image | 1.4.0 and 1.5.1 | Pinot cluster (ZK+controller+broker+server) | Official image; both tags verified on Docker Hub. Prefer `1.5.1` plain tag / `1.4.0` |
| `zookeeper` image | 3.9.x | Pinot's ZK | Per official Pinot compose |
| maven-failsafe-plugin | 3.x (project uses 3.2.5 surefire; failsafe same line) | Runs `*IT.java` in `integration-test` phase, `verify` goal gates build | Standard Maven IT pattern; `-DskipITs` default + `-Pintegration` |
| JUnit 5 | 5.10.2 (already used) | IT test framework | Already in dependencyManagement |
| java-http-client (JDK built-in) / okhttp | — | REST calls: Ranger provisioning, Pinot controller REST, broker SQL | JDK 17 `java.net.http.HttpClient` suffices; no new dependency needed |

### Alternatives Considered

| Instead of | Could Use | Tradeoff |
| --- | --- | --- |
| docker-compose orchestration | Testcontainers (`GenericContainer` + `Network`) | No official Pinot module (PR #3917 rejected; maintainers: "GenericContainer works fine"); testcontainers gives Ryuk cleanup + JUnit lifecycle integration but requires hand-modeling 5-7 containers + wait strategies. Compose is closer to the documented Pinot recipe and reuses env-var parametrization per Pinot version. Recommendation: **compose**, keep testcontainers out (one less dependency) |
| apache/ranger dev-support compose (apache/ranger repo, `dev-support/ranger-docker`) | builds Ranger from source tarballs, needs download-archives.sh + mvn build; heavyweight (README warns "up to an hour" first build) | ranger-tools release images are prebuilt, version-tagged — strictly better for IT |
| ranger-solr container | audit to log4j only (`XAAUDIT.LOG4J.ENABLE=true`) | Skipping Solr simplifies the stack, but then audit verification is limited to grepping broker/controller log files. Solr container is cheap and enables exact audit assertions; recommendation: include it, but degrade gracefully if only log-based audit is wired |

### Version verification notes
- `apache/ranger:2.8.0` pushed 2026-03-06, amd64+arm64, ~1.04GB — verified via Docker Hub registry API 2026-09-17
- `apachepinot/pinot:release-1.5.1` pushed 2026-07-01, amd64+arm64 — verified via Docker Hub registry API
- ranger-tools `download-ranger.sh` defaults `RANGER_VERSION=2.8.0` — image build is exactly the 2.8.0 admin tarball from downloads.apache.org (gpg-verified at build time)
- Pinot docs (docker install page, current): images built with JDK 21 by default; JDK 11/17 variants no longer published from 1.5 on. For 1.4.0, `apachepinot/pinot:1.4.0` is JDK 21? — actually 1.4.0 was built with JDK 11+17+21 variants; the plain `1.4.0` tag is JDK 21-era. **Plugin is compiled for JDK 17 build / runtime JDK 11+ (classlib ≤ 11 target)**, so it runs on either image JVM. Confidence MEDIUM on exact per-tag JDK; irrelevant as long as class files are ≤ the runtime JVM — which they are (release 17? see Pitfall 6).

## Architecture Patterns

### Harness layout (recommended)

```
ranger-pinot-plugin-it/                    # NEW sibling Maven module (or src/it in distro module)
├── pom.xml                                # failsafe config, -Pintegration profile, skipITs=true default
├── docker/
│   ├── docker-compose.ranger.yml          # ranger-db, ranger-solr, ranger-admin (rangernw network)
│   ├── docker-compose.pinot.yml           # pinot-zk, controller, broker, server (parametrized PINOT_IMAGE)
│   └── pinot/Dockerfile.plugin            # FROM apachepinot/pinot:${PINOT_VERSION}; COPY distro tarball; RUN enable script
├── src/test/java/.../PinotRangerIT.java   # orchestrates: start compose, provision via REST, assert
└── src/test/resources/...                # table config, schema, policy JSON fixtures
```

A dedicated `ranger-pinot-plugin-it` module is cleaner than stuffing ITs into the impl module: the impl module's unit tests (real-policy-engine, no Docker) stay fast; the IT module depends on the distro tarball artifact (`ranger-pinot-plugin-distro`'s attached assembly) so the tested artifact is exactly what ships. If a new module is undesirable, `src/it/java` in distro module with includes also works — but distro module currently has no test infra; new module is the smaller conceptual footprint.

### Pattern 1: Ranger stack compose (verified against ranger-tools release README)

```yaml
# docker-compose.ranger.yml — HIGH confidence, mirrors apache/ranger-tools release/README.md exactly
services:
  ranger-db:
    image: apache/ranger-db:2.8.0
    hostname: ranger-db.rangernw
    environment:
      POSTGRES_PASSWORD: rangerR0cks!
      RANGER_DB_USER: rangeradmin
      RANGER_DB_PASSWORD: rangerR0cks!
    networks: [rangernw]
    healthcheck:
      test: ["CMD-SHELL", "su -c 'pg_isready -q' postgres"]
      interval: 10s
      timeout: 2s
      retries: 30

  ranger-solr:
    image: apache/ranger-solr:2.8.0
    hostname: ranger-solr.rangernw
    command: solr-precreate ranger_audits /opt/solr/server/solr/configsets/ranger_audits/
    networks: [rangernw]
    ports: ["8983:8983"]

  ranger-admin:
    image: apache/ranger:2.8.0
    hostname: ranger-admin.rangernw
    environment:
      POSTGRES_PASSWORD: rangerR0cks!
      RANGER_DB_USER: rangeradmin
      RANGER_DB_PASSWORD: rangerR0cks!
    networks: [rangernw]
    ports: ["6080:6080"]
    depends_on:
      ranger-db: { condition: service_healthy }
      ranger-solr: { condition: service_started }

networks:
  rangernw:
    name: rangernw
```

Key facts (HIGH confidence, read from ranger-tools release/Dockerfile.ranger + scripts/ranger.sh):
- First boot runs `setup.sh` (creates DB schema), waits ~30s, then runs `create-ranger-services.py` — total cold-start ~2-5 minutes. The `.setupDone` marker makes restarts fast. For CI, treat first-boot wait as the expected cost; poll `http://localhost:6080` until 200.
- Admin creds: **admin / rangerR0cks!** (README-stated; also the DB password env pattern sets `rangerAdmin_password`).
- `audit_store=solr` baked into the image's install properties → audits land in `ranger_audits` Solr collection.
- The hostname `ranger-admin.rangernw` is baked into configs (`policymgr_external_url=http://ranger-admin:6080`); the network name `rangernw` must exist and the service hostname must resolve. RANGER-5514 in ranger-tools replaced the *hardcoded* network/hostname requirement with configurable values on master — but the published 2.8.0 images predate/ship with the baked hostnames, so keep the exact names above.

### Pattern 2: Pinot stack + plugin install

```yaml
# docker-compose.pinot.yml — parametrized; based on docs.pinot.apache.org docker install page
services:
  pinot-zookeeper:
    image: zookeeper:3.9.5
    networks: [pinot]
    ports: ["2181:2181"]
    healthcheck: { test: ["CMD", "zkServer.sh", "status"], interval: 30s, retries: 5 }

  pinot-controller:
    image: ranger-pinot-plugin-it:${PINOT_VERSION}   # derived image with plugin pre-installed
    command: StartController -zkAddress pinot-zookeeper:2181
    networks: [pinot, rangernw]   # second network: plugin must reach ranger-admin:6080
    ports: ["9000:9000"]
    depends_on: { pinot-zookeeper: { condition: service_healthy } }
    environment:
      JAVA_OPTS: "-Xms512M -Xmx1G"
  pinot-broker:
    image: ranger-pinot-plugin-it:${PINOT_VERSION}
    command: StartBroker -zkAddress pinot-zookeeper:2181
    networks: [pinot, rangernw]
    ports: ["8099:8099"]
    depends_on: { pinot-controller: { condition: service_healthy } }
    environment:
      JAVA_OPTS: "-Xms512M -Xmx1G"
  pinot-server:
    image: ranger-pinot-plugin-it:${PINOT_VERSION}
    command: StartServer -zkAddress pinot-zookeeper:2181
    networks: [pinot]
    ports: ["8098:8098"]
    depends_on: { pinot-broker: { condition: service_healthy } }
    environment:
      JAVA_OPTS: "-Xms512M -Xmx1G"
networks:
  pinot: {}
```

```dockerfile
# pinot/Dockerfile.plugin — derived image: real distro tarball + real 05-01 enable script
ARG PINOT_VERSION
FROM apachepinot/pinot:${PINOT_VERSION}
COPY target/ranger-*-pinot-plugin.tar.gz /tmp/plugin.tar.gz
RUN mkdir -p /opt/ranger && \
    tar -xzf /tmp/plugin.tar.gz -C /opt/ranger --strip-components=1 && \
    printf 'COMPONENT_INSTALL_DIR_NAME=/opt/pinot\nPOLICY_MGR_URL=http://ranger-admin.rangernw:6080\nREPOSITORY_NAME=pinotdev\nPOLICY_CACHE_FILE_PATH=/tmp/ranger-policycache\nPINOT_ENABLE_ROW_COLUMN_LEVEL_AUTH=true\nXAAUDIT.LOG4J.ENABLE=true\nXAAUDIT.SOLR.ENABLE=true\nXAAUDIT.SOLR.URL=http://ranger-solr.rangernw:8983/solr/ranger_audits\nXAAUDIT.SOLR.FILE_SPOOL_DIR=/tmp/audit-spool\n' \
      >> /opt/ranger/scripts/install.properties && \
    /opt/ranger/enable-pinot-plugin.sh
```

Critical detail: **broker and controller containers must be on the `rangernw` network too** (the plugin inside them polls `http://ranger-admin:6080` / `ranger-admin.rangernw`). Two options: (a) attach containers to both networks (compose supports multiple networks per service) — simplest; (b) one flat network for everything. Since hostnames inside ranger image configs use short names (`ranger-db`, `ranger-solr`, `ranger-admin` — note install properties use `ranger-admin` NOT `ranger-admin.rangernw` for `policymgr_external_url`, while the README's docker run uses hostname `ranger-admin.rangernw`; both resolve on the rangernw network via service aliases + hostname), a single flat network named `rangernw` containing everything (Pinot stack + Ranger stack) avoids all DNS questions. **Recommendation: one network, everything in it.** (`pinot-zookeeper` service alias + hostname both work.)

The enable script's known outputs (verified by reading `enable-pinot-plugin.sh` + distro layout):
- jars land in `$PINOT_HOME/lib/` (shim + classloader) + `$PINOT_HOME/lib/ranger-pinot-plugin-impl/` (43 impl jars) — Pinot's `Start*` commands put `lib/*` on the classpath, so the shim loads and `RangerPluginClassLoader` finds its impl dir
- `pinot-broker.conf` gets `pinot.broker.access.control.class=...RangerPinotAccessControlFactory` and `pinot.broker.enable.row.column.level.auth=true`
- `pinot-controller.conf` gets `controller.admin.access.control.factory.class=...RangerPinotAccessControlFactory`
- `ranger-pinot-security.xml` rendered with `POLICY_MGR_URL` + `REPOSITORY_NAME` — this is what wires `ranger.plugin.pinot.service.name=pinotdev` and the policy rest url for the live poll path (BROKER-05)

### Pattern 3: Maven gating (failsafe, default-skip)

```xml
<!-- in the IT module pom (or parent build/pluginManagement) -->
<properties>
  <skipITs>true</skipITs>   <!-- default: mvn clean verify never runs ITs -->
</properties>
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-failsafe-plugin</artifactId>
      <version>3.2.5</version>
      <executions>
        <execution>
          <goals><goal>integration-test</goal><goal>verify</goal></goals>
        </execution>
      </executions>
      <configuration>
        <skipITs>${skipITs}</skipITs>
        <systemPropertyVariables>
          <pinot.baseUrl>http://localhost:9000</pinot.baseUrl>
          <broker.baseUrl>http://localhost:8099</broker.baseUrl>
          <ranger.baseUrl>http://localhost:6080</ranger.baseUrl>
        </systemPropertyVariables>
      </configuration>
    </plugin>
  </plugins>
</build>
<profiles>
  <profile>
    <id>integration</id>
    <properties><skipITs>false</skipITs></properties>
  </profile>
</profiles>
```

- `mvn clean verify` → fast, no Docker, unchanged CI-01..03 behavior (HIGH confidence — failsafe only picks up `*IT.java` classes and `skipITs=true` default skips even those)
- `mvn clean verify -Pintegration` → runs the matrix
- Tests must also self-guard: `Assumptions.assumeTrue(dockerAvailable())` (or a `@Tag("docker")` + junit-platform.properties) so a developer running `-Pintegration` without Docker gets a skip, not a hang — and so accidental invocation can't break the fast path

### Pattern 4: per-version parametrization (COMPAT-02)

Two mechanisms, use both as appropriate:
1. **Compose-level**: `PINOT_IMAGE=apachepinot/pinot:${PINOT_VERSION}` env var → same compose file for both versions (that's how the official compose is written)
2. **Maven-level**: a `pinot.it.version` property (default `1.4.0`) the IT module passes to docker build; the GitHub matrix sets it per job. The plugin *build* itself still compiles once against `pinot.version=1.4.0` (locked decision: byte-identical SPI) — the IT runs the *same artifact* against two runtime images. Do not try to rebuild per version.

### Pattern 5: GitHub Actions wiring

```yaml
# .github/workflows/ci.yml — add a second job (build job unchanged)
  integration:
    needs: build
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        pinot: ["1.4.0", "1.5.1"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v5
        with: { distribution: temurin, java-version: '17', cache: maven }
      - name: Run integration tests
        run: mvn -B clean verify -Pintegration -Dit.pinot.version=${{ matrix.pinot }} --no-transfer-progress
```

- Docker + Compose v2 preinstalled on ubuntu runners (verified: runner-images Ubuntu2404 readme; HIGH confidence)
- Public-repo standard runners: 4 vCPU / 16 GB RAM — enough for ZK+Controller+Broker+Server+Ranger stack IF each JVM is capped (`-Xmx512M`–`1G` per component; the docs' 4–16G defaults WILL OOM). HIGH confidence on the 16GB figure; MEDIUM on exact per-component tuning (first CI run will tell).
- Keep the fast `build` job exactly as is; `integration` depends on it, runs matrix in parallel.

### Anti-Patterns to Avoid
- **Running `apache/ranger:2.8.0` standalone** — no DB, no network aliases, JPA fails (proven failure; see Pitfall 1)
- **MySQL for Ranger Admin on the 2.8.0 images** — the official 2.8.0 image recipe is Postgres-only (`ranger-admin-install-postgres.properties`); the MySQL+`apache/ranger` image pairing is 2.4-era folklore
- **Dumping impl jars onto Pinot's lib/** directly (bypassing the shim/impl-dir structure) — breaks CLASSLOAD-01 and diverges from the shipped artifact; the IT must install via the distro tarball + enable script
- **Rebuilding the plugin per Pinot version** — violates the one-build-two-targets decision; matrix only changes the runtime image tag
- **Pinot QuickStart command** (`QuickStart -type batch`) — single-process, fixed data, can't install a plugin into a controlled multi-component cluster, ports overlap; not suitable for this harness

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
| --- | --- | --- | --- |
| Ranger Admin runtime | Custom Ranger build/deploy in Docker | `apache/ranger:2.8.0` + `apache/ranger-db:2.8.0` + `apache/ranger-solr:2.8.0` images | Official, version-matched, prebuilt; setup.sh + service bootstrap already scripted in image |
| Pinot cluster runtime | Ad-hoc docker run scripts | Official compose pattern from docs.pinot.apache.org | Maintained upstream; healthchecks and dependency ordering already correct |
| Maven IT gating | Surefire exec hacks / custom profiles wiring tests into `test` phase | maven-failsafe-plugin + `skipITs` property + `-Pintegration` | The documented Maven pattern; guarantees fast-path isolation |
| IT container lifecycle | Process spawning docker compose from JUnit with hand-rolled cleanup | docker compose CLI invoked from failsafe's `pre-integration-test`/`post-integration-test` via exec-maven-plugin, or testcontainers' compose module (`DockerComposeContainer`) | exec-maven-plugin start/stop is declarative and leak-free; testcontainers adds a dependency for what compose CLI already does |
| Policy provisioning | Manual curl in CI steps | JUnit helper (HttpClient + Basic auth admin:rangerR0cks!) posting service-def/service/policy JSON | Assertions live with the test; CI step stays `mvn verify -Pintegration` |
| Waiting for readiness | Thread.sleep | Poll loops with timeout (e.g. `awaitility` — already a common Ranger test dep — or plain loop with `HttpClient` retry) | Ranger first boot takes 2-5 min variable; sleeps are flaky |

## Common Pitfalls

### Pitfall 1: `apache/ranger:2.8.0` standalone fails (KNOWN — do not retry)
**What went wrong:** prior attempt hit `Driver net.sf.log4jdbc.DriverSpy claims to not accept jdbcUrl jdbc:log4jdbc:mysql://localhost/ranger` and expected a `ranger-admin.rangernw` network.
**Why:** the image expects (a) a reachable Postgres at `ranger-db` hostname and (b) network-resolvable `ranger-admin`/`ranger-admin.rangernw`. Running it standalone gave it `localhost` MySQL from stale configs.
**How to avoid:** exactly the compose from Pattern 1 — `apache/ranger-db:2.8.0` healthy before `ranger-admin` starts, `rangernw` network name, hostname `ranger-admin.rangernw`, all three env vars (`POSTGRES_PASSWORD`, `RANGER_DB_USER`, `RANGER_DB_PASSWORD`) set.
**Warning signs:** JPA driver errors, `ranger-admin.rangernw: Name or service not known` in logs.

### Pitfall 2: Ranger first-boot is slow and idempotent
`ranger.sh` runs `setup.sh` (schema creation + patches) only when `/home/ranger/.setupDone` is absent, then sleeps 30s and runs `create-ranger-services.py`. Cold start 2-5 minutes; **the test must poll, not sleep**. Restart with the same volume is fast, but in CI each run is cold (no volume) unless you cache a post-setup image/volume. Budget the CI job at 10-15 min total.

### Pitfall 3: Pinot image JAVA_OPTS defaults OOM on CI runners
The docs' compose uses `-Xms4G -Xmx4G` broker / `-Xmx16G` server. GitHub standard runners have 16 GB total; the full Ranger stack adds ~3 JVMs more. Override `JAVA_OPTS` per component (`-Xmx512M`–`1G`, G1GC) as in Pattern 2. Locally, Docker v29.8 is available; the same caps keep developer laptops happy.

### Pitfall 4: Plugin must reach Ranger Admin from inside broker/controller containers
`ranger-pinot-security.xml` will point at `http://ranger-admin.rangernw:6080` (or `ranger-admin:6080` — align with what the enable script writes from `POLICY_MGR_URL`). If the broker/controller containers aren't on the same docker network as `ranger-admin`, every policy poll fails → plugin fail-closed → ALL queries denied → tests fail in a way that looks like a policy bug. Single flat network (`rangernw`) for the entire stack is the robust choice. **Also verify which hostname the install.properties template uses** — the rendered `ranger.plugin.pinot.policy.rest.url` value must be resolvable from inside the Pinot containers.

### Pitfall 5: Broker identity stand-in (known design limitation)
Broker-side `deriveUser` returns `requesterIdentity.getClientIp()` — from a container, that's a docker-network IP (e.g. `172.x.x.x`), not a test username. For allow/deny assertions on the broker path: either (a) create policies granting that IP-string as the user (fragile but faithful to current behavior), or (b) drive broker tests through whatever identity the row-level-auth config exposes, or (c) keep per-user broker assertions as controller-path tests (Basic auth works end-to-end there) and treat broker tests as table-level allow/deny via the derived identity. Document which choice the plan makes; do NOT silently assume username-based policies work on the broker path.

### Pitfall 6: Plugin class-file compatibility with image JVMs
Pinot images are JDK 21 (and 1.4.0 also had 11/17 variants historically). The plugin compiles with `maven.compiler.release=17` at build — runs on JDK 17+ JVMs, but NOT on a JDK 11 image variant. Use the plain (JDK 21) image tags for both 1.4.0 and 1.5.1. (`release=17` class files won't load on JDK 11 — 1.4.0's `-java-11` tag would break; plain tag is safe.) Confidence HIGH on release-flag semantics; MEDIUM on 1.4.0 plain-tag JDK (verify with `docker run ... java -version` in Wave 0).

### Pitfall 7: Audit assertions depend on how the enable script wires audit
Distro's `install.properties` defaults `XAAUDIT.LOG4J.ENABLE=true`; Solr audit needs `XAAUDIT.SOLR.ENABLE=true` + reachable `ranger-solr`. Decide the assertion channel up front: log4j file grep inside the broker/controller container (simple, no Solr needed) vs Solr query `http://localhost:8983/solr/ranger_audits/select?q=...` (stronger, central). Recommendation: log4j grep for v1 of the harness (fewer moving parts); Solr audit as a stretch assertion.

### Pitfall 8: surefire might pick up IT classes if misnamed
Failsafe convention: `*IT.java`, `IT*.java`, `*ITCase.java`. Keep IT class names strictly `...IT` and they will never run under surefire's default includes (`*Test.java` etc.). Zero-config separation.

## Code Examples

### Provisioning Ranger via REST (HIGH confidence — from apache/ranger PublicAPIsv2.java source)

```java
// All calls: Basic auth admin:rangerR0cks!, Content-Type: application/json
// 1. Register service-def (idempotent: DELETE existing by name first if present)
//    POST   http://localhost:6080/service/public/v2/api/servicedef
//    body: contents of ranger-servicedef-pinot.json from the repo (ranger-pinot-plugin/src/main/resources/service-defs/)
//    cleanup: DELETE /service/public/v2/api/servicedef/name/pinot?forceDelete=true
// 2. Create service instance
//    POST   http://localhost:6080/service/public/v2/api/service
//    body: {"name":"pinotdev","type":"pinot","configs":{"pinot.controller.url":"http://pinot-controller:9000", ...}}
// 3. Create policies
//    POST   http://localhost:6080/service/public/v2/api/policy
{
  "service": "pinotdev",
  "name": "allow-select-orders",
  "resources": { "table": { "values": ["orders"] } },
  "policyItems": [ { "accesses": [ {"type": "query", "isAllowed": true} ], "users": ["<derived-identity>"] } ]
}
// 4. Row-filter policy
{
  "service": "pinotdev",
  "name": "rls-orders",
  "resources": { "table": { "values": ["orders"] } },
  "rowFilterPolicyItems": [
    { "rowFilterInfo": { "filterExpr": "region = 'west'" },
      "accesses": [ {"type": "query", "isAllowed": true} ], "users": ["<derived-identity>"] }
  ]
}
// 5. Bulk cleanup: DELETE /service/public/v2/api/policies/bulk?serviceName=pinotdev
```

### Querying Pinot broker SQL (works 1.4 + 1.5)

```bash
# SQL over broker REST — same API shape in 1.4.x and 1.5.x
curl -X POST http://localhost:8099/query/sql \
  -H 'Content-Type: application/json' \
  -d '{"sql": "SELECT count(*) FROM orders"}'
```

### Live poll path (BROKER-05 verification hook)
The rendered `ranger-pinot-security.xml` (from the enable script + install.properties) sets `ranger.plugin.pinot.service.name=pinotdev` and the admin URL; `RangerBasePlugin`'s `PolicyRefresher` polls `GET /service/plugins/policies/download/pinotdev?lastKnownVersion=...` (default 30s interval, `ranger.plugin.pinot.policy.pollIntervalMs`). An IT can set the poll interval low (e.g. 5s) via the enable script's rendered config and assert a policy change takes effect without restart. Config keys (HIGH confidence, from RangerBasePlugin/PolicyRefresher source): `ranger.plugin.<service>.service.name`, `ranger.plugin.<service>.policy.rest.url` (hmm — actually policy manager URL lives in the security xml as `ranger.plugin.<svc>.policy.rest.url` or via `policymgr.url`; verify exact key from the rendered template in Wave 0), `ranger.plugin.<service>.policy.pollIntervalMs`, `ranger.plugin.<service>.policy.cache.dir`.

### Creating the test table + data
```bash
# schema + table via controller REST (works on both versions)
curl -X POST http://localhost:9000/schemas -H 'Content-Type: application/json' -d @schema.json
curl -X POST http://localhost:9000/tables -H 'Content-Type: application/json' -d @table.json
# offline table needs a segment: generate via pinot-admin or use a small standalone segment upload
# simplest: batch table + segment built with pinot-admin AddSegment from a small CSV, uploaded via POST /v2/segments
```

## Validation Architecture

### Test Framework
| Property | Value |
| --- | --- |
| Framework | JUnit 5.10.2 + maven-failsafe 3.2.5 (new), existing surefire for unit tests |
| Config file | IT module `pom.xml` (+ optional `junit-platform.properties`) |
| Quick run command | `mvn clean verify` (unchanged — no Docker) |
| Full suite command | `mvn clean verify -Pintegration` (optionally `-Dit.pinot.version=1.5.1`) |

### Phase Requirements → Test Map
| Req ID | Behavior | Test Type | Automated Command | File Exists? |
| --- | --- | --- | --- | --- |
| CI-04 | E2E allow/deny/audit/row-filter against real Ranger + Pinot | integration (docker) | `mvn verify -Pintegration -Dit.pinot.version=1.4.0 -pl ranger-pinot-plugin-it` | ❌ Wave 0 |
| COMPAT-01 | Same artifact on 1.4.x and 1.5.x | integration | same IT, matrix env | ❌ Wave 0 |
| COMPAT-02 | Matrix in CI | CI | `.github/workflows/ci.yml` integration job | ❌ Wave 0 |
| FOUND-03 | Service-def registered in live Admin (validateConfig hits real controller) | integration | same harness: service create + Test Connection (`POST /service/public/v2/api/service/{name}/validateConfig`? — verify exact endpoint; service-def impl is exercised on service save) | ❌ Wave 0 |
| FOUND-04 | lookupResource table autocomplete against live controller | integration | same harness: lookup call from Admin side | ❌ Wave 0 |
| BROKER-05 | Live poll path (policy change → enforcement change, no restart) | integration | policy PUT + wait pollIntervalMs + re-query | ❌ Wave 0 |
| Phase 4 403s | Unauthorized controller REST rejected with 403 | integration | controller REST call with un-granted Basic-auth user | ❌ Wave 0 |
| MASK-01 live | Row filter applied to broker query | integration | row-filter policy + broker SQL count assertion | ❌ Wave 0 |

### What a green run proves per requirement
- **CI-04 green** = the shipped distro tarball, installed by the real enable script into a stock official Pinot image, enforces Ranger policies authored in a real Ranger Admin (allow + deny), emits audit events, and applies row filters — no mocks anywhere in the chain.
- **COMPAT-01/02 green** = the identical tarball artifact does this on both Pinot 1.4.0 and 1.5.1 runtime images.
- **BROKER-05 green** = policy edits in Ranger Admin propagate to a running broker within the poll interval without restart.

### Sampling Rate
- **Per task commit:** `mvn clean verify` (fast, no Docker)
- **Per wave merge / phase gate:** `mvn clean verify -Pintegration` on both matrix versions (locally with Docker, or push and watch CI)

### Wave 0 Gaps
- [ ] `ranger-pinot-plugin-it/` module skeleton (pom, compose files, Dockerfile.plugin)
- [ ] Wave 0 sanity: `docker compose -f docker-compose.ranger.yml up -d` → poll `http://localhost:6080` → login works → POST servicedef succeeds (this alone retires the standalone-ranger failure for good)
- [ ] Wave 0 sanity: `docker compose -f docker-compose.pinot.yml up -d` (1.4.0) + derived plugin image builds and broker starts with Ranger factory class (check logs for policy fetch)
- [ ] `PinotRangerIT.java` + REST provisioning helper
- [ ] GitHub Actions integration job

## Environment Availability

| Dependency | Required By | Available | Version | Fallback |
| --- | --- | --- | --- | --- |
| Docker | Ranger + Pinot stacks | ✓ (local) | v29.8 | — |
| Docker Compose v2 | stack orchestration | ✓ (bundled with Docker 29.x) | v2 | — |
| `apache/ranger:2.8.0` | Ranger Admin | ✓ (Docker Hub, multi-arch) | 2.8.0 | — |
| `apache/ranger-db:2.8.0` | Ranger Postgres | ✓ (Docker Hub) | 2.8.0 | build from ranger-tools Dockerfile.ranger-postgres (postgres:13.16 + init script) |
| `apache/ranger-solr:2.8.0` | audit store | ✓ (Docker Hub) | 2.8.0 | log4j-only audit assertions |
| `apachepinot/pinot:1.4.0` | Pinot matrix | ✓ (Docker Hub) | 1.4.0 | — |
| `apachepinot/pinot:1.5.1` | Pinot matrix | ✓ (Docker Hub, `release-1.5.1` tag) | 1.5.1 | — |
| GitHub Actions ubuntu runner | CI | ✓ (repo not yet pushed — first real run pending) | — | none; watch closely per STATE.md |
| `pg_isready` in ranger-db healthcheck | compose gating | ✓ (inside postgres image) | — | sleep-based ordering |

**Missing dependencies with no fallback:** none for local runs. The un-pushed remote remains a project-level known risk (STATE.md), not a blocker for building the harness.

## Open Questions

1. **Exact `ranger.plugin.pinot.policy.*` config keys rendered by the enable script** — the distro's conf templates (`conf/ranger-pinot-security.xml` + `ranger-pinot-security-changes.cfg`) determine the final property names/values; the planner should read those files (they were delivered in 05-01) before finalizing the BROKER-05 live-poll test, especially the policy-manager URL key and whether `pollIntervalMs` is rendered. LOW risk — files exist in-repo; this is a read, not a discovery.
2. **Broker-path identity for assertions** (see Pitfall 5): plan must pick option (a) IP-as-username policies, (b) table-level only, or (c) controller-only per-user tests. Recommendation: (b)+(c) for v1 with a documented limitation note; revisit if/when upstream Pinot adds user identity to RequesterIdentity.
3. **`validateConfig` REST surface**: whether Ranger Admin exposes a direct "test connection" REST call or whether FOUND-03 is proven by creating the service with a live controller URL and checking `validateConfig` runs on save (the service-def's implClass gets invoked on service create/update). MEDIUM confidence; verify against PublicAPIsv2/ServiceDBStore in Wave 0.
4. **Ranger 2.8.0 image vs `create-ranger-services.py`**: the first-boot script creates some default services; harmless for us, but the IT's service-def POST must be idempotent (delete-by-name first). LOW risk.
5. **Which hostname value to put in `POLICY_MGR_URL`** (`ranger-admin` vs `ranger-admin.rangernw`): both resolve on the rangernw network when compose service name + hostname are set as in Pattern 1; align with whatever install.properties template expects. LOW risk, verify in Wave 0.

## Sources

### Primary (HIGH confidence)
- apache/ranger-tools release README + Dockerfile.ranger + scripts (ranger.sh, ranger-admin-install-postgres.properties, init_postgres.sh, download-ranger.sh) — read directly from apache.googlesource.com/ranger-tools main: exact image recipe, env vars, network, creds (admin/rangerR0cks!), Postgres-only 2.8.0 images
- Docker Hub registry API (hub.docker.com/v2): verified tags — apache/ranger 2.4.0–2.9.0, apache/ranger-db 2.4.0–2.9.0, apache/ranger-solr 2.8.0/2.9.0, apachepinot/pinot release-1.5.1 (2026-07-01), 1.4.0
- docs.pinot.apache.org docker install page: official docker-compose (ZK/controller/broker/server/minion), healthchecks, JDK 21 default / JDK 11-17 tags dropped from 1.5, resource recommendations
- Repo source: `ranger-pinot-plugin-distro/scripts/enable-pinot-plugin.sh`, `install.properties`, `conf/` templates; `ranger-pinot-plugin/src/main/resources/service-defs/ranger-servicedef-pinot.json`; broker/controller `RangerPinotAccessControl` (identity paths)
- apache/ranger source (PublicAPIsv2.java, RangerBasePlugin, PolicyRefresher, RangerAdminRESTClient): REST endpoints, policy-poll config keys, download endpoint (verified on master; classes long-stable in 2.x)

### Secondary (MEDIUM)
- testcontainers-java PR #3917 / discussion #9175: no Pinot module, maintainers recommend GenericContainer
- timveil-startree/pinot-testcontainers-example: real-world ZK+Pinot testcontainers pattern (archived)
- GitHub runner-images readme + github-hosted-runners docs: Docker/Compose/Maven preinstalled, 4vCPU/16GB public-repo standard runners
- maven-failsafe docs: `skipITs` default-skip pattern, `*IT.java` naming

### Tertiary (LOW)
- Pinot 1.4.0 plain-tag JDK version (likely 21-era; verify `java -version` in Wave 0)

## Metadata

**Confidence breakdown:**
- Ranger stack recipe: HIGH — read directly from apache/ranger-tools release scripts/README and verified image tags exist
- Pinot stack recipe: HIGH — official docs compose, both image tags verified
- Maven/CI gating: HIGH — documented Maven/GHA behavior
- Broker identity assertions: MEDIUM — known design limitation, plan must choose an approach
- Audit assertion channel: MEDIUM — log4j grep is certain; Solr audit wiring depends on install.properties rendering (in-repo, verify at plan time)
- Plugin-config key details: MEDIUM — RangerBasePlugin keys verified from source; exact rendered template values in repo to be read at plan time

**Research date:** 2026-09-17
**Valid until:** 2026-10-17 (images are version-tagged; findings stable)
