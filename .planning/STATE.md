# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-17)

**Core value:** A Ranger admin defines one set of table-level policies (ACL + tag + row-filter + column-mask) enforced consistently on both Pinot's broker and controller, with audit and fail-closed behavior.
**Current focus:** Phase 1 - Foundation & Scaffolding (code complete, live verification pending)

## Current Position

Phase: 1 of 5 (Foundation & Scaffolding)
Plan: 4 of 4 in current phase (all written; live/integration verification not yet done)
Status: In progress - code scaffolding complete and building green; two of four Phase 1 success criteria (live Ranger Admin registration, automated classloader-isolation test) still open
Last activity: 2026-09-17 - Phase 1 scaffolding implemented by spawned child agent: root pom + ranger-pinot-plugin + ranger-pinot-plugin-shim modules, service-def JSON, RangerServicePinot (+ PinotClient/PinotConnectionMgr), allow-all broker/controller stub authorizers, GitHub Actions CI. `mvn clean verify` passes locally under JDK 17 (0 checkstyle/RAT/spotbugs findings). Committed across 3 commits (d2e0424 feat, c7fede8 chore-gitignore, fee0fcf chore-remove-stray-file). Roadmap/Requirements traceability updated to reflect "code done, live verification pending" status, not fully "Complete".

Progress: [█░░░░░░░░░] ~8% (Phase 1 code done, 4 phases remaining, live verification of Phase 1 outstanding)

## Performance Metrics

**Velocity:**
- Total plans completed: 4 (Phase 1, code-complete; not yet counted as fully verified)
- Average duration: - min
- Total execution time: - hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
| ----- | ----- | ----- | -------- |
| 1     | 4/4   | -     | -        |

**Recent Trend:**
- Last 5 plans: -
- Trend: -

*Updated after each plan completion*

## Accumulated Context

### Decisions

Decisions are logged in PROJECT.md Key Decisions table.
Recent decisions affecting current work:

- Init: Module naming follows modern Ranger convention (`ranger-pinot-plugin`/`ranger-pinot-plugin-shim`), not Hive's legacy `-agent` dir naming
- Init: RLS/CLS (row filter + column mask) is IN SCOPE for true Hive parity — Pinot's broker `getRowColFilters` hook makes this achievable natively
- Init: One plugin build targets both Pinot 1.4.x and 1.5.x — verified identical SPI signatures, no per-version shim needed
- Phase 1: `ranger-plugins-audit:2.8.0` is a pom-only aggregator on Maven Central (not resolvable as a jar) — substituted `ranger-audit-core:2.8.0` (the real jar artifact)
- Phase 1: `PluginClassLoaderActivator` (AutoCloseable classloader-activation helper) does not exist in published `ranger-plugin-classloader:2.8.0` (only on unreleased master, and still absent at 2.9.0 latest) — shim classes use direct `activate()`/try-finally/`deactivate()` instead, matching Ranger's own Kafka shim at this release. Revisit if/when a Ranger release ships that helper.
- Phase 1: Service-def kept minimal (`table` resource + `query`/`all` accessTypes only) — full ~50-action `Actions`-class mapping deferred to Phase 2 (broker) + Phase 4 (controller CRUD/cluster actions), and `rowFilterDef`/`dataMaskDef` deferred to Phase 3, to avoid modeling unused accessTypes early

### Pending Todos

None yet.

### Blockers/Concerns

- Phase 1 success criteria #2 (Ranger Admin registers the `pinot` service, Test Connection + table lookup work against a real Pinot controller) and #3 (classloader isolation verified by an automated test) are NOT yet verified — code exists and compiles/passes `mvn clean verify`, but no live Ranger Admin + Pinot controller integration test has been run. Recommend folding this verification into Phase 2's test work (which needs a live Pinot+Ranger harness anyway) rather than standing up a one-off harness just for Phase 1.
- Open question (deferred to Phase 5 planning): exact Maven Central artifactIds for remaining `org.apache.ranger:*` published dependencies need periodic re-verification — already found one surprise in Phase 1 (`ranger-plugins-audit` is pom-only, real jar is `ranger-audit-core`).
- Open question (deferred to Phase 5 planning): whether Ranger's `ranger_credential_helper.py` is truly Hadoop-independent, or needs a Pinot-specific rewrite.
- GitHub Actions CI workflow is written but has never actually run (no GitHub remote pushed yet) — first real push should be watched closely in case the hosted runner environment differs from the local JDK-17 verification.

## Session Continuity

Last session: 2026-09-17
Stopped at: Phase 1 code scaffolding complete and building green locally (JDK 17); ROADMAP/REQUIREMENTS/STATE updated to reflect "code done, live verification pending" rather than "Complete". Next: either (a) proceed straight into Phase 2 (broker enforcement), building a live Pinot+Ranger test harness there that also retroactively closes out Phase 1's two open verification criteria, or (b) pause Phase 1 to build that harness first. Recommendation: option (a) — avoid a throwaway one-off harness.
Resume file: None
