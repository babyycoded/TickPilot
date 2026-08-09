# Changelog

All notable changes to TickPilot are recorded here. Requirement ids (`FR-*`, `AC-*`, `INV-*`)
refer to `SPEC.md`; decisions that deviate from it are logged in `SPEC.md` §13.

## Unreleased

### Phase 6 — `/tickpilot explain` (FR-13, AC-13)

- `/tickpilot explain` (permission level 2) prints everything AC-13 lists — TPS, average MSPT, p95,
  p99, the longest tick with its age, the dominant category with its share, top-3 entity types,
  top-3 block entity types, top-3 mod IDs, deferred tasks, mode and load level — and ends in **one**
  recommendation plus an estimate of its effect.
- `com.tickpilot.command.ExplainAdvisor` holds the whole decision table and imports nothing from
  `net.minecraft`, so all 17 of its tests run without launching the game. It reads
  `TickMetricsSnapshot`, `LoadLevel` and `TickCategory` directly, which were already Minecraft-free.
- **The effect estimate has five classes and only two of them carry a number.** A number appears
  only when it is an upper bound derived from a measurement — a type's own measured cost, phrased
  as "at most X ms/tick, and only if every one of them stops ticking". Nothing claims that removing
  half of something saves half of the cost, because the distribution across instances was never
  measured. Chunk operations, scheduled ticks, chunk environment, network, saving and `Other` are
  always `UNKNOWN`: there is nothing measured to bound them with, and AC-13 names "expected effect
  unknown" an acceptable answer.
- **MSPT and TPS are kept apart.** Below 50 ms/tick the server is already at 20 TPS, so a saving
  there is headroom and not throughput. The two bounded wordings differ on exactly this point,
  which is the difference between an honest estimate and "this will double your TPS".
- **Order of the branches is the order of the questions.** Is the reading about load at all
  (`/tick freeze`, `/tick rate`, warm-up) → is it trustworthy (profiler self-checks) → is anything
  wrong now → what to do. A non-zero self-check counter suppresses every category verdict rather
  than printing a confident misattribution.
- **The two percentile windows finally earn their keep.** A bad 1 min p99 means drops are happening
  now and the advice is to profile while they are; a clean minute next to a bad history p99 means
  they already stopped and the advice is to wait for the next one. The second claim is guarded
  twice: only when the retained history is longer than a minute (otherwise both pairs come from the
  same samples), and only off the history p99 rather than the maximum (whose value on nearly every
  server is the ~120 ms startup tick, which is dated on its own line and is not a diagnosis).
- **Missing data is stated.** No session → one line and a recommendation to run one, no invented
  breakdown. Under a minute of uptime → the output says what the window really covers, but the
  verdict is still given, because a server dying thirty seconds after a start really is dying.
- Two tests enforce the AC-13 wording rules rather than leaving them to review:
  `onlyTheTwoBoundedEffectsCarryNumbers` fails if an unquantified estimate grows an argument or a
  bounded one loses its "at most", and `noRecommendationPromisesAMultiple` fails the build on
  "faster", "boost", "2x", "guarantee", "will fix" and similar in any recommendation string.
  `everyKeyTheAdvisorCanEmitExistsInTheLanguageFile` drives the decision table and checks each key
  it can return against `en_us.json`.
- `explain` aggregates mod IDs over **every** tracked type rather than over the printed top-N, unlike
  the breakdown under `top`: a mod whose cost is spread across twenty cheap entity types would
  otherwise never appear in a "top mod IDs" list. The namespace aggregation is now one helper used
  by both.
- 17 new unit tests; suite total 177.

#### Verified on a dedicated server, three scenarios

All three are live `./gradlew runServer` runs driven through the server console, not rendered
examples. The overload runs use `target_mspt = 2.0` / `critical_mspt = 4.0` so a headless server
with no players genuinely exceeds its configured budget; every number below is measured.

**Healthy.** At 58 s of uptime the short-uptime line appeared with `avg 1m n/a`, and — this is the
case the guard exists for — the history p99 printed the same 18.40 as the 1 min p99, because both
were computed from the same samples. No "drops in the past" claim was made. After a session the
verdict stayed `no action`, with `Chunk operations, 0.23 ms/tick (85.40% of the tick)` as the main
cost of a 0.27 ms tick: dominant and irrelevant at the same time, which is why the recommendation
keys off the budget and not off the share.

**Block entities.** 16 040 hoppers in force-loaded chunks, mob spawning off:

