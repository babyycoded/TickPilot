# Changelog

All notable changes to TickPilot are recorded here. Requirement ids (`FR-*`, `AC-*`, `INV-*`)
refer to `SPEC.md`; decisions that deviate from it are logged in `SPEC.md` §13.

## Unreleased

### Phase 3 — tick metrics and load levels (FR-1, FR-5)

- `com.tickpilot.metrics.TickMetrics` — ring buffer of 6000 tick samples (5 min at 20 TPS) on two
  `long[]` arrays. Reports TPS, average MSPT over 5 s / 1 min / 5 min, p95, p99, max and the last
  tick duration (FR-1, AC-1). The tick path does two array writes and a few counter updates: no
  allocation, no scanning, no locks (INV-6). Averages, percentiles and TPS are computed on the
  command path instead.
- `com.tickpilot.metrics.TickMetricsSnapshot` — immutable view of all AC-1 values taken at one
  instant, so `status` cannot print numbers from two different moments.
- `com.tickpilot.budget.TickBudget` + `LoadLevel` — NORMAL / ELEVATED / HIGH / CRITICAL from the
  5 s average MSPT (FR-5), with a 2 ms hysteresis margin and a 5 s minimum hold before a level can
  be left downwards (AC-5). Escalation is immediate by design; the input is already smoothed.
  Transitions are returned to the caller as a `LoadLevelTransition` and logged exactly once
  (AC-5, AC-16) — the class itself neither logs nor imports anything from Minecraft.
- Measurement source is `ServerTickEvents.START_SERVER_TICK` / `END_SERVER_TICK`. **No mixin was
  added** (MX-1). What the event pair does and does not cover is documented in
  `TickPilotTickListener`, in the README and in §13 entry #8.
- `/tickpilot status` now reports TPS, MSPT (last / 5 s / 1 min / 5 min / p95 / p99 / max), load
  level with its thresholds, uptime and tick count (FR-12).
- `/tick freeze` and `/tick rate` state is read from `MinecraftServer.tickRateManager()` each tick
  and surfaced by `status`, so a deliberately reduced TPS is never presented as overload (AC-1b).
- Server stop clears the tick history, so world B cannot observe world A's numbers (AC-19, INV-7).
- 32 new unit tests (19 for `TickMetrics`, 13 for `TickBudget`), none of which launch Minecraft.
- **SPEC change:** the FR-5 HIGH threshold is now `target + (critical - target) * 0.5` instead of
  `target + 25 %`, which at the default 40/50 collapsed HIGH to an unreachable empty band.
  §13 entry #7.

#### Not implemented / deferred

- **Thresholds are not configurable yet** — `TickBudget` takes target, critical, hysteresis and
  hold time as constructor arguments and validates them, but the server wires in the SPEC defaults
  because the config file arrives with FR-15 in Phase 4.
- **Own overhead is not measured** — INV-10 and FR-12 want the mod's own cost shown in `status`.
  Nothing measures it yet; the README states the cost qualitatively instead of inventing a number.
- **Cross-check against vanilla `getTickTimesNanos()`** — AC-1b allows it as a test-only sanity
  check. Not written: it needs a running server, and this phase's tests are deliberately
  Minecraft-free. To be covered by the integration checklist.
- **Per-category breakdown** — `status` reports total tick time only. Categories are FR-2 (Phase 5).

### Phase 2 — mod skeleton and lifecycle (FR-17, FR-18, FR-19)

- Main entrypoint `com.tickpilot.TickPilot` subscribes to `SERVER_STARTED`, `SERVER_STOPPING`
  and `SERVER_STOPPED`, creating and destroying per-server state (FR-19, AC-19).
- `TickPilotServerState` holds all state belonging to one running server; `ServerStateHolder`
  maps servers to it, backed by the Minecraft-free `ServerStateRegistry`. Nothing survives a
  world reload (INV-7; see §13 entry #5 for why a static lookup table is compliant).
- `/tickpilot status` reports liveness only — "TickPilot active, no metrics yet" (FR-12).
  Player-facing text goes through translation keys in `assets/tickpilot/lang/en_us.json`.
- Runs on both a dedicated server and the integrated server of a singleplayer world (FR-17).
  No client-only class is referenced from common/server code (FR-18), verified by grep.
- Every lifecycle callback and command body catches `Throwable`, logs once and steps aside
  rather than propagating into the server (INV-9).
- Removed the template's `ExampleMixin` and `ExampleClientMixin`. The project currently ships
  zero mixins; both mixin configs remain in place for later phases.
- Build: JUnit 5 (`org.junit:junit-bom:5.11.4`) plus `useJUnitPlatform()` (§13 entry #2).
  `junit-platform-launcher` is declared explicitly because Gradle 9 no longer adds it.
- Licence changed from the template's CC0-1.0 to MIT, in `LICENSE` and `fabric.mod.json`
  (§13 entry #3).
- 8 unit tests cover the per-server state lifecycle without launching Minecraft.

Verified by hand: dedicated server and two separate singleplayer worlds both answer
`/tickpilot status` without errors; server stop completes cleanly.

### Not implemented / deferred

- **Integrated-server absence check in the client entrypoint** — deferred to Phase 10 (FR-20).
  At `onInitializeClient()` time no integrated server exists yet, so a check there would be
  dead code. It will be written where the HUD actually reads server state. §13 entry #6.
- **Configurable permission level for `/tickpilot status`** — FR-12 calls for level 0 that an
  operator can change. It is currently hard-wired to level 0 because the config file arrives
  with FR-15 in Phase 4.
- **All metrics** — `status` reports liveness, not numbers. Tick measurement is FR-1 (Phase 3);
  nothing in this phase measures anything.
- **Full INV-7 verification between worlds** — the unit tests prove state is created and
  removed per server, but "counters really start from zero in world B" cannot be observed until
  there are counters. To be re-checked in Phase 3.
