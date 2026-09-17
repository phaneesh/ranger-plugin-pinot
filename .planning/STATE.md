# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-17)

**Core value:** A Ranger admin defines one set of table-level policies (ACL + tag + row-filter + column-mask) enforced consistently on both Pinot's broker and controller, with audit and fail-closed behavior.
**Current focus:** Phase 3 - Row Filtering & Column Masking (not yet started)

## Current Position

Phase: 3 of 5 (Row Filtering & Column Masking) - ready to plan
Plan: 0 of 2 in current phase
Status: Phases 1 and 2 complete (with two deliberately-deferred live-infra verification items, both folded into Phase 5's real integration harness rather than built as one-off throwaway harnesses)
Last activity: 2026-09-17 - Phase 2 broker enforcement implemented and verified (commit 71281b7); Phase 1's classloader-isolation gap closed with a real, rigorous automated test (commit 7c9723e) that also caught a genuine infinite-recursion bug in an initial test design (shim+impl sharing a classpath recreates the exact hazard isolation exists to prevent). ROADMAP/REQUIREMENTS updated to reflect true status.

Progress: [███░░░░░░░] ~30% (2 of 5 phases substantively complete; Phase 3-5 remain)

## Performance Metrics

**Velocity:**
- Total plans completed: 7 (Phase 1: 4, Phase 2: 3)
- Average duration: - min
- Total execution time: - hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
| ----- | ----- | ----- | -------- |
| 1     | 4/4   | -     | -        |
| 2     | 3/3   | -     | -        |

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
Stopped at: Phases 1 and 2 complete and committed (7c9723e is HEAD). Next up is Phase 3 (row filtering & column masking via the broker's `getRowColFilters` hook) — needs `rowFilterDef`/`dataMaskDef` added to the service-def JSON and `RangerBasePlugin.evalRowFilterPolicies`/`evalDataMaskPolicies` wired into `RangerPinotAccessControl`. Notebook pages `pinot-auth-spi`, `ranger-plugin-architecture`, `ranger-plugin-api-facts` hold all research needed to start Phase 3 without re-deriving facts.
Resume file: None