```
Main cost: Block entities, 3.23 ms/tick (95.19% of the tick)
  minecraft:hopper: 2.57 ms/tick, 16040.00 instances/tick
Recommendation: reduce how many minecraft:hopper tick ...
Expected effect: at most 2.57 ms/tick, 75.51% of the measured 3.40 ms tick, and only if every one
of them stops ticking. MSPT is already under 50 ms, so this buys headroom against future load, not
more TPS.
```

The 0.66 ms between the category total and the hopper row is the walk over 16 040 ticker entries,
which belongs to no type and is charged to the enclosing frame's self time — the arithmetic the
frame stack was built for, visible in the field.

**Lithium makes 16 000 empty hoppers cost 0.11 ms.** The first attempt at this scenario, with
`lithium-fabric-0.15.4` installed, measured `BLOCK_ENTITIES 0.11 ms/tick (4.4%)` for the same
16 000 hoppers and no hopper row at all in the top-3 — the whole category was the ticker walk.
Lithium was moved out of `run/mods` for the run quoted above and put back afterwards. Worth
recording as a fact about what the numbers mean: on a server with Lithium, "thousands of hoppers"
is not automatically the answer.

**Chunk generation landed in `Other`, and the mod said so instead of guessing.** Force-loading
1024 fresh chunks produced a single **35 s** tick, and all of it was attributed to `OTHER`
(174.35 ms/tick over 201 profiled ticks, 92.54 %). That is correct, not a miss: vanilla's
`ForceLoadCommand` calls `getChunk(..., FULL, true)` per chunk, so the generation runs
synchronously inside command execution, which is outside the profiled `ServerChunkCache.tick`
region. The output recommended a JVM profiler and returned `Expected effect: unknown` rather than
attributing 35 seconds to a category that did not spend it. Whether generation driven the normal
way — a player walking into new terrain — lands in `CHUNK_OPS` instead is **not verified**. It is
expected to, because that path is driven by the chunk system's tick rather than by command
execution, but the same expectation was already wrong once about the forceload path, so it stays an
expectation until a run with a connected client shows otherwise. The `chunk_ops` recommendation
text is covered by unit tests only.

#### Not implemented / deferred

- **Deferred task count is `n/a`, not `0`.** The adaptive scheduler is FR-6 and arrives in Phase 7.
  Nothing defers anything yet, so there is no queue to count, and a zero would read as a measured
  empty one.
- **The mode shown is read, not settable.** `/tickpilot mode <strict|balanced|aggressive>` is part
  of FR-12 but belongs with the policies it controls (FR-11, Phase 8). `explain` reports
  `TickPilotConfig.effectiveMode()`, which is the real value including the `safe_compatibility_mode`
  override, and says when that override is what produced it. Changing it still means editing the
  config and running `/tickpilot reload`.
- **No coordinates in the recommendation.** Naming *where* the expensive block entities are would
  make the advice far more actionable, and needs the per-position buffer deferred in §13 entry #14.
- **The recommendation for `SCHEDULED_TICKS` cannot name a block.** That category has no per-type
  attribution, so the advice is a direction to look in rather than a culprit. Adding one needs a
  hook per scheduled tick, whose cost was judged not worth it in Phase 5.
- **The `chunk_ops` recommendation was never exercised on a live server**, and it is not known
  which category player-driven chunk generation actually lands in. Reproducing it needs a connected
  client walking into ungenerated terrain, which cannot be driven from a console script; the
  headless approach generates chunks inside command execution and lands in `OTHER`. Unit tests
  cover the branch, a live run does not. For the integration checklist, alongside the Phase 3
  vanilla `getTickTimesNanos()` cross-check that is deferred for the same reason.

### Phase 5 — category profiler and top-N (FR-2, FR-3, FR-4)

- `com.tickpilot.profiler.TickProfiler` — a frame stack that charges each region only its **self
  time**, `elapsed - childNanos`, and passes the full elapsed up to its parent. This is the whole
  point of the class: the three regions worth timing are nested inside one another, verified
  against the decompiled 1.21.1 sources —
  `tickNonPassenger` ⊃ `tickPassenger` (recursively), `tickBlockEntities` ⊃
  `BoundTickingBlockEntity.tick`, `ServerChunkCache.tick` ⊃ `ServerLevel.tickChunk` — and a plain
  start/stop pair around each would report a tick as costing more than it did, which AC-2 forbids.
  Sum of self times equals the outermost span by construction.
- Self-check counters (`droppedFrames`, `unbalancedEnds`, `abandonedFrames`, `overrunTicks`). If
  any is non-zero the report says the numbers are not trustworthy instead of printing them
  straight. Categories exceeding TOTAL is counted, not clamped away.
