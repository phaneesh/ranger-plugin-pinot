# Requirements: Ranger Plugin for Apache Pinot

**Defined:** 2026-09-17
**Core Value:** A Ranger administrator can define one set of table-level access policies (with tag policies, row filters, and column masks) in Ranger Admin, and have them enforced consistently on both Pinot's broker (queries) and controller (admin API) — with audit trails, fail-closed behavior on policy-fetch failure, and zero Pinot-specific policy authoring quirks relative to how Hive/Kafka admins already work with Ranger.

## v1 Requirements

### Foundation

- [ ] **FOUND-01**: Repo is a multi-module Maven project with `ranger-pinot-plugin` (impl) and `ranger-pinot-plugin-shim` (thin shim) modules, matching Ranger's shim/impl split naming convention
- [ ] **FOUND-02**: Root pom declares JDK 17 build target and shared dependency/version management for `org.apache.ranger:*` published artifacts
- [ ] **FOUND-03**: `ranger-servicedef-pinot.json` registers a `pinot` service type in Ranger Admin with `table` (and `cluster`-scope) resource hierarchy, accessTypes derived from Pinot's `Actions` constants, `rowFilterDef`, `dataMaskDef`, and `configs[]` for controller URL/auth
- [ ] **FOUND-04**: `RangerServicePinot` (extends `RangerBaseService`) implements `validateConfig()` (Test Connection hits Pinot controller health/tables endpoint), `lookupResource()` (table-name autocomplete via `GET /tables`), and `getDefaultRangerPolicies()`

### Broker Enforcement

