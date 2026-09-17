# Project State

## Project Reference

See: .planning/PROJECT.md (updated 2026-09-17)

**Core value:** A Ranger admin defines one set of table-level policies (ACL + tag + row-filter + column-mask) enforced consistently on both Pinot's broker and controller, with audit and fail-closed behavior.
**Current focus:** Phase 1 - Foundation & Scaffolding

## Current Position

Phase: 1 of 5 (Foundation & Scaffolding)
Plan: 0 of 4 in current phase
Status: Ready to plan
Last activity: 2026-09-17 - Project initialized: PROJECT.md, REQUIREMENTS.md, ROADMAP.md written after direct source research into apache/ranger (Hive/Kafka plugins) and apache/pinot (AccessControl SPI, verified stable across 1.4.0/1.5.1/master)

Progress: [░░░░░░░░░░] 0%

## Performance Metrics

**Velocity:**
- Total plans completed: 0
- Average duration: - min
- Total execution time: 0 hours

**By Phase:**

| Phase | Plans | Total | Avg/Plan |
| ----- | ----- | ----- | -------- |
| -     | -     | -     | -        |

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

### Pending Todos

None yet.

### Blockers/Concerns

- Open question (deferred to Phase 5 planning): exact Maven Central artifactIds for `org.apache.ranger:*` published dependencies need verification against the chosen Ranger compatibility baseline version — some may differ from reactor module directory names seen in source.
- Open question (deferred to Phase 5 planning): whether Ranger's `ranger_credential_helper.py` is truly Hadoop-independent, or needs a Pinot-specific rewrite.

## Session Continuity

Last session: 2026-09-17
Stopped at: ROADMAP.md and REQUIREMENTS.md written; PROJECT.md written; not yet committed to git.
Resume file: None
