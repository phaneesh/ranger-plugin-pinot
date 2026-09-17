# Ranger Plugin for Apache Pinot

## What This Is

A standalone, multi-module Maven project (`ranger-plugin-pinot`) that adds Apache Ranger
authorization support to Apache Pinot (1.4.x and 1.5.x), modeled directly on
`apache/ranger`'s own plugin implementations — structurally on the modern
`plugin-<name>`/`ranger-<name>-plugin-shim` pattern (Kafka/Presto/Trino), and at
Hive-plugin feature depth (resource ACLs, tag-based policies, row-level filtering,
column masking, audit logging, policy-refresh-with-fallback). It enforces access
control at both of Pinot's authorization surfaces: the broker (query-time table ACL +
row/column security) and the controller (admin REST API, table CRUD + cluster-level
actions). Ships as an independent GitHub repo with its own multi-module Maven build,
GitHub Actions CI, and a tag-triggered release workflow publishing a Ranger-style
plugin tarball as a GitHub Release asset.

## Core Value

A Ranger administrator can define one set of table-level access policies (with tag
policies, row filters, and column masks) in Ranger Admin, and have them enforced
consistently on both Pinot's broker (queries) and controller (admin API) — with
audit trails, fail-closed behavior on policy-fetch failure, and zero Pinot-specific
policy authoring quirks relative to how Hive/Kafka admins already work with Ranger.

## Requirements

### Validated

(None yet - ship to validate)

### Active

- [ ] Multi-module Maven layout mirroring Ranger's shim/impl split (`ranger-pinot-plugin` + `ranger-pinot-plugin-shim`)
- [ ] `RangerPluginClassLoader`-based dependency isolation between the plugin and Pinot's own classpath
- [ ] Ranger Admin service-def (`ranger-servicedef-pinot.json`) with table resource, Pinot's ~50-action vocabulary as accessTypes, rowFilterDef, dataMaskDef
- [ ] `RangerServicePinot` (admin-side): Test Connection against Pinot controller, table-name autocomplete (lookupResource), default policies
- [ ] Broker `AccessControlFactory`/`AccessControl` implementation: per-table ACL, fail-closed on null policy result, audit emission
- [ ] Row-level filtering (RLS) and column masking (CLS) via broker's `getRowColFilters` hook, wired to Ranger's `evalRowFilterPolicies`/`evalDataMaskPolicies`
- [ ] Controller `AccessControlFactory`/`AccessControl` implementation: table-scoped CRUD + `FineGrainedAccessControl` CLUSTER/TABLE target-type enforcement
- [ ] Tag-based policy support (inherited from `RangerBasePlugin`, verified working end-to-end)
- [ ] Distro packaging: `ranger-<version>-pinot-plugin.tar.gz` matching Ranger's tarball layout (lib/, install/, conf.templates/, install.properties, enable/disable/upgrade scripts)
- [ ] GitHub Actions CI: JDK 17, `mvn clean verify`, checkstyle + Apache RAT (hard fail), spotbugs (non-blocking)
- [ ] GitHub Actions release workflow: on version-tag push, builds and publishes the plugin tarball as a GitHub Release asset
- [ ] Verified compatibility: single build works against both Pinot 1.4.x and 1.5.x
- [ ] Integration test suite: real Pinot cluster + Ranger Admin (docker-compose/testcontainers), exercising allow/deny/audit/mask end-to-end

### Out of Scope

- GRANT/REVOKE SQL sync — Pinot has no SQL grant/revoke DDL, unlike Hive; not applicable
- Hadoop JCEKS credential-provider install flow — Pinot doesn't ship a Hadoop classpath by default; install scripts use Pinot's own property-based config instead of `hadoop credential create`
- Resource types beyond `table`/`cluster` (e.g. per-segment or per-schema Ranger resources) — Pinot's native REST/broker SPI only exposes `CLUSTER` and `TABLE` as target types; finer-grained resources would require inventing a Pinot-side concept Ranger has no hook for
- Building/publishing to Maven Central or Apache dist.apache.org — this is an independent (non-ASF) repo; distribution is via GitHub Releases only

## Context