- **INV-6:** the four stack arrays plus the two accumulator arrays are allocated once in the
  constructor and reused for the life of the server.
  `TickProfilerTest.theFrameStackIsAllocatedOnceAndReused` reads them back through reflection
  after 100 ticks × 500 entities × 2 nesting levels and fails the build on reallocation.
- `com.tickpilot.profiler.CostTracker` — per-type aggregation for FR-3. One `long[2]` per type,
  created the first time that type is seen and updated in place after that; identity-keyed,
  because `EntityType` and `BlockEntityType` are registry singletons. Registry lookups and string
  building happen on the command path, never in the tick loop.
- **Seven Mixins**, all with the MX-2 Javadoc block, every target verified against
  `mappings.tiny` *and* `javap` on the jar the project compiles against:

  | Category | Target |
  |---|---|
  | ENTITIES | `ServerLevel.tickNonPassenger`, `ServerLevel.tickPassenger` |
  | BLOCK_ENTITIES | `Level.tickBlockEntities`, `LevelChunk$BoundTickingBlockEntity.tick` |
  | SCHEDULED_TICKS | `LevelTicks.tick(JILjava/util/function/BiConsumer;)V` |
  | RANDOM_TICKS | `ServerLevel.tickChunk` |
  | CHUNK_OPS | `ServerChunkCache.tick` |
  | SAVING | the `saveEverything` call site inside `MinecraftServer.tickServer` |
  | NETWORK | `ServerConnectionListener.tick` |

- **The block entity trap, found by reading the source rather than by guessing.** The obvious
  target for per-type timing is the `TickingBlockEntity` interface, and it is wrong:
  `LevelChunk.updateBlockEntityTicker` registers a `RebindableTickingBlockEntityWrapper`, whose
  `tick()` delegates to the `BoundTickingBlockEntity` it wraps. Every ticking block entity passes
  through **two** `TickingBlockEntity.tick()` frames per tick, so hooking the interface would have
  doubled every measurement, and would additionally have caught `NULL_TICKER`.
  `BoundTickingBlockEntity` is the leaf and the only one of the three that knows its block entity.
- **The entity recursion, corrected.** `tickNonPassenger` is *not* recursive — the recursion lives
  entirely in `tickPassenger` — and it has exactly one caller in the game, which skips any entity
  that has a vehicle. So the category needs no re-entrancy flag at all; only the per-type
  attribution needs the child subtraction, so a passenger is charged to its own type rather than
  to the boat.
- No new dependency. MixinExtras 0.5.4, used for the one `@WrapOperation`, is nested inside
  `fabric-loader-0.19.3.jar` and already on the compile classpath.
- `ProfilerHook` parks the active profiler for the duration of a tick and compares the calling
  thread on every call, so a singleplayer client running `ClientLevel.tickBlockEntities` on the
  render thread through the same Mixins cannot corrupt the server's stack (INV-1). Cleared at
  every tick end and at shutdown, so nothing survives a world (INV-7).
- Commands: `/tickpilot profile <1-300>`, `profile stop`, `top`, `top entities`,
  `top blockentities` (FR-12). Starting a second session is a message, not an exception (AC-4).
