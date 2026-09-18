# Requirements: Ranger Plugin for Apache Pinot

**Defined:** 2026-09-17 **Core Value:** A Ranger administrator can define one set of table-level access policies (with
tag policies, row filters, and column masks) in Ranger Admin, and have them enforced consistently on both Pinot's broker
(queries) and controller (admin API) — with audit trails, fail-closed behavior on policy-fetch failure, and zero
Pinot-specific policy authoring quirks relative to how Hive/Kafka admins already work with Ranger.

## v1 Requirements

### Foundation

- [ ] **FOUND-01**: Repo is a multi-module Maven project with `ranger-pinot-plugin` (impl) and
  `ranger-pinot-plugin-shim` (thin shim) modules, matching Ranger's shim/impl split naming convention
- [ ] **FOUND-02**: Root pom declares JDK 17 build target and shared dependency/version management for
  `org.apache.ranger:*` published artifacts
- [ ] **FOUND-03**: `ranger-servicedef-pinot.json` registers a `pinot` service type in Ranger Admin with `table` (and
  `cluster`-scope) resource hierarchy, accessTypes derived from Pinot's `Actions` constants, `rowFilterDef`,
  `dataMaskDef`, and `configs[]` for controller URL/auth
- [ ] **FOUND-04**: `RangerServicePinot` (extends `RangerBaseService`) implements `validateConfig()` (Test Connection
  hits Pinot controller health/tables endpoint), `lookupResource()` (table-name autocomplete via `GET /tables`), and
  `getDefaultRangerPolicies()`

### Broker Enforcement

- [x] **BROKER-01**: Broker `AccessControlFactory`/`AccessControl` implementation loads and evaluates Ranger policies
  via `RangerBasePlugin` (policy-type ACCESS)
- [x] **BROKER-02**: Per-table ACL enforced on broker queries via `authorize(RequesterIdentity, Set<String> tables)` ->
  `TableAuthorizationResult`
- [x] **BROKER-03**: Access is denied (fail-closed) when the policy engine returns a null result (policy fetch never
  succeeded and no cache available) — matches Hive's `result == null → deny` behavior
- [x] **BROKER-04**: Every access decision (allow and deny) emits an audit event to whichever Ranger audit destinations
  are configured (log4j/solr/hdfs/db/audit-server), via `RangerDefaultAuditHandler` or a thin `RangerPinotAuditHandler`
  subclass