- Reference implementation: https://github.com/apache/ranger (Hive plugin = feature-depth reference; Kafka/Presto plugins = structural/module-naming reference, since Pinot is a query-engine plugin with no filesystem resources, closer to Kafka/Presto than to Hive/HDFS).
- Verified via direct source inspection (sparse partial clones of apache/ranger and apache/pinot at release-1.4.0/release-1.5.1/master) that Pinot's `AccessControl`/`AccessControlFactory`/`FineGrainedAccessControl` interfaces are byte-identical across 1.4.0, 1.5.1, and master — a single plugin build compiled against one baseline works unmodified against both target Pinot versions. Both pinned to JDK 11, `javax.ws.rs` (not migrated to jakarta), Jersey 2.47.
- Pinot's broker SPI uniquely exposes a native `getRowColFilters` hook (row/column security), unlike Kafka's plugin (which has no RLS/CLS analog at all) — this makes genuine Hive-parity RLS/CLS achievable without touching Pinot's query planner internals.
- Full architecture research (module layout, classloader mechanism, audit handler shape, service-def structure, distro tarball layout, CI conventions) captured from direct source reading of apache/ranger's hive-agent, ranger-hive-plugin-shim, plugin-kafka, agents-common, ranger-plugin-classloader, and distro modules — see project notebook pages `pinot-auth-spi` and `ranger-plugin-architecture` for full detail with file paths and class names.

## Constraints

- **Reference fidelity**: Must match `apache/ranger`'s coding standards, design patterns, and code style — module naming (`ranger-<name>-plugin`/`ranger-<name>-plugin-shim` artifactIds), classloader-isolation shim pattern, `RangerBasePlugin`/`RangerAccessResourceImpl`/`RangerAccessRequestImpl`/`RangerDefaultAuditHandler` reuse, service-def JSON shape, install-script/tarball conventions.
- **Pinot version support**: Must work against both Pinot 1.4.x and 1.5.x.
- **Build system**: Multi-module Maven project (matching Ranger's own build tooling), JDK 17 for the build (Ranger's CI baseline), plugin runtime targets Pinot's JDK 11 baseline.
- **CI/CD**: GitHub Actions for build/verify on every push/PR, and a separate tag-triggered workflow that publishes a release package (Ranger-style tarball) as a GitHub Release asset per tag.
- **Dependency isolation**: Real impl module's dependencies (Ranger's fat stack: jersey, jackson, solr-solrj, etc.) must never leak onto Pinot's own classpath, and vice versa — enforced via `RangerPluginClassLoader`, matching the shim pattern.

## Key Decisions

| Decision | Rationale | Outcome |
| -------- | --------- | ------- |
| Module naming: `ranger-pinot-plugin` + `ranger-pinot-plugin-shim` (modern convention), not `pinot-agent` | Pinot is structurally a query-engine plugin (REST + query-time auth, no filesystem resources) — closer to Kafka/Presto/Trino (which use the `plugin-<name>` convention) than to Hive/HDFS's legacy `<svc>-agent` naming. ArtifactId convention (`ranger-<name>-plugin[-shim]`) is identical either way. | - Pending |
| Implement RLS/CLS (row filter + column masking) for true Hive parity | Pinot's broker `AccessControl.getRowColFilters` is a native SPI hook that supports this without touching Pinot's query planner — unlike Kafka, which has no equivalent hook at all. Declining this would mean falling short of the explicitly requested "Hive plugin feature parity." | - Pending |
| No shim-per-Pinot-version; one build targets both 1.4.x and 1.5.x | Verified via direct source diff that the relevant SPI interfaces are byte-identical across release-1.4.0, release-1.5.1, and master. | - Pending |
| Classloader activation via `PluginClassLoaderActivator` (try-with-resources), copying Kafka's pattern, not Hive's manual activate/deactivate | Kafka's SPI shape (single interface, no factory-of-authorizer indirection) matches Pinot's `AccessControl` shape much more closely than Hive's `HiveAuthorizerFactory` does. | - Pending |
| Install/enable scripts are forked from Ranger's generic scripts, not copied verbatim | Ranger's generic `enable-agent.sh`/credential flow assumes Hadoop-style `*-site.xml` config files and `hadoop credential create` (JCEKS); Pinot has neither by default. Scripts must instead rewrite Pinot's own property-based config format. | - Pending |
| Resource hierarchy is `table` (+ optional `cluster` scope), no column/segment/schema resource levels | Pinot's native REST/broker SPI (`TargetType` enum) only defines `CLUSTER` and `TABLE` — there is no admin-API concept of a column or segment as an independently addressable resource; column-level security is expressed only via the RLS/CLS query-time hook, not as a distinct Ranger resource type. | - Pending |

---
*Last updated: 2026-09-17 after initialization*

## Evolution

This document evolves at phase transitions and milestone boundaries.

**After each phase transition** (via `/gsd-transition`):
1. Requirements invalidated? → Move to Out of Scope with reason
2. Requirements validated? → Move to Validated with phase reference
3. New requirements emerged? → Add to Active
4. Decisions to log? → Add to Key Decisions
5. "What This Is" still accurate? → Update if drifted

**After each milestone** (via `/gsd-complete-milestone`):
1. Full review of all sections
2. Core Value check - still the right priority?
3. Audit Out of Scope - reasons still valid?
4. Update Context with current state
