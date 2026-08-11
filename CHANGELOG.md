# Changelog

All notable changes to TickPilot are recorded here. Requirement ids (`FR-*`, `AC-*`, `INV-*`)
refer to `SPEC.md`; decisions that deviate from it are logged in `SPEC.md` §13.

## Unreleased

## Not implemented in v1.0

The per-phase sections below each end with what that phase left undone, but most of those items
were picked up by a later phase. This is the list that is still true — the one to read if you are
deciding whether TickPilot does what you need. Every entry was re-checked against the source while
writing it, not carried over from an earlier list.

**Features that are diagnostics only**

- **Block entity throttling (FR-9).** Never implemented; `BlockEntityPolicy` counts and reports,
  nothing is skipped. Seven candidate types were read and all rejected: five keep their state in
  the ticker itself (`cookingProgress++`, `spawnDelay--`, `brewTime--`), so a skipped tick loses
  work instead of delaying it; `beacon` and `conduit` are gated on absolute game time, so a skip
  that lands on the gate loses the effect entirely; `chest` has no server ticker at all. §13 #18.
- **Registered `ThrottlePolicy` objects are consulted by nothing.** `TickPilotApi.registerPolicy`
  accepts and stores them, and `registerPolicy` logs that it does nothing yet, but no decision path
  reads them. A mod that registers one has not protected anything — use `throttle_denylist`.
- **`TaskProfile.asyncComputeAllowed` is stored and never read.** It is a declaration for a
  scheduler that does not exist. Nothing in TickPilot runs anything off the server thread.
- **The chunk budget caps nothing on a vanilla server.** Not a bug and not a stub: every vanilla
  chunk ticket type is player-critical by construction, so there is genuinely no optional chunk work
  to cap unless another mod is loading chunks. §13 #19.

**Data that is not collected**

- **No coordinates anywhere.** AC-3 asks for a bounded per-position buffer during a profiling
  session so `explain` can say *where* the expensive block entities are. Only per-type aggregation
  exists, so the advice is "this type is expensive", never "at these coordinates". §13 #14.
- **`SCHEDULED_TICKS` has no per-type attribution**, so its recommendation names a direction to look
  in rather than a culprit. Adding one needs a hook per scheduled tick, whose cost was judged not
  worth it in Phase 5.
