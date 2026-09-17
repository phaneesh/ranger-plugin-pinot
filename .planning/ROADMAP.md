# Roadmap: Ranger Plugin for Apache Pinot

## Overview

Five phases take the project from an empty repo to a released, Hive-parity Ranger
plugin for Pinot. Phase 1 stands up the multi-module Maven skeleton, the
classloader-isolation shim, the Ranger Admin service-def, and CI — this is the
scaffolding every later phase builds on. Phase 2 wires the broker side of
enforcement (table ACLs, audit, fail-closed policy evaluation) since that's Pinot's
primary query-authorization surface. Phase 3 adds row-filtering and column-masking
on top of the working broker plugin — Pinot's SPI supports this natively via
`getRowColFilters`, which is what makes true Hive-parity achievable. Phase 4 covers
the controller's admin-API authorization surface (table CRUD + cluster-scope
actions) plus verifying tag-based policies, reusing the shared code built in
Phase 2. Phase 5 is packaging and release: the Ranger-style distro tarball, GitHub
Actions CI/release automation, and end-to-end integration tests against real Pinot
1.4.x and 1.5.x clusters.

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (2.1, 2.2): Urgent insertions (marked with INSERTED)

- [ ] **Phase 1: Foundation & Scaffolding** - Multi-module Maven skeleton, classloader shim, service-def, CI
- [ ] **Phase 2: Broker Enforcement** - Table ACL + audit + fail-closed policy evaluation at query time
- [ ] **Phase 3: Row Filtering & Column Masking** - RLS/CLS via the broker's getRowColFilters hook
- [ ] **Phase 4: Controller (Admin API) Enforcement** - Table CRUD + cluster actions + tag-policy verification
- [ ] **Phase 5: Packaging & Release** - Distro tarball, GitHub Actions release automation, integration tests

## Phase Details

### Phase 1: Foundation & Scaffolding
**Goal**: A buildable, CI-passing multi-module Maven repo with the Ranger-style shim/impl split, registered as a service type in Ranger Admin (even with no-op enforcement yet).
**Depends on**: Nothing (first phase)
**Requirements**: FOUND-01, FOUND-02, FOUND-03, FOUND-04, CLASSLOAD-01, CLASSLOAD-02, CI-01, CI-02, CI-03
**Success Criteria** (what must be TRUE):
  1. `mvn clean verify` succeeds on a fresh checkout, building both `ranger-pinot-plugin` and `ranger-pinot-plugin-shim` modules
  2. Ranger Admin can register a `pinot` service instance using `ranger-servicedef-pinot.json` — Test Connection and table-name autocomplete both work against a real Pinot controller
  3. `ranger-pinot-plugin-shim`'s classloader correctly isolates the impl module's dependencies from a host classpath (verified with a unit/integration test that simulates classpath conflicts)
  4. GitHub Actions CI runs checkstyle + Apache RAT + SpotBugs on every push/PR, with checkstyle/RAT failures blocking merge and SpotBugs findings surfaced but non-blocking
**Plans**: TBD

Plans:
- [ ] 01-01: Maven project skeleton (parent pom, module layout, dependency management)
- [ ] 01-02: Classloader shim (`RangerPluginClassLoader` wiring, `PluginClassLoaderActivator` pattern)
- [ ] 01-03: Service-def + `RangerServicePinot` (test connection, lookup, default policies)
- [ ] 01-04: GitHub Actions CI (checkstyle, RAT, spotbugs, JDK 17 build)

### Phase 2: Broker Enforcement
**Goal**: Pinot's broker enforces Ranger table-level ACL policies on every query, with audit logging and fail-closed behavior.
**Depends on**: Phase 1
**Requirements**: BROKER-01, BROKER-02, BROKER-03, BROKER-04, BROKER-05, ADMIN-03
**Success Criteria** (what must be TRUE):
  1. A query against a table the user is not authorized for is rejected by the broker
  2. A query against an authorized table succeeds and produces exactly one audit event (allow), a denied query produces exactly one audit event (deny)
  3. If Ranger Admin is unreachable and no local policy cache exists, all queries are denied (fail-closed), not allowed
  4. Policy changes made in Ranger Admin are reflected in broker enforcement within the configured poll interval, without a broker restart