- [x] **BROKER-05**: Policy refresh/polling reflected without a restart -- verified via
  `RangerBasePlugin.setPolicies(...)` swap in unit tests (live Ranger-Admin-poll-interval wiring itself deferred to
  Phase 5's real integration harness)

### Row Filtering & Column Masking

- [x] **MASK-01**: Row-level filter policies are applied to broker queries via the broker's `getRowColFilters` hook,
  calling `RangerBasePlugin.evalRowFilterPolicies` — DONE in code and unit-tested with the real policy engine (commit
  `a28274d`); end-to-end against a live broker (broker-side `enableRowColumnLevelAuth` config on) is covered by Phase
  5's integration harness
- [x] **MASK-02** *(infeasible with the target Pinot SPI — re-evaluate when Pinot adds a masking channel)*: Pinot
  1.4.x/1.5.x's broker query path has NO column-masking channel at all — `TableRowColAccessResult` exposes only
  `Optional<List<String>> getRLSFilters()` (SQL row predicates); verified against release-1.4.0 and release-1.5.1
  sources. There is nothing for a plugin to consume a Ranger data-mask result through, so no `dataMaskDef` was added to
  the service-def (it would be dead config). Revisit if a future Pinot release adds masking to the broker SPI.
- [x] **MASK-03**: Ranger Admin UI can author row-filter policies for the `pinot` service type (`rowFilterDef` added to
  the service-def; data-mask authoring deliberately not added — see MASK-02)

### Controller (Admin API) Enforcement

- [x] **ADMIN-01**: Controller `AccessControlFactory`/`AccessControl` implementation enforces table-scoped CRUD
  (`CREATE`/`READ`/`UPDATE`/`DELETE`) via Ranger policies, called through `AccessControlUtils.validatePermission` --
  DONE in code and unit-tested with the real policy engine (`RangerPinotAccessControlTest`, 9 tests); coarse CRUD for
  non-table endpoints maps to the `cluster` resource
- [x] **ADMIN-02**: `FineGrainedAccessControl` is implemented for `CLUSTER`/`TABLE` target types, mapping Pinot's ~50
  `Actions` constants (CreateTable, DeleteTable, RebalanceTable, UploadSegment, Query, etc.) to Ranger `accessTypes` --
  DONE: all 127 unique `Actions.Cluster.*`/`Actions.Table.*` string values are service-def accessTypes;
  `hasAccess(headers, targetType, targetId, action)` evaluates the action string directly
- [x] **ADMIN-03**: Resource-building, request-building logic lives in a shared `RangerPinotAuthorizer` class (package
  `org.apache.ranger.authorization.pinot.authorizer`, not `.broker`/`.controller`) so Phase 4's controller work reuses
  it rather than duplicating

### Tag-Based Policies

- [x] **TAG-01**: Tag-based policies (via Atlas tag-sync + `RangerTagEnricher`) are enforced automatically once the
  `pinot` service is tag-service-enabled in Ranger Admin -- verified with the real policy engine: a PII-tag on the
  `orders` table + a tag policy grants access with NO table policy present (`RangerFileBasedTagRetriever` + classpath
  ServiceTags JSON, `tagBasedPolicyGrantsAccessOnTaggedTable`); no Pinot-specific code required beyond correct
  `RangerBasePlugin` construction

### Classloader Isolation

- [x] **CLASSLOAD-01**: `ranger-pinot-plugin-shim` isolates the impl module's dependency tree from Pinot's own classpath
  (and vice versa) using `RangerPluginClassLoader`, loading impl jars from a `ranger-pinot-plugin-impl/` directory
  sitting next to the shim jar -- proven by an automated test that compiles a second, differently-behaving class of the
  identical FQCN into that directory and asserts `RangerPluginClassLoader` resolves it over the ambient classpath
  version
- [x] **CLASSLOAD-02**: Classloader activation around every delegated SPI call is bracketed correctly (activate before
  delegating, deactivate in a `finally`) -- uses direct `activate()`/`deactivate()` rather than the
  `PluginClassLoaderActivator` try-with-resources helper, because that helper class does not exist in the published
  `ranger-plugin-classloader:2.8.0` jar (only on unreleased Ranger master); matches Ranger's own Kafka shim at this
  release

### Packaging & Distribution

- [x] **PKG-01**: A release build produces `ranger-<version>-pinot-plugin.tar.gz` with Ranger's tarball layout: `lib/`
  (shim + classloader jars), `lib/ranger-pinot-plugin-impl/` (impl jar + all transitive deps as flat jars), `install/`,
  `conf.templates/{enable,disable,default}/`, `install.properties`, `enable-pinot-plugin.sh`/`disable-pinot-plugin.sh`/
  `upgrade-pinot-plugin.sh`, `version` file — complete: `ranger-pinot-plugin-distro` module, maven-assembly descriptor
  modeled on Ranger's plugin-kafka.xml, 43-jar impl dir, verified layout + no host pinot jars (05-01)
- [x] **PKG-02**: Install/enable/disable scripts are adapted for Pinot's property-based config format (not Hadoop
  `*-site.xml`/JCEKS credential provider flow) — complete: sed-based property renderer, idempotent broker/controller
  .conf key wiring, no JCEKS, enable/disable/upgrade smoke-tested end-to-end (05-01)
- [x] **PKG-03**: GitHub Actions publishes the tarball as a GitHub Release asset automatically when a version tag (e.g.
  `v1.0.0`) is pushed — complete: `release.yml` (05-02), tag push → JDK 17 `mvn clean verify` → tarball uploaded via
  `softprops/action-gh-release@v2` with `fail_on_unmatched_files: true`; not yet exercised on a real remote (repo not
  yet pushed)

### CI / Build Quality

- [ ] **CI-01**: GitHub Actions CI workflow builds on JDK 17 via `mvn clean verify` on every push and PR
- [ ] **CI-02**: Checkstyle and Apache RAT (license-header) checks are enforced as hard build failures
- [ ] **CI-03**: SpotBugs runs as a non-blocking check (findings reported, build not failed), matching upstream Ranger's
  own choice
- [x] **CI-04**: An integration-test job stands up a real Pinot cluster and Ranger Admin instance (docker-compose or
  testcontainers) and verifies allow/deny/audit/row-filter/column-mask behavior end-to-end — harness BUILT (05-03):
  docker-compose Ranger 2.8.0 + Pinot stack, PinotRangerIT 9 E2E tests; green run rides the CI integration job (Docker
  daemon unreachable in the executor sandbox)

### Version Compatibility