- 46 new unit tests, none of which launch Minecraft; suite total 160.
- **SPEC changes:** MX-3 now forbids `@Redirect` as well as `@Overwrite` (§13 entry #12);
  `RANDOM_TICKS` is displayed as "Chunk environment" because its only safe measurement point is
  wider than its name (§13 entry #13); AC-3 coordinates deferred (§13 entry #14).

#### The mod's own overhead (INV-10, FR-12)

- `com.tickpilot.metrics.OverheadMeter` — mean, peak and sample count of the time spent inside
  TickPilot's own code, printed by `/tickpilot status`. This is the SPEC §11 checklist item
  "measured and shown in `/tickpilot status`", and it is now actually measured.
- What is timed is the tick listener's own body, bracketed by two extra `nanoTime()` calls per
  tick. That is the right target because INV-10 caps the overhead *in default mode*, and in
  default mode there is no session: every Mixin hook is a static read and a null check with no
  clock call. Timing the per-entity hooks instead would need two clock calls per entity, and the
  measurement would cost more than the thing measured.
- Measured on a live dedicated server: **0.01 ms/tick, 0.11 % of MSPT** while the server was doing
  real work, rising to 0.34 % as the tick got cheaper. Comfortably inside the INV-10 cap.
- Two caveats are documented in the README rather than left to be discovered. The percentage is
  meaningless on an idle server — a 0.2 ms vanilla tick makes 0.01 ms read as 5 %, and the run
  above shows exactly that at 4.79 % once the world went quiet; the absolute figure is what does
  not move. And the peak slice is normally set during JIT warm-up in the first second (785 us
  observed), so it reads as "nothing pathological happened later", not as a typical cost.
- A running session's own cost is deliberately **not** in that number: a hook reads its timestamp
  before its bookkeeping, so the bookkeeping lands inside the category it measures. Deep profiling
  inflates the categories it reports, which is stated in the README and in `OverheadMeter`.

#### Verified under load, with Lithium installed

Real `lithium-fabric-0.15.4+mc1.21.1` from Modrinth in `run/mods/`, dedicated server, a real client
connected, ~400 zombies plus assorted farm animals, hoppers and furnaces around the player. Both
mods loaded with no Mixin or refmap error of any kind, and a 20 s session reported:

```
Profiling session finished: 402 ticks, 12.32 ms/tick total
  ENTITIES 64.4%  CHUNK_OPS 22.0%  SCHEDULED_TICKS 4.7%  NETWORK 3.8%
  RANDOM_TICKS 2.1%  OTHER 2.6%  BLOCK_ENTITIES 0.4%  SAVING 0.0%
```

The AC-2 check that an idle server cannot give: the eight categories sum to **100.00 %** of TOTAL
with `OTHER` at only 2.6 %, and every self-check counter stayed at zero — no double counting, no
overrun, nothing dropped. `RANDOM_TICKS` is non-zero here because chunk environment ticking needs
a player within spawning range, which is why it read 0.00 on the empty server earlier. The per-type
numbers reconcile with the category: the ten listed entity types add up to 7.88 ms/tick against an
`ENTITIES` total of 7.93, the remainder being the two types outside the top 10.

#### Not implemented / deferred

- **Coordinates for expensive block entities** — AC-3 also asks for positions kept in a bounded
  top-N buffer during a session. Only type-level aggregation is implemented. §13 entry #14.
- **Chunk sending to players is in `OTHER`** — deliberate, documented in the README and in
  `ServerConnectionListenerMixin`.
- **No load test beyond a single machine** — the Lithium run above is one server, one client, one
  mod. Behaviour in a hundred-mod pack is argued from Lithium's source, not measured.

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

#### Warm-up period (FR-5, AC-5, AC-16) — closing the Phase 3 deferral

- `TickBudget` now pins the level at NORMAL and reports no transition for the first
  `DEFAULT_WARMUP_MILLIS` (10 s) after it is created. The startup tick costs ~120 ms, and because
  the input is a 5 s average that single tick held the average above `critical` for the next five
  seconds — so every server start logged `NORMAL -> CRITICAL` and then recovered.
- Ten seconds is twice the smoothing window: five is the minimum for the spike to age out of the
  average, and the margin covers a start that is slow for more than one tick.
- **Measurement is untouched.** `TickMetrics` still records the slow tick and `status` still shows
  it as the max with its age. Only the *decision* waits. To make sure a pinned NORMAL is never
  mistaken for a measured one, `status` says so while it lasts, with a countdown, in the same
  spirit as the AC-1b `/tick freeze` line.
- A budget rebuilt for a config reload gets **zero** warm-up: a running server is already warm,
  and suppressing a real CRITICAL for ten seconds right after an operator edited the thresholds
  would hide exactly what they were looking for.
- Warm-up is latched once it ends, so a clock that steps backwards cannot re-enter it.
- 8 new `TickBudgetTest` cases and 2 in `TickPilotServerStateTest`, including the exact scenario
  that failed: 120 ms average from the first update must produce no transition.

Verified on a dedicated server over a 56 s run: no `Load level` line is logged at all, and
`status` counted down `8s` → `5s` → `2s` before the warm-up line disappeared. The same run before
the fix logged `NORMAL -> CRITICAL (avg MSPT 51.42)` one second after `Done`.

#### Not implemented / deferred

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

- ~~**False CRITICAL on server startup.**~~ **Fixed in Phase 4** — see "Warm-up period" in the
  Phase 4 section above. The diagnosis, for the record: the first tick after `Done (…)!` genuinely
  costs ~120 ms (measured: 117.37 ms on an idle dedicated server), so every start logged
  `Load level NORMAL -> CRITICAL` and then recovered to NORMAL about five seconds later. The
  reading was truthful but useless — nothing was overloaded, the server was still warming up, and
  one bogus critical line per start is exactly the noise AC-16 exists to prevent.
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