**Plans**: TBD

Plans:
- [ ] 02-01: `RangerPinotAuthorizer` core (resource/request building, `RangerBasePlugin` wiring)
- [ ] 02-02: Broker `AccessControlFactory`/`AccessControl` implementation + audit handler
- [ ] 02-03: Fail-closed + policy-refresh verification tests

### Phase 3: Row Filtering & Column Masking
**Goal**: Ranger row-filter and column-mask policies defined in Admin are enforced on Pinot broker queries.
**Depends on**: Phase 2
**Requirements**: MASK-01, MASK-02, MASK-03
**Success Criteria** (what must be TRUE):
  1. A row-filter policy on a table causes queries against that table to only see rows matching the filter
  2. A column-mask policy on a table causes the masked column's values to be transformed/hidden in query results
  3. Ranger Admin's policy-authoring UI accepts row-filter and data-mask policy definitions for the `pinot` service type without error
**Plans**: TBD

Plans:
- [ ] 03-01: Service-def `rowFilterDef`/`dataMaskDef` + `getRowColFilters` implementation
- [ ] 03-02: End-to-end row-filter and column-mask verification tests

### Phase 4: Controller (Admin API) Enforcement
**Goal**: Pinot's controller enforces Ranger policies on admin REST API calls (table CRUD and cluster-scope actions), and tag-based policies are verified working across both broker and controller.
**Depends on**: Phase 2
**Requirements**: ADMIN-01, ADMIN-02, TAG-01
**Success Criteria** (what must be TRUE):
  1. An unauthorized user's attempt to create/update/delete a table via the controller REST API is rejected with 403
  2. Cluster-scope admin actions (e.g. rebalance, instance management) are gated by their mapped Ranger accessType
  3. A tag-based policy (via Atlas tag-sync) is enforced identically on both broker queries and controller admin calls, with no Pinot-specific code beyond standard `RangerBasePlugin` construction
**Plans**: TBD

Plans:
- [ ] 04-01: Controller `AccessControlFactory`/`AccessControl` + `FineGrainedAccessControl` implementation
- [ ] 04-02: Action-to-accessType mapping (Pinot's `Actions` constants → service-def accessTypes)
- [ ] 04-03: Tag-policy end-to-end verification (broker + controller)

### Phase 5: Packaging & Release
**Goal**: A tagged release produces a Ranger-style plugin tarball, published automatically to GitHub Releases, verified against real Pinot 1.4.x and 1.5.x clusters.
**Depends on**: Phase 3, Phase 4
**Requirements**: PKG-01, PKG-02, PKG-03, CI-04, COMPAT-01, COMPAT-02
**Success Criteria** (what must be TRUE):
  1. Pushing a version tag produces a `ranger-<version>-pinot-plugin.tar.gz` GitHub Release asset with the correct internal layout (`lib/ranger-pinot-plugin-impl/`, install scripts, conf templates, version file)
  2. `enable-pinot-plugin.sh` successfully wires the plugin into a real Pinot broker+controller install without requiring Hadoop JCEKS tooling
  3. The integration-test job passes against both a Pinot 1.4.x and a Pinot 1.5.x target, using the same built artifact
**Plans**: TBD

Plans:
- [ ] 05-01: Distro assembly (tarball layout, install/enable/disable/upgrade scripts)
- [ ] 05-02: GitHub Actions release-on-tag workflow
- [ ] 05-03: Integration test matrix (Pinot 1.4.x + 1.5.x via docker-compose/testcontainers)

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase                              | Plans Complete | Status      | Completed |
| ----------------------------------- | --------------- | ----------- | --------- |
| 1. Foundation & Scaffolding          | 0/4             | Not started | -         |
| 2. Broker Enforcement                | 0/3             | Not started | -         |
| 3. Row Filtering & Column Masking     | 0/2             | Not started | -         |
| 4. Controller (Admin API) Enforcement | 0/3             | Not started | -         |
| 5. Packaging & Release               | 0/3             | Not started | -         |