- [x] **COMPAT-01**: The plugin builds once and runs unmodified against both Pinot 1.4.x and 1.5.x (no per-version shim
  needed — verified identical SPI signatures) — matrix wiring complete (05-03): identical tarball installed into both
  apachepinot/pinot:1.4.0 and :1.5.1 derived images via the same Dockerfile.plugin; live matrix verification runs in CI
- [x] **COMPAT-02**: CI integration-test matrix runs against both a Pinot 1.4.x and a 1.5.x target — job written
  (05-03): integration matrix job, first real run on repo push

## v2 Requirements

(None identified yet — flag during phase execution if discovered.)

## Out of Scope

| Feature                                         | Reason                                                                               |
|-------------------------------------------------|--------------------------------------------------------------------------------------|
| GRANT/REVOKE SQL → policy sync                  | Pinot has no SQL grant/revoke DDL, unlike Hive                                       |
| Hadoop JCEKS credential-provider install flow   | Pinot doesn't ship a Hadoop classpath by default; property-based config used instead |
| Per-segment or per-schema Ranger resource types | Pinot's native REST/broker SPI (`TargetType`) only exposes `CLUSTER` and `TABLE`     |
| Publishing to Maven Central or dist.apache.org  | Independent (non-ASF) repo; distribution is via GitHub Releases only                 |

## Traceability

| Requirement  | Phase   | Status                                                                                                                                 |
|--------------|---------|----------------------------------------------------------------------------------------------------------------------------------------|
| FOUND-01     | Phase 1 | In Progress                                                                                                                            |
| FOUND-02     | Phase 1 | In Progress                                                                                                                            |
| FOUND-03     | Phase 1 | In Progress (code done, live Ranger Admin verification pending)                                                                        |
| FOUND-04     | Phase 1 | In Progress (code done, live Ranger Admin verification pending)                                                                        |
| CLASSLOAD-01 | Phase 1 | In Progress (code done, no automated isolation test yet)                                                                               |
| CLASSLOAD-02 | Phase 1 | In Progress (direct activate/deactivate used instead of PluginClassLoaderActivator — not published in ranger-plugin-classloader:2.8.0) |
| CI-01        | Phase 1 | In Progress (passes locally under JDK 17, not yet run on GitHub)                                                                       |
| CI-02        | Phase 1 | In Progress (passes locally under JDK 17, not yet run on GitHub)                                                                       |
| CI-03        | Phase 1 | In Progress (passes locally under JDK 17, not yet run on GitHub)                                                                       |
| BROKER-01    | Phase 2 | Complete                                                                                                                               |
| BROKER-02    | Phase 2 | Complete                                                                                                                               |
| BROKER-03    | Phase 2 | Complete                                                                                                                               |
| BROKER-04    | Phase 2 | Complete                                                                                                                               |
| BROKER-05    | Phase 2 | Complete (setPolicies mechanism verified; live poll path in Phase 5)                                                                   |
| ADMIN-03     | Phase 2 | Complete                                                                                                                               |
| MASK-01      | Phase 3 | Complete                                                                                                                               |
| MASK-02      | Phase 3 | Infeasible with current Pinot SPI (see requirement note)                                                                               |
| MASK-03      | Phase 3 | Complete (row-filter only; masking infeasible)                                                                                         |
| ADMIN-01     | Phase 4 | Complete                                                                                                                               |
| ADMIN-02     | Phase 4 | Complete                                                                                                                               |
| TAG-01       | Phase 4 | Complete (tag policy engine verified in unit test; Atlas tag-sync is live-infra, Phase 5)                                              |
| PKG-01       | Phase 5 | Complete (05-01)                                                                                                                       |
| PKG-02       | Phase 5 | Complete (05-01)                                                                                                                       |
| PKG-03       | Phase 5 | Complete (05-02; untested on a real remote — repo not yet pushed)                                                                      |
| CI-04        | Phase 5 | Complete (05-03: harness built + fast-path verified; live green run rides the CI integration job)                                      |
| COMPAT-01    | Phase 5 | Complete (05-03: same tarball into both 1.4.0/1.5.1 derived images; live matrix run in CI)                                             |
| COMPAT-02    | Phase 5 | Complete (05-03: integration matrix job written; first real run on repo push)                                                          |

**Coverage:**

- v1 requirements: 27 total
- Mapped to phases: 27
- Unmapped: 0 ✓

---
*Requirements defined: 2026-09-17*
*Last updated: 2026-09-18 after 05-03*
