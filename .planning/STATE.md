# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-17)

**Core value:** A Ranger admin defines one set of table-level policies (ACL + tag + row-filter) enforced consistently on both Pinot's broker and controller, with audit and fail-closed behavior.
**Current focus:** Phase 5 - Packaging & Release (ready to plan)

## Current Position

Phase: 5 of 5 (Packaging & Release) - plan 05-01 complete
Plan: 1 of 3 in current phase (05-01 done)
Status: Plan 05-01 (distro assembly) complete: new `ranger-pinot-plugin-distro` module produces `ranger-<version>-pinot-plugin.tar.gz` (maven-assembly descriptor modeled on Ranger 2.8.0's plugin-kafka.xml; `lib/` = shim + ranger-plugin-classloader jars, `lib/ranger-pinot-plugin-impl/` = impl jar + 43 curated transitive deps, host-provided jackson/jersey/slf4j/log4j/reload4j/pinot excluded). Pinot-specific install scripts (property-based, no JCEKS): enable/disable/upgrade smoke-tested end-to-end against a synthetic $PINOT_HOME (install jars, render Ranger XML configs via changes.cfg + sed, wire `pinot.broker.access.control.class`/`controller.admin.access.control.factory.class`/`pinot.broker.enable.row.column.level.auth` into broker/controller .conf files; disable removes exactly what enable added). PKG-01 + PKG-02 complete. 2 consecutive green `mvn clean verify` runs (18+2 tests).
Last activity: Plan 05-01 distro assembly implemented and verified (tarball layout asserted, no host pinot jars in impl dir, scripts bash -n clean).

Progress: [██████░░░░] ~90% (Phase 5: 1 of 3 plans done; 05-02 release workflow and 05-03 integration matrix remain)

## Performance Metrics

**Velocity:**
- Total plans completed: 10 (Phase 1: 4, Phase 2: 3, Phase 4: 3)
- Average duration: - min
- Total execution time: - hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
| ----- | ----- | ----- | -------- |
| 1     | 4/4   | -     | -        |
| 2     | 3/3   | -     | -        |
| 4     | 3/3   | -     | -        |

**Recent Trend:**
- Last 5 plans: -
- Trend: -

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Module naming: modern Ranger convention (`ranger-pinot-plugin`/`ranger-pinot-plugin-shim`)
- RLS/CLS in scope for Hive parity (Pinot's `getRowColFilters` hook makes it achievable) — this is Phase 3's actual work, next up
- One plugin build targets both Pinot 1.4.x and 1.5.x (verified identical SPI signatures)
- `ranger-plugins-audit:2.8.0` is pom-only; use `ranger-audit-core:2.8.0` instead
- `PluginClassLoaderActivator` doesn't exist in published `ranger-plugin-classloader:2.8.0` (only unreleased master) — shim uses direct activate/try-finally/deactivate
- Test policy fixtures MUST assign unique `id`/`guid` to every `RangerPolicy` — omitting them causes a genuine intermittent failure in Ranger's real policy engine (found and fixed in Phase 2, applies to all future test fixtures)
- Identity derivation for broker requests is unresolved upstream (Pinot's `RequesterIdentity` has no "user" concept at all) — currently stands in with `getClientIp()`, flagged TODO for real auth wiring later
- Testing shim+impl together on the SAME ambient classpath is unsafe by design (triggers real infinite recursion via the FQCN-sharing trick) — never do this, even in tests; proven in Phase 1 closure work

### Pending Todos

None yet.

### Blockers/Concerns

- FOUND-03/04 (RangerServicePinot's validateConfig/lookupResource against a REAL running Pinot controller) still deliberately deferred to Phase 5, which builds the real integration harness (docker-compose/testcontainers with the official Pinot image) that both CI-04 and this need — avoids building a throwaway one-off harness twice.
- Broker's real identity derivation (`RequesterIdentity` → user/groups) is an open design question beyond this project's current scope; current stand-in (client IP as principal) is documented but not a long-term answer.
- GitHub Actions CI has never actually run on a pushed remote (repo not yet pushed) — first real push should be watched closely.

## Session Continuity

Last session: 2026-09-17
Stopped at: Phases 1-4 complete and committed. Next up is Phase 5 (packaging & release: distro tarball with `lib/ranger-pinot-plugin-impl/` layout, install scripts, conf templates, GitHub Actions release automation, integration tests). Notebook pages `pinot-auth-spi`, `ranger-plugin-architecture`, `ranger-plugin-api-facts`, `phase-3-row-filtering` hold prior research; Phase 4 added the cluster-resource + Actions-accessType service-def model (see `service-defs/ranger-servicedef-pinot.json`) and the tag-policy test harness pattern (`RangerFileBasedTagRetriever` + classpath ServiceTags JSON + tag-serviceDef with `pinot:`-prefixed accessTypes, prefix stripped by normalization).
Resume file: None
