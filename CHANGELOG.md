# Changelog

All notable changes to TickPilot are recorded here. Requirement ids (`FR-*`, `AC-*`, `INV-*`)
refer to `SPEC.md`; decisions that deviate from it are logged in `SPEC.md` §13.

## Unreleased

### Phase 4 — configuration (FR-15, AC-15)

- `config/tickpilot.toml` — every key and default of the FR-15 schema, created with its comments
  on first start and applied by `/tickpilot reload` without a restart.
- `com.tickpilot.config.TickPilotConfig` — immutable, already-validated snapshot. Held per
  `MinecraftServer` in `TickPilotServerState`, not in a static field (INV-7).
- `com.tickpilot.config.TomlParser` / `TomlWriter` — a hand-written parser for the subset of TOML
  the schema uses, and the writer that produces the commented default file. **No new dependency**
  was added: there is no TOML parser anywhere on the compile classpath, so a library would have
  had to be nested in the mod jar. Choice made with the project owner before touching
  `build.gradle`; alternatives and their costs are in §13 entry #10, the unsupported syntax is
  listed in the README, and each unsupported construct is refused by name rather than as a
  generic syntax error.
- `com.tickpilot.config.ConfigLoader` — the three AC-15 behaviours:
  - file missing → written once with defaults;
  - file unreadable or unparseable → defaults, and **the file is not touched**, verified on a live
    server by comparing its checksum before and after;
  - a single value invalid → that field falls back to its default and is reported, every other
    value from the file is kept.
  Nothing throws at the caller: a config problem is a log line, never a crash (INV-9).
- Cross-field validation for the two pairs that can be individually valid and jointly useless:
  `critical_mspt > target_mspt` and `reduced_radius > full_radius`. Both would otherwise leave a
  load level or an activity zone as an unreachable empty band — the §13 entry #7 failure. The
  loader keeps the operator's value when repairing one side of the pair is enough, and resets both
  only when it is not. Added rule, §13 entry #11.
- `TickBudget` now takes its thresholds from the config instead of the hard-wired defaults, closing
  the Phase 3 deferral. It is rebuilt on reload **only if a threshold actually moved**, so a reload
  does not silently drop the reported load level back to NORMAL for no reason.
- `/tickpilot reload` (permission level 2, FR-12) reports which of the four outcomes happened,
  lists up to eight rejected values in chat with the rest in the log, and returns failure to a
  command block when the file could not be read. An unparseable file puts the server back on
  defaults, and the message says so — nobody is left thinking their edits took effect.
- 61 new unit tests (24 for the parser, 31 for loading and validation, 6 for applying a config to
  a running server), none of which launch Minecraft; the filesystem cases run against a JUnit
  `@TempDir`. Suite total is now 114.

#### Fixed during Phase 4

- **Reload fed the load-level state machine a clock from a different epoch.** `reconfigure` took
  `System.currentTimeMillis()` while every other clock value in `TickPilotServerState` — and every
  value the tick loop passes `TickBudget.update` — comes from `System.nanoTime()`. A rebuilt budget
  would have recorded its hold-period start about fifty-five years ahead of the tick loop's clock,
  so `heldForMillis` stayed negative forever and the level could never drop back down after a
  reload with changed thresholds. Found by review, not by a failing test, so `reconfigure` now
  takes nanoseconds like its neighbours and `TickPilotServerStateTest` pins the unit down.

Verified on a dedicated server (`./gradlew runServer`), all four paths: the file is created at
`run/config/tickpilot.toml` with the FR-15 defaults; a clean reload reports success; a file with
three bad values and one misspelled key logs exactly those four and keeps everything else; and a
file with a syntax error logs `line 1`, runs on defaults and comes out of the run with an
unchanged md5.

#### Not implemented / deferred

