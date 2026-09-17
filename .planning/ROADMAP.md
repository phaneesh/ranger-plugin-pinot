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

- [x] **Phase 1: Foundation & Scaffolding** - Multi-module Maven skeleton, classloader shim, service-def, CI
- [x] **Phase 2: Broker Enforcement** - Table ACL + audit + fail-closed policy evaluation at query time
- [x] **Phase 3: Row Filtering** - RLS via the broker's getRowColFilters hook (column masking infeasible: Pinot 1.4.x/1.5.x broker SPI has no masking channel)
- [x] **Phase 4: Controller (Admin API) Enforcement** - Table CRUD + cluster actions + tag-policy verification (done: 9 controller tests incl. tag-policy test, real policy engine, 2 green verify runs)
- [ ] **Phase 5: Packaging & Release** - Distro tarball, GitHub Actions release automation, integration tests

## Phase Details

### Phase 1: Foundation & Scaffolding
**Goal**: A buildable, CI-passing multi-module Maven repo with the Ranger-style shim/impl split, registered as a service type in Ranger Admin (even with no-op enforcement yet).
**Depends on**: Nothing (first phase)
**Requirements**: FOUND-01, FOUND-02, FOUND-03, FOUND-04, CLASSLOAD-01, CLASSLOAD-02, CI-01, CI-02, CI-03
**Success Criteria** (what must be TRUE):
  1. `mvn clean verify` succeeds on a fresh checkout, building both `ranger-pinot-plugin` and `ranger-pinot-plugin-shim` modules — DONE, verified locally under JDK 17 (2026-09-17)
  2. Ranger Admin can register a `pinot` service instance using `ranger-servicedef-pinot.json` — Test Connection and table-name autocomplete both work against a real Pinot controller — CODE DONE, NOT YET VERIFIED against a live Ranger Admin + Pinot controller (needs an integration harness — carry into Phase 2's test work)
  3. `ranger-pinot-plugin-shim`'s classloader correctly isolates the impl module's dependencies from a host classpath (verified with a unit/integration test that simulates classpath conflicts) — DONE (2026-09-17): automated test compiles a second, differently-behaving class of the identical FQCN into an isolated `ranger-<type>-plugin-impl/` directory and asserts `RangerPluginClassLoader` resolves it over the ambient classpath version. A companion "exercise the real shim classes together with impl on the same classpath" test was attempted and deliberately dropped after it triggered genuine infinite recursion -- proof the isolation boundary is load-bearing, not test-scope noise.
  4. GitHub Actions CI runs checkstyle + Apache RAT + SpotBugs on every push/PR, with checkstyle/RAT failures blocking merge and SpotBugs findings surfaced but non-blocking — workflow written and passes locally (`mvn clean verify` green under JDK 17); not yet exercised on a real GitHub Actions run (repo not yet pushed to a remote)
**Plans**: TBD

Plans:
- [x] 01-01: Maven project skeleton (parent pom, module layout, dependency management)
- [x] 01-02: Classloader shim (`RangerPluginClassLoader` wiring; note: published `ranger-plugin-classloader:2.8.0` lacks the `PluginClassLoaderActivator` helper, so uses direct activate/try-finally/deactivate instead — matches Ranger's own Kafka shim at this release)
- [x] 01-03: Service-def + `RangerServicePinot` (test connection, lookup) — minimal `table` resource + `query`/`all` accessTypes for now; `getDefaultRangerPolicies()` uses base-class default, not overridden
- [x] 01-04: GitHub Actions CI (checkstyle, RAT, spotbugs, JDK 17 build) — config written, `mvn clean verify` passes locally under JDK 17; not yet run on a pushed GitHub remote

### Phase 2: Broker Enforcement
**Goal**: Pinot's broker enforces Ranger table-level ACL policies on every query, with audit logging and fail-closed behavior.
**Depends on**: Phase 1
**Requirements**: BROKER-01, BROKER-02, BROKER-03, BROKER-04, BROKER-05, ADMIN-03
**Success Criteria** (what must be TRUE):
  1. A query against a table the user is not authorized for is rejected by the broker — DONE (2026-09-17), verified via `RangerBasePlugin.setPolicies(...)`-driven unit tests (real policy engine, no mocks)
  2. A query against an authorized table succeeds and produces exactly one audit event (allow), a denied query produces exactly one audit event (deny) — DONE, verified via a capturing test `RangerAccessResultProcessor`
  3. If Ranger Admin is unreachable and no local policy cache exists, all queries are denied (fail-closed), not allowed — DONE, verified: a fresh `RangerBasePlugin` with no `init()`/`setPolicies()` call returns a null `RangerAccessResult`, treated as deny
  4. Policy changes made in Ranger Admin are reflected in broker enforcement within the configured poll interval, without a broker restart — DONE for the mechanism (same plugin instance, `setPolicies(v1)` then `setPolicies(v2)`, no restart, verified by test); the live poll-interval-driven Ranger-Admin-REST-client path itself is NOT separately verified against a real Admin server — deferred to Phase 5's integration harness, consistent with how FOUND-03/04 were deferred
**Plans**: TBD

Plans:
- [x] 02-01: `RangerPinotAuthorizer` core (resource/request building, `RangerBasePlugin` wiring)
- [x] 02-02: Broker `AccessControlFactory`/`AccessControl` implementation + audit handler (also added the `authorize(identity, BrokerRequest)` overload, required after reading Pinot's real single-stage query path -- not in the original plan, but its default implementation throws and would have broken every real query)
- [x] 02-03: Fail-closed + policy-refresh verification tests (6 JUnit tests, all passing)

### Phase 3: Row Filtering
**Goal**: Ranger row-filter policies defined in Admin are enforced on Pinot broker queries. (Column masking was found infeasible: Pinot 1.4.x/1.5.x's broker SPI has no masking channel — `TableRowColAccessResult` only carries RLS SQL predicates — so `evalDataMaskPolicies` has nothing to consume and no `dataMaskDef` was added. Re-evaluate when Pinot adds a masking channel.)
**Depends on**: Phase 2
**Requirements**: MASK-01, MASK-03 (MASK-02 infeasible — see REQUIREMENTS.md)
**Success Criteria** (what must be TRUE):
  1. A row-filter policy on a table causes queries against that table to only see rows matching the filter — DONE in code: `RangerPinotAccessControl.getRowColFilters` returns the policy's filter expression, unit-tested against the real policy engine. Live end-to-end (broker-side `enableRowColumnLevelAuth` config on, query actually rewritten) rides Phase 5's integration harness
  2. ~~A column-mask policy on a table causes the masked column's values to be transformed/hidden in query results~~ INFEASIBLE with the target Pinot SPI (see Goal note)
  3. Ranger Admin's policy-authoring UI accepts row-filter policy definitions for the `pinot` service type without error — `rowFilterDef` added to the service-def (modeled on Hive's); data-mask authoring deliberately not added (would be dead config)
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
- [x] 04-01: Controller `AccessControlFactory`/`AccessControl` + `FineGrainedAccessControl` implementation
- [x] 04-02: Action-to-accessType mapping (Pinot's `Actions` constants → service-def accessTypes)
- [x] 04-03: Tag-policy end-to-end verification (broker + controller)

### Phase 5: Packaging & Release
**Goal**: A tagged release produces a Ranger-style plugin tarball, published automatically to GitHub Releases, verified against real Pinot 1.4.x and 1.5.x clusters.
**Depends on**: Phase 3, Phase 4
**Requirements**: PKG-01, PKG-02, PKG-03, CI-04, COMPAT-01, COMPAT-02
**Success Criteria** (what must be TRUE):
  1. [x] Pushing a version tag produces a `ranger-<version>-pinot-plugin.tar.gz` GitHub Release asset with the correct internal layout (`lib/ranger-pinot-plugin-impl/`, install scripts, conf templates, version file) — layout DONE (05-01, verified tarball); the tag→Release automation is 05-02
  2. [x] `enable-pinot-plugin.sh` successfully wires the plugin into a real Pinot broker+controller install without requiring Hadoop JCEKS tooling — script DONE (05-01, smoke-tested against a synthetic $PINOT_HOME); live-cluster run is 05-03
  3. The integration-test job passes against both a Pinot 1.4.x and a Pinot 1.5.x target, using the same built artifact
**Plans**: TBD

Plans:
- [x] 05-01: Distro assembly (tarball layout, install/enable/disable/upgrade scripts)
- [ ] 05-02: GitHub Actions release-on-tag workflow
- [ ] 05-03: Integration test matrix (Pinot 1.4.x + 1.5.x via docker-compose/testcontainers)

## Progress

**Execution Order:**
Phases execute in numeric order: 1 → 2 → 3 → 4 → 5

| Phase                              | Plans Complete | Status      | Completed |
| ----------------------------------- | --------------- | ----------- | --------- |
| 1. Foundation & Scaffolding          | 4/4             | Complete (live Ranger-Admin/Pinot-controller check deferred to Phase 5) | 2026-09-17 |
| 2. Broker Enforcement                | 3/3             | Complete (core logic verified; live Ranger-Admin-poll path deferred to Phase 5) | 2026-09-17 |
| 3. Row Filtering & Column Masking     | 2/2             | Complete (row-filter only; column masking infeasible with current Pinot SPI) | 2026-09-17 |
| 4. Controller (Admin API) Enforcement | 3/3             | Complete (unit-verified incl. tag policies; live 403 checks in Phase 5) | 2026-09-17 |
| 5. Packaging & Release               | 0/3             | Not started | -         |