- **Chunk sending to players lands in `OTHER`.** Deliberate, and documented in the README.
- **The MSPT underestimate is not quantified.** Measurement spans the body of the tick, not the few
  statements around it (§13 #8). The delta is constant and changes no conclusion, but nobody has
  measured it on a loaded server, so no number is printed.

**Interfaces that are narrower than the SPEC text**

- **`/tickpilot status` permission level is hard-wired to 0.** FR-12 says operator-settable; FR-15
  defines no key for it, and inventing one was judged worse than the limitation.
- **No off-thread submission path.** `TickPilotApi.submit` from another thread returns
  `WRONG_THREAD` and refuses, rather than queueing. §13 #16.
- **The client HUD is singleplayer only**, and no network protocol was added to change that.
- **The config parser is a documented subset of TOML.** §13 #10.

**Testing gaps**

- **No unit tests for client code.** `src/test` does not see the `client` source set, and adding a
  client test source set was judged not worth the build change for code whose real failure modes
  (main menu, world switch, F3) are only observable live. Verified by probe instead — see Phase 10.
- **The chunk budget's holding branch has never run on a live server**, because a vanilla server
  cannot produce work of the class it holds. Unit-tested only.
- **No load test beyond a single machine.** Every live run in this project is one server, one
  client, one box.

**Found in Phase 11: a deferral that was lost between sessions**

`/tickpilot mode` is now implemented, and it should not have taken this long. Phase 6 recorded it as
deferred "to Phase 8, with the policies it controls". Phase 8 came and went, the policies landed,
and the command did not — the note stayed in an old per-phase section that nobody re-read, and no
later phase carried it forward. It was caught only because Phase 11 walked the FR-12 command table
against the registered subcommands instead of trusting the phase history.

That is the failure mode this consolidated list exists to prevent: a per-phase "deferred" note is
written once and then ages out of sight, while a single list that has to be re-derived from the
source keeps the promise visible. If you are adding a phase, add its deferrals **here** as well.

### Phase 11 — documentation, audits and the mode command (FR-12, AC-11, FR-18, INV-7)

- **`/tickpilot mode <strict|balanced|aggressive>`** implemented, closing the FR-12 command table
  and the "by command as well as by config" half of AC-11. The mode lives in `TickPilotServerState`
  as a runtime override, applies from the tick it is set, and is dropped by `/tickpilot reload` so
  the file goes back to being the source of truth. Setting the mode the config already asks for
  clears the override rather than pinning it.
  - `safe_compatibility_mode = true` **cannot** be overridden by the command. It is the operator
    saying "this server runs no experiments", and a chat command that could defeat it would make
    the config file a lie. The command refuses with an explanation instead of silently doing
    nothing — FR-12 does not cover this case, so it is a judgement, recorded here as one.
  - Six existing consumers moved from `config().effectiveMode()` to `state.effectiveMode()`; a
    config snapshot cannot see a runtime override, so any of them left behind would have acted on
    a mode the operator had already changed.
  - Verified live: all four branches (report, set, set-again, refusal under
    `safe_compatibility_mode`) driven through a real server console. 7 new unit tests.
- **INV-7 audit, field by field.** Every non-final `static` field in `src/main` and `src/client` was
  listed and justified individually rather than by a blanket claim. It found two client fields that
  outlived the world that produced them: `HudRenderer.failed`, which meant a HUD that failed once in
  world A stayed off in world B until the game restarted, and `HudSampler.tickCounter`. Neither held
  a world reference, so neither was an INV-7 violation in substance — both are now cleared on
  `SERVER_STOPPING`/`SERVER_STOPPED` anyway, and the clearing method lists them so the audit stays
  honest.
- **Every Mixin target re-verified against the 1.21.1 mappings**, closing the last "partial" on the
  §11 checklist. Not a re-read of the Javadoc each phase wrote: the targets were extracted from the
  sources mechanically and each checked by descriptor with `javap -s` against the named jar. All 11
  resolve. Two things a name check alone would have missed were checked too — that
  `saveEverything(ZZZ)Z` really is invoked inside `tickServer`, and exactly once, so the
  `@At(target = ...)` injection point exists and needs no ordinal; and that the shadowed
  `BoundTickingBlockEntity.blockEntity` has the descriptor the Mixin declares.
- **README completed to all thirteen items of SPEC §10**, adding installation, adaptive modes,
  modpack compatibility, limits and conflicts, why arbitrary Minecraft code cannot run
  asynchronously, and how to compare with spark. Checked mechanically: every one of the 24 config
  keys appears in the settings table, and every internal link resolves to a heading that exists.
- **`TickProfiler.dominantCategory()`** and the consolidated not-implemented list above.

### Phase 10 — client HUD (FR-20, FR-18, AC-19)

An optional top-left readout of TPS, MSPT, mode, load level, deferred tasks and the main load
source. Off by default behind `client_hud_enabled`, and confined to `com.tickpilot.client`.

- **No client Mixin.** `HudRenderCallback` exists in fabric-rendering-v1 **5.1.0** — the version
  this project actually resolves, checked with `./gradlew dependencies` rather than assumed, since
  the Gradle cache also holds 8.x and 25.x — with the signature
  `onHudRender(GuiGraphics, DeltaTracker)`, verified by `javap` on the remapped jar. MX-1 satisfied,
  and `tickpilot.client.mixins.json` stays empty.
- **The snapshot is built on the integrated server's thread**, once every ten ticks, and published
  through one `volatile` reference to an immutable record of primitives. The render thread never
  reads the metrics themselves. Doing it the obvious way — the HUD reaching into
  `TickPilotServerState` per frame — would mean walking a 6000-entry ring buffer that the server
  thread is writing, at frame rate rather than tick rate, with a torn `long` read permitted by the
  memory model. It is also the literal reading of the phase requirement that the data come from the
  server's state rather than from rendering.
- The sampler deliberately does not call `TickPilotServerState.snapshot(long)`, which computes
  percentiles over the whole buffer. That is the right price for a command run a few times a
  session and the wrong one for something on a timer.
- **`TickProfiler.dominantCategory()`** extracted from the private half of `TickPilotCommand`, so
  the HUD and `/tickpilot explain` cannot drift into two copies of the "which category is the main
  cost" rule.
- `SideSeparationTest` scans `src/main/java` and fails on any mention of `net.minecraft.client` or
  `com.tickpilot.client`, turning FR-18 into a standing check instead of a one-off review.

#### The AC-19 clause Phase 2 deferred is now closed, and how

Phase 2 left the last clause of AC-19 — "when no integrated server exists, the client side does
nothing and throws nothing" — deliberately unimplemented, on the grounds that a check at
client-init time would be dead code. It is now implemented where it belongs, in the renderer, and
verified live in three steps rather than asserted:

1. **A client on a dedicated server** — no integrated server, so the in-game HUD renders every
   frame with a `null` snapshot. Forty-five seconds in-world: zero HUD failures, zero exceptions.
2. **A probe proving that was not a vacuous pass.** A one-shot throw inserted at the top of the
   callback produced exactly one `TickPilot HUD failed` line, which proves the callback is
   registered and firing every frame — and incidentally proves the AC-16 property that a failing
   HUD logs once and stops, rather than once per frame. Probe removed, absence re-checked by grep.
3. **A probe proving the opposite branch.** In singleplayer, a throw placed *after* the null check
   fired with the real published record:
   `HudSnapshot[tps=20.0, msptLast=11.7032, msptAvg5s=18.218175, loadLevel=NORMAL, mode=BALANCED,
   adaptiveEnabled=true, deferredQueued=0, dominant=null, ...]`. So the sampler publishes on the
   server thread, the `volatile` handoff works, and the render thread enters the draw path with
   real numbers. `dominant=null` is the honest FR-4 case: no profiling session, no main cost.

**Not verified: pixels.** There is no way to read the client's screen from here, so "the readout is
legible and in the right place" and the F3/F1 toggles remain manual checklist items in `README.md`.
The same goes for the world-A-to-world-B switch, which needs menu clicks; the clear is wired to
both `SERVER_STOPPING` and `SERVER_STOPPED`.

### Phase 9 — chunk budget (FR-10, AC-10, INV-8)

A cap on how much **optional** chunk generation may start per tick, off by default behind the new
`enable_chunk_budget` flag. The whole feature is built around one requirement it is not allowed to
break: a player must never sit on the terrain-loading screen because of TickPilot.

- `com.tickpilot.chunk.ChunkOpClass` is the SPEC AC-10 priority list, expressed as declaration
  order. Only the last two classes are optional; the first three are INV-8 written down, and there
  is no configuration or load level at which they can be refused — `ChunkBudgetTest` walks the
  whole input space to check that rather than sampling it.
- Classification uses the vanilla ticket types, which turn out to be exactly the protected classes:
  `START`, `DRAGON`, `PLAYER`, `FORCED`, `PORTAL`, `POST_TELEPORT`, `UNKNOWN`. `UNKNOWN` is treated
  as the **highest** priority, not the lowest its name suggests — it is the ticket
  `ServerChunkCache.getChunk` takes out while the server thread is blocked waiting for that chunk.
- The player radius is view distance **plus nine chunks**, not view distance. Full generation pulls
  in a neighbourhood of up to eight chunks around whatever it is generating, so a tighter radius
  would hold back the neighbours of the chunk a player is waiting for — INV-8 broken by arithmetic
  rather than by intent. Distance is Chebyshev, because Minecraft loads a square, not a disc.
- Nothing is dropped, cancelled or queued by TickPilot. Held tasks are moved out of vanilla's own
  `pendingGenerationTasks` list at the head of `runGenerationTasks` and put back at its return, at
  the front. The Phase 7 failure of a shared deadline flushing a whole batch into one tick cannot
  recur here because there is no deadline and no second queue.
- **Two emergency releases**, both logged once. Nothing dispatched for a second while chunk work
  waits — the signature of a blocked server thread — drops the cap for 30 s. So does the cap having
  been the binding constraint for 5 s, which is the window the load level itself is computed over.
- `/tickpilot status` reports the per-class breakdown, and says in words, in red, if anything
  player-critical was ever held. That counter is the pass condition of the manual teleport scenario
  now documented in `README.md`.
- Two Mixins, both `@Inject`: `ChunkMap.runGenerationTasks` (HEAD and RETURN) and
  `ServerChunkCache.addRegionTicket` (HEAD, read-only). The ticket layer was considered as the
  obvious place and rejected — see `SPEC.md` §13 entry #19.
- 21 new unit tests; suite total 305.
- `build.gradle` now runs the client out of `run/runclient`. Sharing `run/` with the server makes
  the two processes fight over `run/.fabric/processedMods`, and the second to start dies during mod
  remapping — which is exactly the pair the manual scenario needs running at once.

#### What was verified live, and what was not

**Verified on a live dedicated server** with a real client attached
(`runClient --args="--quickPlayMultiplayer localhost:25565"`, Lithium 0.15.4 on both sides),
configured for the worst case: `max_chunk_operations_per_tick = 1` and thresholds lowered to
`0.1 / 0.2` so the load level sat at CRITICAL and the cap really applied.

- Both Mixins applied (`defaultRequire: 1`, so a failed injection would have stopped the server).
- Four long-distance teleports — `10000 200 10000`, `-10000 200 -10000`, a cross-dimension jump
  into the Nether, and a return to the Overworld — plus a `/locate structure mansion`. Across all
  of them: **0 held in every class**, 0 of 7530 ticks capped, TPS 20.00, no emergency release, and
  no disconnect. The client was still in the world and receiving chat five minutes and four
  teleports later.
- The `POST_TELEPORT` protection is not theoretical: the first teleport classified 511 chunk
  generation starts, 31 of them via the teleport ticket. Those are chunks that would otherwise have
  been "far from every player" — the player's position had not moved yet when they were scheduled.
- The classifier reaches its background branch: one chunk operation in an emptied Nether was
  classified `world with no players in it`.

**Not verified live, and why.** The path where the cap actually *holds* work back was never
exercised on the running server, because a vanilla server cannot produce work of that class: every
vanilla ticket type lands in a protected class by construction. That is the same fact the README
warns about — on a server with no other chunk-loading mods this feature has nothing to cap. The
holding path, the priority ordering and both releases are covered by unit tests
(`ChunkBudgetTest`, `ChunkDrainPlanTest`); reading a `ChunkPos` off a live `ChunkGenerationTask` is
the one part covered by neither, which is why the manual scenario exists.

**Found by running it, not by reading it.** The first implementation parked its hook per tick, like
the profiler and policy hooks, and classified **nothing at all** during a 10 000-block teleport:
chunk generation is drained mostly from `MinecraftServer.pollTaskInternal`, outside the span the
Fabric tick events cover. The park now lasts as long as the server. The same measurement invalidated
the first emergency release, which counted consecutive empty drains — between two ticks an idle
server produces hundreds of those. It now measures elapsed time instead. `SPEC.md` §13 entry #20.

### Phase 8a — activity zones and throttling diagnostics (FR-7, FR-11, AC-7, AC-11)

The first half of the riskiest phase, and it deliberately changes nothing about what the game does.
Every candidate object is put through the real policy on the real tick, the verdict is counted, and
then discarded. Nothing is skipped, no control flow is touched, and `cancellable` appears nowhere.
That order is SPEC INV-3 applied to the work rather than only to a config flag: the half that skips
ticks starts from measured numbers instead of an expectation.

- `com.tickpilot.zones.ActivityZone` and `ZoneResolver` implement the FR-7 distance table. The
  answer is cached **per chunk per tick** — one hash and an array read in the hot path — because
  answering per object would cost one distance computation per player per object per tick.
- **Two approximations, both pointing the same way.** Distance is measured to the *nearest point of
  the chunk* rather than its centre, and only horizontally. Both under-estimate, so an object is
  never placed in a farther zone than it belongs to; a mob two hundred blocks below a player in the
  same column counts as FULL. Caching per chunk and honouring height are not compatible, and of the
  two directions only one is safe.
- **An empty world is not a frozen one** (AC-7). With no players every chunk answers FULL. "Nobody
  is near" and "nobody is here" are different facts, and confusing them stops every farm and chunk
  loader on the server the moment its owner logs off.
- `com.tickpilot.policy.TickPolicy` is the single decision, a pure function of seven values. The
  order of its checks is part of the contract because it decides what the diagnostics say, and the
  **load level is checked last** on purpose: "everything agrees except that the server is healthy"
  is the one count that says what thinning would actually buy.
- The verdict is an enum, not a record, so the reason travels with the decision at no allocation
  cost — it is produced once per object per tick (INV-6).
- Id lists are resolved into registry objects once per config load, so membership is a hash lookup
  rather than a string comparison in the hot path. An entry matching no registered type is now
  reported: an allowlist with a typo in it is inert, and an operator who is not told will believe
  they configured something they did not.
- The decision is taken **inside the profiler's own injector**, not in a second Mixin on the same
  instruction. Two injectors at one HEAD have no defined order between them, and once this call can
  cancel the tick, an order that opened the profiler's frame first would leave it unclosed.
- 29 new unit tests; suite total 258. Two of them walk the **whole** input space (288 combinations)
  rather than sampling it: `strictModeIsExhaustivelyInert` and
  `nothingIsEverEligibleWithoutTheOperatorsAllowlist`. INV-5 holds by construction — there is no
  path to an eligible verdict without the operator's allowlist.

#### What was verified live, and what was not

This distinction matters more than usual here, because the phase will later look fully verified
when half of its decision space has never run on a server.

**Verified on a live dedicated server** (`runServer` with Lithium 0.15.4 also installed):

- the hooks fire on every entity and block entity, and the counters produce real per-tick rates;
- **the FULL zone rule of AC-7** — a server with no players thinned nothing, and every object not
  otherwise protected reported "in the FULL zone";
- **the force-loaded protection of INV-8** — with nine chunks force-loaded, exactly the objects
  inside them reported as protected (2.40 entities and 0.82 block entities per tick);
- `0.00/tick would be eligible` on a default install, which is INV-5 and INV-3 on a real server
  rather than in a test;
- config reload rebuilding the id lists and the radii, and the tally being cleared with it;
- TickPilot's own overhead unchanged at 0.01 ms/tick with the diagnostics running on every object.

**Never run on a server, unit-tested only:** the **REDUCED and FROZEN zones**, the **allowlist
branch**, and the **load-level branch**. All three need a connected player — without one every
chunk is FULL by AC-7, so the other branches are unreachable from a console-driven run. There is no
client automation available here, so they are covered exhaustively by the 288-combination walk and
by nothing else. They stay on the integration checklist until a real client run happens; do not
read "phase 8 verified" as covering them.

**One correction made during the run.** The first version of the output named only the single
commonest reason, which reported "protected object" without saying *which* protection — a failure
of exactly the thing the diagnostics exist for. It now prints the full breakdown by reason, which
answered the question immediately (it was the force-load).

#### Type-by-type reading (SPEC FR-8, FR-9)

Every candidate was read in the decompiled 1.21.1 source and classified against three failure
modes, of which only the first was anticipated: **(A) counter drift**, **(B) missed window**, **(C)
no server ticker at all**.

Block entities — of seven types read, **none qualifies unconditionally**:

| Type | Mode | Evidence |
|---|---|---|
| `hopper` | A — refused | `cooldownTime--`, `HopperBlockEntity:96` |
| `furnace`, `blast_furnace`, `smoker` | A — refused | `litTime--:256`, `cookingProgress++:289` |
| `brewing_stand` | A — refused | `brewTime--:104`, `fuel--:114` |
| `campfire` | A — refused | `cookingProgress[i]++:51` |
| `mob_spawner` | A — refused | `spawnDelay--` in `BaseSpawner.serverTick`, already gated on `isNearPlayer` by vanilla |
| `beacon` | B — conditional | effects on `getGameTime() % 80 == 0`, `BeaconBlockEntity:167` |
| `conduit` | B — conditional | `getGameTime() % 40 == 0` |
| `chest` | C — not applicable | `ChestBlock.getTicker:303` returns `null` on the server; chests do not tick there at all |

Mode B is the one worth remembering: a ticker gated on **absolute** game time does not drift, but a
skip landing on the gate tick **loses** the work until the next multiple. Beacon effects would lapse
rather than arrive late.

Mobs — a structural finding that moves the whole group the *other* way: `serverAiStep()` is called
from `LivingEntity.aiStep()`, and breeding (`Animal.aiStep`, `inLove--`) and growth
(`AgeableMob.aiStep`, `setAge`) live in the caller. So skipping AI leaves age, breeding, physics,
`travel()`, fish flopping and squid propulsion running at full rate; only goals, navigation, sensing
and the movement controllers are thinned.

| Type | Verdict | Note |
|---|---|---|
| `pig`, `cow`, `sheep`, `chicken` | candidates | age and breeding provably untouched |
| `bat` | candidate | probabilistic logic, no counters |
| `squid`, `glow_squid` | candidate, visible behaviour | direction comes from a goal, propulsion from `aiStep`: a thinned squid **keeps swimming in a straight line** on its last heading |
| `cod`, `salmon`, `tropical_fish`, `pufferfish` | candidates | bucketed fish are persistent and so already protected |
| `villager` | refused without reading | brain, POI, trading, schedule — four independent reasons |

**Nothing has been written to the README and nothing has been added to the shipped allowlist**, which
stays empty. Per the agreed process a type only reaches the README after a live behavioural
comparison, which cannot happen until the half that actually skips ticks exists.

### Phase 8b — mob AI thinning (FR-8, AC-8)

- **The thinning step is a staggered grid**, SPEC §13 entry #17: an object runs when
  `(gameTime + phase) % interval == 0`, with the phase taken from the entity id. The obvious
  `gameTime % interval == 0` would put every thinned object on the same tick — four times the work
  on one tick in four — which is exactly the shape Phase 7 measured when 666 deferred tasks shared a
  deadline. Vanilla stages goal re-evaluation the same way inside the very method being hooked
  (`(tickCount + getId()) % 2`).
- **Block entity throttling is not implemented**, SPEC §13 entry #18. Of eight types read, five
  drift on a counter, one has no server ticker at all, and the two survivors (`beacon`, `conduit`)
  are gated on absolute game time and would need phase-alignment machinery whose only beneficiaries
  are two rare, cheap blocks. Diagnostics only, per INV-4.
- `MobServerAiStepMixin` cancels `Mob.serverAiStep()` at HEAD — the mod's **only** cancelling hook.
  It does nothing unless the operator both raises `min_entity_update_interval_ticks` above 1 and puts
  the type on `throttle_allowlist`, and STRICT disables it outright. A mob with an attack target is
  never thinned even then.
- 8 new unit tests; suite total 266.

#### Verified with a real client connected

Three headless attempts failed first, and the reason is worth recording because it will catch anyone
who tries to verify this from a console: on a server **with no players**, "ticked by vanilla" and
"eligible for thinning" are mutually exclusive. Force-loading a chunk keeps mobs ticking but makes
them protected under INV-8; without force-load the startup chunk ticket expires about a minute after
boot and vanilla stops ticking them entirely (the probe's `Age` froze while TickPilot reported
`0.00/tick actually skipped` — the freeze was vanilla's, not the mod's). Raising `spawnChunkRadius`
did not help either.

The way through is a real client, and it needs no GUI automation: the client is launched with
`--quickPlayMultiplayer localhost:25565`, joins by itself, and stands still. A stationary player
keeps everything within `simulation-distance` (10 chunks, 160 blocks) ticking, which is wider than
`reduced_radius` (96), so a mob summoned 120 blocks away is **ticked by vanilla and in the FROZEN
zone at the same time**. Lithium has to be moved out of `run/mods` for the run — server and client
race to remap it and the second one fails on a file lock.

Two runs, `min_entity_update_interval_ticks = 4`, `throttle_allowlist = ["minecraft:pig"]`, thresholds
lowered so the server held CRITICAL:

| Run | Eligible | AI reached schedule | Actually skipped | Expected (eligible × ¾) |
|---|---|---|---|---|
| A | 7.00/tick | 24.00/tick | **5.25/tick** | 5.25 |
| B | 4.00/tick | 9.92/tick | **2.99/tick** | 3.00 |

So the cancel path executes on a live server, and the staggered grid skips exactly three ticks in
four of the eligible mobs — twice, to the hundredth. Mobs that reached the schedule but were not
eligible (17 of 24 in run A) were untouched, which is the allowlist and the zone doing their job on
real objects. With the shipped default of `interval = 1` the `Mob AI` line does not appear at all:
nothing reaches the schedule, which is INV-3 observed rather than asserted. No crash, no Mixin
failure, TPS held at 20.

**Still not verified live: that growth and breeding are untouched.** The probe needed a baby pig and
the `{Age:-24000}` NBT did not take through the command chain in either attempt, so what was measured
was an adult pig's `Age` staying at 0 — which proves nothing. That breeding (`Animal.aiStep`) and
growth (`AgeableMob.aiStep`) sit in the *caller* of the hooked method remains a reading of the
decompiled source, not a measurement. It is the one claim behind the "passive animals are good
candidates" conclusion, so it stays on the integration checklist and no type is recommended in the
README on the strength of it.

### Phase 7 — public API and adaptive scheduler (FR-14, FR-6, AC-14, AC-6)

- **`com.tickpilot.api` is the published surface**: `TickPilotApi` with the six methods of FR-14,
  plus `TaskProfile`, `TaskPriority`, `SubmitResult`, `ThrottlePolicy`, `ThrottleAdvice`,
  `ServerLoad` and `TickPilotMetrics`. Everything public carries Javadoc.
- **AC-14 is enforced by a test, not by review.** `ApiSurfaceTest` walks every public and protected
  member of the package — return types, parameters, fields, thrown types, generic arguments, and
  implemented interfaces — and fails the build if any of them names a `com.tickpilot` class outside
  `api`. That is why `ServerLoad` and `TickPilotMetrics` exist as their own types instead of
  exposing `LoadLevel` and `TickMetricsSnapshot`: a consumer compiles against the API package alone.
  A second test keeps `ServerLoad` in step with `LoadLevel`, and the mapping between them is an
  exhaustive `switch`, so adding a level internally breaks the build rather than misreporting.
- **`com.tickpilot.scheduler.AdaptiveScheduler` implements all four guarantees of AC-6.** Bounded by
  `max_deferred_tasks`; drained by priority and, within a priority, in submission order; every task
  whose `maxDelayTicks` has elapsed runs on the next tick *before* the priority drain and
  *regardless* of the time budget; and an overflow drops the least urgent queued task for a more
  urgent submission, or refuses the submission outright, instead of growing.
- **Two indexes over the same entries, not a `PriorityQueue`.** An intrusive FIFO list per priority
  gives O(1) "next to run" and O(1) "least urgent, oldest" for the eviction victim; a binary
  min-heap keyed by (deadline, submission order) gives O(log n) "what has expired". Deadlines are
  not monotonic within a priority — a task submitted later with a shorter deadline expires earlier —
  so one ordering cannot serve both, and lazy deletion would leave dead entries piling up in a
  priority that is never drained. Each entry knows its own links and heap slot, so removal through
  one index removes it from the other. That is the difference between bounding live tasks and
  bounding memory.
- **Critical work never enters the queue.** It runs inside `submit`, so no later decision —
  priority, deadline, overflow — can apply to it. A test submits critical work into a queue that is
  completely full and asserts it still runs and that nothing was dropped.
- **`submit` off the server thread is refused, not helped** (`WRONG_THREAD`; §13 entry #16). The
  check runs before a single field of the scheduler is read, so the queue stays single-threaded by
  construction rather than by convention. Running the work there would breach INV-1/INV-2; queueing
  it would corrupt a structure that has no locks. The caller is told by a return value, never by an
  exception — an exception inside a tick would take the server down over another mod's mistake.
- **Nothing in the API ever throws at a consumer.** Contradictory profiles are normalised at
  construction (`critical` wins over `deferrable`, a negative deadline becomes "next tick", a
  deadline beyond 6000 ticks is clamped so the starvation guarantee cannot be opted out of), and a
  `null` argument is logged with a cooldown and ignored.
- **`coalescable` is implemented rather than stored.** A second submission of a queued coalescable
  task replaces its work and keeps the earlier position and deadline — the semantics of "dirty
  again", which is also why coalescing cannot starve anything.
- **STRICT defers nothing.** Holding another mod's work back is an intervention, and FR-11 says
  STRICT performs none, so every submission runs immediately there. Work queued before the switch
  still drains; a mode change never strands it.
- **The tick budget is `target_mspt − reserve_mspt` minus what the tick just cost.** Deferred work
  runs after the tick has been measured and after TickPilot's own overhead has been recorded, so it
  distorts neither: it is other mods' time, and charging it to the INV-10 figure would misreport
  both. It gets its own line in `status`.
- **Shutdown discards queued work rather than running it.** Executing other mods' tasks against a
  world being torn down is a worse answer than losing work that was, by its own profile, allowed to
  be late. Critical work never enters the queue and so cannot be lost this way. Said out loud in the
  API documentation and the README, not left to be discovered.
- `max_deferred_tasks` now applies on `/tickpilot reload` without a restart; lowering it drops the
  least urgent queued tasks immediately, because an operator lowering a bound is asking for it to
  hold now.
- Registrations live in `com.tickpilot.policy.PolicyRegistry`, which **deliberately outlives a
  world** — argued in full in §13 entry #15. Keyed by id, so re-registration replaces rather than
  accumulates; holds no reference to any game object; the state that does belong to a world, the
  queue, stays in `TickPilotServerState` and dies with it.
- `status` and `explain` print the queue: depth, cap, peak, how many ran, how many were forced by
  their deadline, and its cost in ms/tick, plus separate lines for dropped, refused and failed work.
  A server where no mod uses the API says so instead of printing zeros. **This closes the Phase 6
  item "Deferred task count is `n/a`, not `0`"** — there is a real queue to count now, and a zero
  there is a measured empty queue.
- 52 new unit tests (29 for the scheduler); suite total 229.

#### Verified on a dedicated server

Live `./gradlew runServer` runs with Lithium 0.15.4 also installed. The mod loads, `status` and
`explain` report the queue cap read from the config, `/tickpilot reload` goes through the new
reconfigure path without error, and the server stops cleanly.

All four AC-6 guarantees were then driven through the real tick listener with the real time budget,
not only through the synthetic clock of the unit tests. A throwaway `/tickpilot debugload <n>`
command stood in for the consumer mod that does not exist yet: it registered three profiles (HIGH
and NORMAL at a 600-tick deadline, LOW at 3) and submitted stub tasks costing about 1 ms each
through the real `TickPilotApi.submit`. It was **deleted before this commit** and is not part of
the mod; the numbers below are from its runs.

**Overflow, with `max_deferred_tasks = 100`.** 500 submissions returned `DEFERRED=183,
REJECTED_QUEUE_FULL=317`. Of the 183 accepted, 83 were later dropped to make room for more urgent
submissions and 100 ran. Peak queue depth was exactly 100 — the cap held under a flood four times
its size, which is the difference AC-6 is about. The overflow warning appeared once, at the first
drop, and the recovery line once, when the queue fell back below its mark.

**Both overflow answers were exercised.** LOW tasks were evicted by later HIGH ones (`dropped`),
and submissions with nothing less urgent to displace were refused outright (`rejected`) — the
refusal that keeps submission order meaningful under sustained pressure.

**Starvation protection, with the cap raised out of the way.** 2000 submissions, of which 666 were
LOW with a 3-tick deadline, made on scheduler tick 484. Every one of the 666 ran on tick **487** —
their deadline, exactly, not one earlier and not one later — while 1073 more urgent tasks were
still queued. The status line taken at that moment reads `1073 queued of 10000 max, peak 2000; 927
run so far (666 forced by their deadline)`: of the 927 that had run, 666 were forced LOW ones that
jumped a queue full of HIGH and NORMAL work with 600-tick deadlines. The whole 2000 drained in
about 3.5 s at roughly 29 tasks per tick, which is the 30 ms budget spending itself on 1 ms tasks.

**A consequence worth stating: forced work ignores the budget, so it can stall a tick.** When all
666 LOW tasks expired in the same tick they all ran in that tick — about 0.7 s of work — and the
next `status` showed TPS 17.95. That is AC-6 doing exactly what it says rather than a defect: the
deadline is chosen by the mod that submitted the work, and honouring it is the guarantee. The
lesson for a consumer is that a short `maxDelayTicks` on a large batch is a promise to run all of it
at once.

**And a second one: MSPT does not include deferred work.** Through that same stall MSPT read 0.06
and 0.23 ms, because the scheduler runs after the tick has been measured. The cost is not hidden —
it is on the scheduler's own line, which read 1.90 then 3.57 ms/tick — but an operator looking only
at MSPT would not see it. That is the deliberate trade described above: including it would charge
other mods' time to TickPilot's own overhead figure and let a mod's deferred work drive the load
level. TPS, which is wall-clock, does show it.

#### Not implemented / deferred

- **No registered `ThrottlePolicy` is consulted by anything.** Entity and block entity throttling is
  FR-8 and FR-9, Phase 8. The same is true of `markSafeToDefer` and `markSafeForAsyncCompute`: both
  are recorded, neither changes behaviour. This is stated in the Javadoc of each, in a log line
  written at registration, and in the README, so that "my policy does nothing" cannot be mistaken
  for a broken integration.
- **`asyncComputeAllowed` changes nothing and is not a promise that it ever will.** INV-1 forbids
  touching the world off the server thread at all, and separating a type's computation from its
  world access is not something a flag can do on its owner's behalf.
- **No consumer mod exists.** The live runs above used a throwaway command that was deleted before
  the commit, which covers everything except what only a real integration can show: coalescing and
  the wrong-thread refusal were not exercised live, and neither was the soft-dependency pattern in
  the README. Those remain unit-tested and reviewed only. For the integration checklist, alongside
  the two items deferred in Phase 6 for the same reason.
- **No off-thread submission path.** A background thread cannot hand work to TickPilot; it is
  refused with `WRONG_THREAD`. An MPSC inbox is neither in FR-6 nor in FR-14, and writing concurrent
  code at the end of a phase to serve a consumer that does not exist would be the wrong trade
  (§13 entry #16).

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