- **Warm-up period for the load level** — Phase 3 deferred the bogus `NORMAL -> CRITICAL` logged
  at every server start to this phase, on the grounds that the thresholds were moving into the
  config anyway. They have, but the fix itself is a `TickBudget` behaviour change rather than a
  config one and is not part of the FR-15 scope, so it is **still open** and still reproduces
  (observed again during this phase's manual verification: `Load level NORMAL -> CRITICAL
  (avg MSPT 51.42)` a second after `Done`).
- **Configurable permission level for `/tickpilot status`** — FR-12 wants an operator-settable
  level, but FR-15 defines no key for it and this phase does not invent one. Still hard-wired
  to level 0.
- **`enable_adaptive_mode`, `default_mode` and the five lists are stored, not obeyed** — they are
  parsed and validated, and nothing reads them yet. The scheduler, zones and policies that act on
  them are FR-6, FR-7 and FR-11 in later phases. `safe_compatibility_mode` forcing STRICT is
  implemented as `TickPilotConfig.effectiveMode()`, but with no policy to obey it that is a value,
  not a behaviour.
- **No hot reload from a file watcher** — FR-15 does not ask for one; the config is re-read on
  `/tickpilot reload` and at server start only.

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

#### Fixed during manual verification of Phase 3

- **TPS was permanently 19.80 on a healthy server.** The rate was computed as "samples whose end
  timestamp falls in the last 5 s, divided by 5 s". `/tickpilot status` runs inside
  `tickChildren`, so the tick in progress has not recorded its end yet: a 5 s window crosses 100
  tick boundaries but contains only 99 recorded ends, and the fixed divisor quantised the answer
  to steps of 0.2 TPS. The result was 19.80 regardless of load. TPS is now the mean interval
  between recorded tick ends — `(n - 1)` periods divided by the time they span — which does not
  depend on where the window boundary falls. Time waiting for an overdue tick is folded in after
  a two-period grace, so stalls are still visible. Five regression tests, including a sweep of
  every query phase within a tick.
- **Player-facing strings are ASCII only.** An em dash in the `status` header rendered as `ΓÇö`
  on a Windows console running a legacy code page. `LangFileAsciiTest` fails the build if a
  non-ASCII character reappears in `en_us.json`.
- **p95/p99 were computed over the whole ring buffer**, so one burst of slow ticks kept them
  elevated until it was evicted — five minutes at 20 TPS. The cross-check made it visible:
  TickPilot read p95 3.47 ms where vanilla read 0.3 ms over its 100-tick window, because the
  spawn-chunk generation from server startup was still in the buffer. Startup was only the most
  visible case; an autosave, a player joining or a `/reload` did the same at any point in a
  server's life. Percentiles now come in two windows — 1 min for "how is it now", whole history
  for "how bad has it been" — and `TickMetricsSnapshot` carries both so `explain` (FR-13) can
  contrast them. `max` keeps the full history, as AC-13 requires, and is now printed with the
  age of the outlier instead of as a bare number that looks current.
- **An average window the server has not lived through shows `n/a`.** The three averages carry
  the fixed names AC-1 gives them, so unlike the percentile lines they cannot be relabelled with
  the span they cover: at 80 s of uptime `avg 5m 1.07` was an average over 80 seconds wearing a
  "5 min" label. Same rule as AC-2 uses for a profiling category with no data.
- **Window labels report the interval actually held.** The ring buffer is bounded by sample
  count, so 6000 samples is five minutes only at a full 20 TPS, and less than that until it
  fills. `status` no longer prints a nominal `(last 5 min)`; a server up for forty seconds says
  `(last 40s)`. Recorded as §13 entry #9.

Cross-checked against vanilla `/tick query` on a dedicated server (`./gradlew runServer`, idle
world, three samples): TPS reads 20.00 where it previously read 19.80, and average MSPT agrees
with vanilla within the one decimal vanilla prints (0.28 / 0.26 / 0.20 against 0.2 / 0.3 / 0.2).
In one sample both reported the same outlier — vanilla P99 8.2 ms, TickPilot p99 8.15 ms.
Note that vanilla reports no TPS at all, and its percentiles cover the last 100 ticks, so only
the averages were directly comparable — the percentile window mismatch is what led to the fix
below.

Verified again on a dedicated server after the percentile change. At 35 s of uptime the label
reported the real span (`p95 2.02, p99 10.25 (last 35s)`) rather than a nominal window. At
1 m 45 s the two windows separated as intended: `p95 0.13, p99 0.20 (last 1m 00s)` next to
`max 128.89, 1m 45s ago; p95 0.89, p99 3.24 (history: 1m 45s)` — the startup burst is dated and
out of the short window while remaining explainable. Vanilla, queried two seconds later, read
`P95: 0.1ms P99: 2.1ms, sample: 100`: p95 agrees, and the P99 gap is the 100-sample rank
resolution the 1 min window exists to avoid (vanilla's P99 is the second-slowest of a hundred
ticks, TickPilot's is the thirteenth-slowest of twelve hundred).

#### Not implemented / deferred

- **False CRITICAL on server startup — Phase 4, warm-up period.** The first tick after
  `Done (…)!` genuinely costs ~120 ms (measured: 117.37 ms on an idle dedicated server), so every
  start logs `Load level NORMAL -> CRITICAL` and then recovers to NORMAL about five seconds later.
  The reading is truthful but useless: nothing is overloaded, the server is still warming up, and
  one bogus critical line per start is noise that AC-16 exists to prevent. Deliberately not fixed
  in Phase 3 — the fix changes `TickBudget` behaviour, and its thresholds move into the config
  file in Phase 4 anyway. Fix it there, together with FR-15: hold the level at NORMAL until the
  ring buffer holds a minimum number of samples (or a warm-up interval has passed) rather than
  special-casing the first tick.
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