- [x] **BROKER-01**: Broker `AccessControlFactory`/`AccessControl` implementation loads and evaluates Ranger policies via `RangerBasePlugin` (policy-type ACCESS)
- [x] **BROKER-02**: Per-table ACL enforced on broker queries via `authorize(RequesterIdentity, Set<String> tables)` -> `TableAuthorizationResult`
- [x] **BROKER-03**: Access is denied (fail-closed) when the policy engine returns a null result (policy fetch never succeeded and no cache available) — matches Hive's `result == null → deny` behavior
- [x] **BROKER-04**: Every access decision (allow and deny) emits an audit event to whichever Ranger audit destinations are configured (log4j/solr/hdfs/db/audit-server), via `RangerDefaultAuditHandler` or a thin `RangerPinotAuditHandler` subclass
- [x] **BROKER-05**: Policy refresh/polling reflected without a restart -- verified via `RangerBasePlugin.setPolicies(...)` swap in unit tests (live Ranger-Admin-poll-interval wiring itself deferred to Phase 5's real integration harness)

### Row Filtering & Column Masking

- [ ] **MASK-01**: Row-level filter policies are applied to broker queries via the broker's `getRowColFilters` hook, calling `RangerBasePlugin.evalRowFilterPolicies`
- [ ] **MASK-02**: Column-masking policies are applied via the same `getRowColFilters` hook, calling `RangerBasePlugin.evalDataMaskPolicies`
- [ ] **MASK-03**: Ranger Admin UI can author row-filter and data-mask policies for the `pinot` service type (requires `rowFilterDef`/`dataMaskDef` in the service-def, from FOUND-03)

### Controller (Admin API) Enforcement

- [ ] **ADMIN-01**: Controller `AccessControlFactory`/`AccessControl` implementation enforces table-scoped CRUD (`CREATE`/`READ`/`UPDATE`/`DELETE`) via Ranger policies, called through `AccessControlUtils.validatePermission`
- [ ] **ADMIN-02**: `FineGrainedAccessControl` is implemented for `CLUSTER`/`TABLE` target types, mapping Pinot's ~50 `Actions` constants (CreateTable, DeleteTable, RebalanceTable, UploadSegment, Query, etc.) to Ranger `accessTypes`
- [x] **ADMIN-03**: Resource-building, request-building logic lives in a shared `RangerPinotAuthorizer` class (package `org.apache.ranger.authorization.pinot.authorizer`, not `.broker`/`.controller`) so Phase 4's controller work reuses it rather than duplicating

### Tag-Based Policies

- [ ] **TAG-01**: Tag-based policies (via Atlas tag-sync + `RangerTagEnricher`) are enforced automatically once the `pinot` service is tag-service-enabled in Ranger Admin — verified end-to-end, no Pinot-specific code required beyond correct `RangerBasePlugin` construction

### Classloader Isolation

- [x] **CLASSLOAD-01**: `ranger-pinot-plugin-shim` isolates the impl module's dependency tree from Pinot's own classpath (and vice versa) using `RangerPluginClassLoader`, loading impl jars from a `ranger-pinot-plugin-impl/` directory sitting next to the shim jar -- proven by an automated test that compiles a second, differently-behaving class of the identical FQCN into that directory and asserts `RangerPluginClassLoader` resolves it over the ambient classpath version
- [x] **CLASSLOAD-02**: Classloader activation around every delegated SPI call is bracketed correctly (activate before delegating, deactivate in a `finally`) -- uses direct `activate()`/`deactivate()` rather than the `PluginClassLoaderActivator` try-with-resources helper, because that helper class does not exist in the published `ranger-plugin-classloader:2.8.0` jar (only on unreleased Ranger master); matches Ranger's own Kafka shim at this release

### Packaging & Distribution

- [ ] **PKG-01**: A release build produces `ranger-<version>-pinot-plugin.tar.gz` with Ranger's tarball layout: `lib/` (shim + classloader jars), `lib/ranger-pinot-plugin-impl/` (impl jar + all transitive deps as flat jars), `install/`, `conf.templates/{enable,disable,default}/`, `install.properties`, `enable-pinot-plugin.sh`/`disable-pinot-plugin.sh`/`upgrade-pinot-plugin.sh`, `version` file
- [ ] **PKG-02**: Install/enable/disable scripts are adapted for Pinot's property-based config format (not Hadoop `*-site.xml`/JCEKS credential provider flow)
- [ ] **PKG-03**: GitHub Actions publishes the tarball as a GitHub Release asset automatically when a version tag (e.g. `v1.0.0`) is pushed

### CI / Build Quality

- [ ] **CI-01**: GitHub Actions CI workflow builds on JDK 17 via `mvn clean verify` on every push and PR
- [ ] **CI-02**: Checkstyle and Apache RAT (license-header) checks are enforced as hard build failures
- [ ] **CI-03**: SpotBugs runs as a non-blocking check (findings reported, build not failed), matching upstream Ranger's own choice
- [ ] **CI-04**: An integration-test job stands up a real Pinot cluster and Ranger Admin instance (docker-compose or testcontainers) and verifies allow/deny/audit/row-filter/column-mask behavior end-to-end

### Version Compatibility

- [ ] **COMPAT-01**: The plugin builds once and runs unmodified against both Pinot 1.4.x and 1.5.x (no per-version shim needed — verified identical SPI signatures)
- [ ] **COMPAT-02**: CI integration-test matrix runs against both a Pinot 1.4.x and a Pinot 1.5.x target

## v2 Requirements

(None identified yet — flag during phase execution if discovered.)

## Out of Scope

| Feature                                                             | Reason                                                                                                  |
| -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| GRANT/REVOKE SQL → policy sync                                       | Pinot has no SQL grant/revoke DDL, unlike Hive                                                             |
| Hadoop JCEKS credential-provider install flow                        | Pinot doesn't ship a Hadoop classpath by default; property-based config used instead                      |
| Per-segment or per-schema Ranger resource types                      | Pinot's native REST/broker SPI (`TargetType`) only exposes `CLUSTER` and `TABLE`                           |
| Publishing to Maven Central or dist.apache.org                       | Independent (non-ASF) repo; distribution is via GitHub Releases only                                       |

## Traceability

| Requirement  | Phase   | Status  |
| ------------ | ------- | ------- |
| FOUND-01     | Phase 1 | In Progress |
| FOUND-02     | Phase 1 | In Progress |
| FOUND-03     | Phase 1 | In Progress (code done, live Ranger Admin verification pending) |
| FOUND-04     | Phase 1 | In Progress (code done, live Ranger Admin verification pending) |
| CLASSLOAD-01 | Phase 1 | In Progress (code done, no automated isolation test yet) |
| CLASSLOAD-02 | Phase 1 | In Progress (direct activate/deactivate used instead of PluginClassLoaderActivator — not published in ranger-plugin-classloader:2.8.0) |
| CI-01        | Phase 1 | In Progress (passes locally under JDK 17, not yet run on GitHub) |
| CI-02        | Phase 1 | In Progress (passes locally under JDK 17, not yet run on GitHub) |
| CI-03        | Phase 1 | In Progress (passes locally under JDK 17, not yet run on GitHub) |
| BROKER-01    | Phase 2 | Pending |
| BROKER-02    | Phase 2 | Pending |
| BROKER-03    | Phase 2 | Pending |
| BROKER-04    | Phase 2 | Pending |
| BROKER-05    | Phase 2 | Pending |
| ADMIN-03     | Phase 2 | Pending |
| MASK-01      | Phase 3 | Pending |
| MASK-02      | Phase 3 | Pending |
| MASK-03      | Phase 3 | Pending |
| ADMIN-01     | Phase 4 | Pending |
| ADMIN-02     | Phase 4 | Pending |
| TAG-01       | Phase 4 | Pending |
| PKG-01       | Phase 5 | Pending |
| PKG-02       | Phase 5 | Pending |
| PKG-03       | Phase 5 | Pending |
| CI-04        | Phase 5 | Pending |
| COMPAT-01    | Phase 5 | Pending |
| COMPAT-02    | Phase 5 | Pending |

**Coverage:**
- v1 requirements: 27 total
- Mapped to phases: 27
- Unmapped: 0 ✓

---
*Requirements defined: 2026-09-17*
*Last updated: 2026-09-17 after initial definition*
