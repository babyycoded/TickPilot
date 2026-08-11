# TickPilot

A server-side Fabric performance mod for Minecraft Java Edition 1.21.1 (Java 21).

**What it actually does:** it measures where your server's tick time goes, explains it in plain
language, and — only if you switch something on and say what it may touch — holds back a narrow
slice of optional work. It is a measuring instrument first. If you install it and change nothing,
it changes nothing.

**What it does not do:** it will not raise your TPS on its own, it does not rewrite the tick loop,
and it does not run game logic on other threads. See [Why not just make it multithreaded?](#why-not-just-make-it-multithreaded)
and [Limits and known conflicts](#limits-and-known-conflicts) before expecting otherwise.

**Measure first, explain second, throttle cautiously last.** On a default install TickPilot only
measures and reports. Exactly two things can change behaviour, and each needs you to switch it on
*and* say what it may touch: mob AI thinning, which needs types on `throttle_allowlist` and
`min_entity_update_interval_ticks` above 1, and the [chunk budget](#chunk-budget), which needs
`enable_chunk_budget = true`.

## Installation

Requires [Fabric Loader](https://fabricmc.net/use/installer/) 0.19.3 or newer, Fabric API, and
Java 21. TickPilot targets Minecraft **1.21.1** only.

### Singleplayer

1. Install Fabric Loader for 1.21.1.
2. Put `tickpilot-<version>.jar` and Fabric API into `.minecraft/mods`.
3. Start the game and open any world.
4. Run `/tickpilot status` and `/tickpilot explain` in chat. `status` needs no permissions; the
   rest need level 2, which you have in a singleplayer world with cheats on. Without cheats, only
   `status` is available.
5. `config/tickpilot.toml` is created inside your world's game directory on first run.

### Dedicated server

1. Put `tickpilot-<version>.jar` and Fabric API into the server's `mods` folder.
2. Start the server once — `config/tickpilot.toml` is written with commented defaults.
3. Give yourself operator level 2 (`op <name>` from the console), or run the commands from the
   server console, which always has full permission.
4. **No client-side mod is required.** Players do not need TickPilot installed, and nothing is sent
   to them. The optional [HUD](#client-hud) is the only client-side part, and it only works in
   singleplayer.

## TPS and MSPT

- **TPS** — ticks per second. The target is 20 and vanilla never exceeds it, so TPS tells you
  *that* the server is behind but not by how much.
- **MSPT** — milliseconds per tick. The budget is 50 ms. This is the number that matters: a
  server at 20 TPS and 45 ms MSPT is one bad chunk away from lagging, and TPS will not show it.

TickPilot treats MSPT as the primary metric and derives the load level from it.

A concrete consequence worth knowing before you read the output: **TPS cannot tell a quiet server
from a busy one that is still keeping up.** An empty world at 0.1 ms per tick and the same world
with sixty zombies at 0.4 ms per tick both report 20.00, because in both cases the server spends
the rest of the 50 ms waiting. TPS only starts moving once MSPT passes 50 ms, at which point you
are already lagging. MSPT tells you how much headroom is left; TPS tells you it is gone.

TickPilot measures TPS as the mean interval between ticks over the last five seconds, not as a
count of ticks inside a fixed window — a count is off by exactly one tick when the command runs
mid-tick, which reads as a permanent 19.80 on a perfectly healthy server.

## Commands

| Command | Permission | What it does |
|---|---|---|
| `/tickpilot status` | everyone | TPS, MSPT (last / 5 s / 1 min / 5 min averages, p95, p99, max), load level, uptime |
| `/tickpilot reload` | level 2 | Re-reads `config/tickpilot.toml` and reports what it accepted |
| `/tickpilot profile <1-300>` | level 2 | Runs a profiling session for that many seconds |
| `/tickpilot profile stop` | level 2 | Ends the session early and prints the breakdown |
| `/tickpilot top` | level 2 | Tick cost split by category |
| `/tickpilot top entities` | level 2 | Costliest entity types, and the mods they belong to |
| `/tickpilot top blockentities` | level 2 | Costliest block entity types |
| `/tickpilot explain` | level 2 | All of the above in one plain-language readout, plus one recommendation |
| `/tickpilot mode` | level 2 | Reports the intervention mode in force and where it came from |
| `/tickpilot mode <strict\|balanced\|aggressive>` | level 2 | Changes it, from this tick, without a restart |

Example output:

```
TickPilot - 14203 ticks measured, uptime 11m 50s
TPS: 20.00
MSPT: last 8.41, avg 5s 9.02, 1m 8.77, 5m 8.61
MSPT: p95 14.30, p99 22.85 (last 1m)
MSPT: max 61.20, 3m 12s ago; p95 15.02, p99 40.11 (history: 5m 00s)
Load level: NORMAL (target 40.00 / high 45.00 / critical 50.00 ms)
```

`/tickpilot mode` says what is in force and, when they differ, why that is not what the config file
says:

```
> /tickpilot mode
Adaptive mode: BALANCED

> /tickpilot mode aggressive
Adaptive mode set to AGGRESSIVE; it applies from this tick and lasts until the server stops or the config is reloaded
Adaptive mode: AGGRESSIVE
  set by command; the config says BALANCED, and /tickpilot reload puts it back

> /tickpilot mode aggressive
Adaptive mode is already AGGRESSIVE; nothing changed
```

With `safe_compatibility_mode = true` the command refuses rather than silently doing nothing:

```
> /tickpilot mode aggressive
safe_compatibility_mode = true pins this server to STRICT. Change it in the config and run /tickpilot reload if you really want to intervene
```

### Reading the two percentile lines

They answer different questions and will disagree, on purpose.

The **first line** covers the last minute: this is how the server is behaving *now*. The
**second** covers everything still in the ring buffer — about five minutes at 20 TPS — and is how
bad it has been *lately*, together with the single worst tick and how long ago it happened.

A server that has just finished generating its spawn chunks shows something like `p95 0.20 (last
1m)` next to `max 207.89, 1m 45s ago`. Both are true: the burst is over, and it is still recent
enough to be worth explaining. One window alone cannot say that — whole-history percentiles keep
a recovered server looking broken for five minutes, and a short window alone loses the outlier
entirely.

The span in brackets is what the buffer actually holds, not a nominal figure. A server up for
forty seconds says `(last 40s)`, because the buffer is bounded by sample count and five minutes
of it only exists at a full 20 TPS.

The three averages on the line above keep their fixed names, so a window the server has not been
up long enough to fill reads `n/a` rather than quietly averaging less time than it claims:

```
MSPT: last 0.12, avg 5s 0.10, 1m 0.88, 5m n/a
```

## Where the tick goes

Deep profiling is **off by default** and runs only for as long as you ask for it, because the
measurement itself is not free: with no session running, each hook costs one field read and a null
check, and in particular no `System.nanoTime()` call.

```
/tickpilot profile 20
... twenty seconds later, in the server log ...
Profiling session finished: 402 ticks, 12.32 ms/tick total
  ENTITIES: 7.93 ms/tick (64.4%)      CHUNK_OPS: 2.71 ms/tick (22.0%)
  SCHEDULED_TICKS: 0.57 ms/tick (4.7%)  NETWORK: 0.47 ms/tick (3.8%)
  RANDOM_TICKS: 0.26 ms/tick (2.1%)     BLOCK_ENTITIES: 0.05 ms/tick (0.4%)
  SAVING: 0.00 ms/tick (0.0%)           OTHER: 0.33 ms/tick (2.6%)
```

```
/tickpilot top entities
Top Entities over 10 of 12 types, 402 ticks:
  minecraft:zombie: 6.39 ms/tick, 377.00 instances, 17.0 us each
  minecraft:item: 0.32 ms/tick, 93.18 instances, 3.4 us each
  minecraft:turtle: 0.10 ms/tick, 2.24 instances, 46.7 us each
```

Three numbers per row, because the total alone misleads: 377 zombies at 17 microseconds each cost
far more than two turtles at 47, but it is the turtle that is expensive *per animal*. The instance
count is instances actually ticked per tick, not instances loaded.

### Reading the categories honestly

**`Other` is not a category, it is a remainder** — total tick time minus everything measured. A
large `Other` means the mod is not measuring where your time goes, not that the time is cheap.

**A category with no injection point prints `n/a`, never `0.00`.** Zero would claim the work
happened and cost nothing; `n/a` says the mod cannot see it.

**`Chunk environment` is wider than "random ticks".** The only safe measurement point covers
lightning, ice and snow, precipitation *and* the random block ticks in one span. Splitting it
would need a timer around every block, which would cost more than the thing being measured.

**Nested work is never counted twice.** A passenger's time is inside its vehicle's; a block
entity's is inside the block entity phase; the per-chunk environment ticking is inside chunk
operations. Each frame reports only what it spent outside its children, so the categories sum to
the real tick instead of overshooting it. If that arithmetic ever fails, the output says so in red
rather than printing a plausible lie.

**Chunk sending to players is not in `Network`.** It happens per player at the server level while
`Network` is the connection tick, and folding one into the other would make the numbers
unpredictable. It sits in `Other`.

## Explaining a drop

`/tickpilot explain` is the same measurements as `status` and `top`, read out in the order a person
would ask for them, ending in **one** recommendation and an estimate of what acting on it would
buy. It is meant for the moment when somebody says "the server is lagging" and you have thirty
seconds to say something useful.

### The rules the wording follows

**One recommendation, not a list.** A list of eight things to try is a way of not having an
opinion. If the evidence supports two, the mod says the one it can support best.

**An effect estimate carries a number only when the number is an upper bound from a measurement.**
The quantified form is always "at most X ms/tick, and only if every one of them stops ticking",
because the cost per instance was never measured and "remove half, save half" does not follow from
anything. Everything else says *unknown*, which AC-13 explicitly allows and which is the honest
answer for chunk generation, redstone, network and `Other`.

**MSPT and TPS are never conflated.** Below 50 ms/tick a server is already at 20 TPS, so removing
work there buys headroom against future load and no TPS at all. The estimate says which of the two
it is, instead of implying the more impressive one.

**Missing data is stated, not filled in.** With no profiling session there is no category
breakdown — one line saying so and a recommendation to run a session, not a plausible guess. The
deferred-task line says "none — no mod has submitted work" on a server where the API is unused,
rather than printing a row of zeros: nobody submitting anything and the queue keeping up are
different facts, and only the second one is about performance.

**A short uptime narrows the claim, it does not silence it.** Under a minute of measurements the
output says what window it actually covers. It still gives the verdict: a server dying thirty
seconds after a start really is dying, and refusing to look at it would be a worse failure than
answering carefully.

### Drops now versus drops earlier

The two percentile windows are used for exactly what they were introduced for. A p99 above the
critical threshold in the **last minute** means it is happening right now, and the recommendation
is to profile *while it is happening*. A clean last minute next to a bad **history** p99 means it
already stopped, and the recommendation is to wait and catch the next one rather than profile an
idle server.

That second claim is only made when the retained history is genuinely longer than a minute —
otherwise both pairs are computed from the same samples, and calling one of them "the past" would
be an artefact of the window rather than a finding. It also keys off the history p99 and not the
maximum, because the slowest tick on almost any server is its own startup tick at ~120 ms; that one
is printed with its age on its own line and does not need to become a diagnosis.

## Configuration

`config/tickpilot.toml` is created with commented defaults the first time the server starts, and
`/tickpilot reload` applies changes without a restart.

### Every setting

| Key | Default | What it means |
|---|---|---|
| `target_mspt` | `40.0` | Milliseconds per tick above which the load level leaves NORMAL. The budget you are aiming to stay inside, not the point at which you lag. |
| `critical_mspt` | `50.0` | Where the load level becomes CRITICAL. 50 ms is one tick at 20 TPS, so at this point TPS is already falling. Must be above `target_mspt`. |
| `reserve_mspt` | `10.0` | How much of the tick the scheduler keeps free for the game. Deferred work only runs in `target_mspt - reserve_mspt` minus what the tick already cost. May be 0. |
| `full_radius` | `32` | Blocks. Inside this distance from any player, nothing is ever thinned. |
| `reduced_radius` | `96` | Blocks. Beyond this, the most thinning is allowed. Must be above `full_radius`, or the REDUCED zone would be an empty band. |
| `min_entity_update_interval_ticks` | `1` | Run an allowlisted mob's AI step one tick in N. **`1` means no thinning at all**, which is the default. |
| `min_block_entity_update_interval_ticks` | `1` | Reserved. Block entity thinning is not implemented — see [Limits](#limits-and-known-conflicts). |
| `enable_adaptive_mode` | `true` | The master switch for every intervention. `false` makes the mod measurement-only whatever else is set. |
| `default_mode` | `"BALANCED"` | `STRICT`, `BALANCED` or `AGGRESSIVE` — see [Modes](#adaptive-modes). Changeable at runtime with `/tickpilot mode`. |
| `max_deferred_tasks` | `10000` | Hard cap on the queue of work other mods submitted through the API. At the cap, work is dropped by priority rather than queued forever. |
| `enable_chunk_budget` | `false` | Switches on the [chunk budget](#chunk-budget). Off by default because it reorders chunk loading. |
| `max_chunk_operations_per_tick` | `8` | How many *optional* chunk generation starts are allowed per tick. Ignored unless the key above is `true`. |
| `profile_buffer_size` | `1200` | How many samples a profiling session keeps. |
| `log_slow_operations_above_ms` | `2.0` | Threshold for reporting a slow operation. Must be above 0 — 0 would mean logging every tick, which the mod does not do. |
| `sampling_enabled` | `false` | Start deep profiling from the first tick instead of waiting for `/tickpilot profile`. Costs a little on every tick; leave it off unless you are chasing a start-up problem. |
| `singleplayer_enabled` | `true` | Whether the mod runs at all on an integrated server. |
| `client_hud_enabled` | `false` | The optional [HUD](#client-hud). Singleplayer only. |
| `integrated_server_optimizations` | `true` | Whether interventions apply on an integrated server as well as a dedicated one. |
| `safe_compatibility_mode` | `false` | `true` forces `STRICT` regardless of `default_mode`, and `/tickpilot mode` cannot override it. Set this if you want a guarantee in writing that the mod touches nothing. |

And the `[lists]` table, all empty by default:

| Key | What it means |
|---|---|
| `excluded_entity_ids` | Entity ids TickPilot ignores completely, e.g. `"minecraft:villager"`. |
| `excluded_block_entity_ids` | Block entity ids to ignore. |
| `excluded_mod_ids` | Whole namespaces to ignore, e.g. `"create"`. |
| `throttle_allowlist` | **The only types that may ever be thinned.** Empty means nothing is, which is the default and the point of INV-5. |
| `throttle_denylist` | Types never touched. Outranks the allowlist. |

### The rules the loader follows

**Your file is never rewritten.** It is written exactly once — when it does not exist. A file that
fails to parse is the one you need to read to find your mistake, so overwriting it with defaults
would be the worst possible response. The mod logs the line number and carries on.

**A bad value costs you that value, not the file.** Each setting is validated on its own: an
impossible `full_radius` falls back to 32 and is reported, while every other setting you edited is
still applied. A parse error is different — a file that is not valid syntax cannot be trusted
line by line, so everything falls back to defaults and the log says so.

**Nothing here can stop the server.** A config problem is a warning in the log and a message in
chat, never a crash.

What that looks like when three values are wrong and one key is misspelled:

```
[tickpilot] Loaded tickpilot.toml with 4 rejected value(s); the default is used for each of them
[tickpilot]   critical_mspt (20.0) must be greater than target_mspt (45.0); using the default 50.0
[tickpilot]   full_radius (line 27): must be between 1 and 2147483647, got -1; using 32
[tickpilot]   sampling_enabled (line 46): expected true or false, got "yes"; using false
[tickpilot]   unknown key 'max_deferrd_tasks' (line 37); ignoring it
```

Two settings are validated as a pair, because either one alone can be perfectly sensible while
the combination is not: `critical_mspt` must be above `target_mspt`, and `reduced_radius` above
`full_radius`. `critical == target` would leave the ELEVATED and HIGH load levels as empty bands
that can never be reached, and `reduced <= full` does the same to the REDUCED activity zone. When
a pair is inverted the loader repairs the smallest part of it that it can, and only resets both
when keeping your value would still leave the pair inverted.

### The supported TOML subset

TickPilot parses TOML itself rather than bundling a parser. Fabric ships no TOML library, so a
dependency would have to be nested inside the mod jar — a few hundred kilobytes and a version
conflict waiting to happen in a large modpack, in return for reading a twenty-line config file.
The trade is that the parser covers what the schema uses and nothing more:

- `key = value` and one `[lists]` table;
- integers, floats, `true` / `false`, `"double-quoted strings"`, and arrays of them;
- `#` comments and blank lines anywhere; arrays may span lines.

Valid TOML that is **not** supported: `'single-quoted'` strings, `"""multi-line"""` strings,
inline tables (`{ a = 1 }`), dotted keys (`a.b = 1`), arrays of tables, dates and times, and
hexadecimal or binary integers. Using one of these is treated the same as a syntax error: the mod
names the construct and the line, keeps your file, and runs on defaults.

## Load levels

Computed from the 5 s average MSPT, with the thresholds from `config/tickpilot.toml`. With the
default budget (target 40 ms, critical 50 ms):

| Level | Average MSPT |
|---|---|
| NORMAL | below 40 ms |
| ELEVATED | 40–45 ms |
| HIGH | 45–50 ms |
| CRITICAL | 50 ms and above |

The level does not chatter on a boundary: leaving a level downwards requires the average to fall
a margin below the threshold *and* the level to have been held for at least five seconds.
Escalation is immediate, because the input is already a five-second average.

### Warm-up

For the first ten seconds after the server starts, the load level is pinned at NORMAL and no
transition is logged. The first tick of a starting server genuinely costs on the order of a
hundred milliseconds, and since the level is driven by a five-second average, that one tick used
to drag the average above `critical_mspt` and make every single start log a CRITICAL it then
recovered from.

The measurements are not touched — the slow tick is still recorded, and `status` still shows it as
the max with its age. Only the *decision* waits until the averaging window is made of ticks from a
server that has finished starting. While that is happening `status` says so, so a pinned NORMAL is
never mistaken for a measured one:

```
Load level: NORMAL (target 40.00 / high 45.00 / critical 50.00 ms)
Server is warming up - the load level is held at NORMAL for another 5s; the MSPT numbers above are real
```

The trade is deliberate: a server that is genuinely overloaded from its first tick reports NORMAL
for ten seconds. Nothing can tell those two cases apart that early, the real MSPT is on screen the
whole time, and one false CRITICAL per restart is the more expensive mistake. A reload that
changes the thresholds does **not** start another warm-up — the server is already running.

Load level is **not** the same thing as the adaptive mode (STRICT / BALANCED / AGGRESSIVE). The
level says how bad things are and is computed; the mode says how far the mod may intervene and is
chosen by you in `default_mode`. Nothing acts on the mode yet — the policies it governs arrive in
a later phase — so today it is validated and stored, and that is all.

Changing `target_mspt` or `critical_mspt` and running `/tickpilot reload` rebuilds the thresholds
and resets the level to NORMAL; it settles on the true level within about five seconds. A reload
that leaves both thresholds alone does not disturb the level.

## Chunk budget

Off by default. `enable_chunk_budget = true` caps how much **optional** chunk generation may start
per tick, at `max_chunk_operations_per_tick`.

Every chunk the server starts loading or generating is put into one of five classes, and only the
last two can ever be capped:

| # | Class | Capped? | What it is |
|---|---|---|---|
| 1 | needed by a player now | never | within view distance of a player, or covered by a vanilla `PLAYER`, `DRAGON` or `UNKNOWN` chunk ticket |
| 2 | teleport, portal or world start | never | covered by a `POST_TELEPORT`, `PORTAL` or `START` ticket |
| 3 | force-loaded region | never | inside `/forceload` |
| 4 | far from every player | yes | a world with players in it, but none near this chunk |
| 5 | world with no players in it | yes, first | background loading |

Nothing is ever dropped or cancelled. A chunk that is not allowed to start this tick starts on a
later one, at the front of the queue — the mod keeps no queue of its own, it just leaves work in
the list Minecraft already has.

### Read this before turning it on: on a vanilla server it does nothing

The vanilla chunk ticket types are, in full, `START`, `DRAGON`, `PLAYER`, `FORCED`, `PORTAL`,
`POST_TELEPORT` and `UNKNOWN` — and every one of them lands in class 1, 2 or 3. That is not a
coincidence, it is the point: vanilla only loads chunks somebody needs. So on a server with no
other chunk-loading mods, the cap has nothing to cap, and `/tickpilot status` will keep saying so.

What it is for is the case where something *else* is loading chunks: a pregenerator such as Chunky,
a world scanner, a mod that keeps distant machinery loaded with a ticket of its own. Those use
their own ticket types, which is what puts them in class 4 or 5. If you are not running one,
leave this off.

### The two ways the cap lets go

Both are logged once, and both exist because a mod that delays chunk loading is one bug away from
a player stuck on the terrain-loading screen forever:

- **Nothing dispatched for a second.** `ServerChunkCache.getChunk` blocks the server thread until
  the chunk it wants is ready. If a whole second passes with chunk work waiting and nothing at all
  being let through, the cap is dropped for 30 seconds. A server that is still ticking dispatches
  something every tick, so this cannot fire because the server is merely busy.
- **The cap has been the binding constraint for five seconds.** Five seconds is the window the load
  level itself is computed over. If the cap has held work back on every tick for that long, then
  what is shaping the server is the cap and not the load, so it stands down for 30 seconds.

### What `status` shows

```
Chunk budget: cap 1 per tick, 1128 chunk generation start(s) let through, 0 held for a later tick (0 of 5309 ticks were capped)
    needed by a player now - 1032 let through, 0 held
    teleport, portal or world start - 96 let through, 0 held
```

That is real output, from one player and four long-distance teleports on a server deliberately set
to the harshest cap there is. Every start was player-critical and nothing was held — which is what
the section above means by "on a vanilla server it does nothing".

The number to look at is the second one on each row. `0 held` on the first three rows is the
guarantee this feature has to keep, and if it is ever not zero `status` says so in words, in red.

With the feature off, which is the default, the line reads:

```
Chunk budget: off (enable_chunk_budget = false) - chunk loading runs exactly as it would without TickPilot
```

### Manual check: does a 10 000-block teleport still work?

Unit tests cover the classifier; this checks the whole thing on a running server, and it is worth
doing after any change to the chunk code.

1. In `config/tickpilot.toml`, set the worst case you can:

   ```toml
   target_mspt = 0.1          # forces the load level to CRITICAL, so the cap really applies
   critical_mspt = 0.2
   enable_chunk_budget = true
   max_chunk_operations_per_tick = 1
   ```

2. Start the server, join it, and wait about fifteen seconds — for the first ten the load level is
   pinned at NORMAL by the warm-up and the cap is not applying yet. `/tickpilot status` should say
   `Load level: CRITICAL` and `Chunk budget: cap 1 per tick`.
3. Note the time, then teleport far enough that the destination has certainly never been generated:

   ```
   /tp <player> 10000 200 10000
   ```

4. The terrain-loading screen should clear in about the same time it takes without the mod, and the
   world around you should fill in normally. Fly around for a few seconds and look for holes.
5. Run `/tickpilot status` again and read the class rows. The pass condition is exact:
   **`held` must be 0 on the first three rows.** Those are the chunks a player was waiting for.
   Non-zero `held` on rows 4 and 5 is expected and fine — that is the cap doing its job.
6. Repeat with `/tp <player> -10000 200 -10000`, and again into the Nether
   (`/execute in minecraft:the_nether run tp <player> 2000 100 2000`) so a portal ticket and a
   cross-dimension teleport are covered too.

If the loading screen ever hangs, check the server log for `Chunk budget lifted` — that line means
the emergency release fired, which is the mod telling you it caught itself. Set
`enable_chunk_budget = false`, run `/tickpilot reload`, and please report it.

## Client HUD

Off by default. `client_hud_enabled = true` draws a small readout in the top-left corner:

```
TickPilot  TPS 20.00  MSPT 11.70 (5s 18.22)
  mode BALANCED, load NORMAL
  deferred tasks: 0
  main cost: n/a - run /tickpilot profile 30 to measure it
```

It shows what SPEC FR-20 asks for: TPS, MSPT, the intervention mode, the load level, the number of
deferred tasks, and the main source of load.

**Singleplayer only, and that is not a limitation to be fixed.** The numbers come from the
integrated server's own state. A client connected to somebody else's server has no integrated
server, so it has nothing to read and draws nothing — TickPilot sends no packets and adds no
protocol. If you want these numbers on a remote server, run `/tickpilot status` there.

**The main cost line is usually `n/a`, and honestly so.** Category profiling is a sampling session
(FR-4), not something that runs all the time, so outside a session there is no measured breakdown
to show. Run `/tickpilot profile 30` and the line fills in while the session lasts. It is left
blank rather than filled with the categories that happen to read zero.

The HUD hides itself when the F3 debug screen is up — that corner belongs to vanilla — and when the
GUI is hidden with F1. `client_hud_enabled` is live: `/tickpilot reload` turns it on and off without
restarting the game.

### How it stays off the server's back

The snapshot is built **on the integrated server's thread**, once every ten ticks, and handed to
the renderer through a single `volatile` reference to an immutable record. The render thread never
touches the metrics themselves. That matters for two reasons: reading a six-thousand-entry ring
buffer from another thread while the server writes it is a torn read waiting to happen, and doing
it per frame would make the cost scale with your frame rate instead of the tick rate.

Nothing client-side exists on a dedicated server. All of it lives in `com.tickpilot.client`, which
Fabric only loads on a client, and no common class is allowed to name it — that is checked by a
test on every build, not by review.

### Manual check

1. Set `client_hud_enabled = true` in the config of the world you are loading and start the game.
2. In a singleplayer world the readout appears top-left with live numbers.
3. Press F3 — it disappears; release F3 — it comes back. Same with F1.
4. Leave to the main menu — nothing is drawn and nothing lingers. Load a different world — the
   numbers start from that world, not from the previous one.
5. Join a multiplayer server — nothing is drawn, and nothing is logged.

## Adaptive modes

The mode says how far TickPilot may go; the [load level](#load-levels) says how bad things are.
They are different things and both are shown by `/tickpilot status`.

| Mode | What it does | Acts at load level |
|---|---|---|
| `STRICT` | Measures only. No intervention of any kind. This is the compatibility mode — run it if you suspect TickPilot of causing a problem. | never |
| `BALANCED` | The default. Intervenes only for types you put on `throttle_allowlist`, and only when the server is actually struggling. | HIGH, CRITICAL |
| `AGGRESSIVE` | Starts intervening one level earlier and thins harder — still only within your allowlist. | ELEVATED, HIGH, CRITICAL |

No mode ever intervenes at NORMAL. A server inside its budget has nothing to gain, and thinning a
healthy server would be a change nobody asked for.

Set it with `/tickpilot mode <strict|balanced|aggressive>` or with `default_mode` in the config.
A mode set by command lasts until the server stops or you run `/tickpilot reload`, which puts the
config back in charge. `safe_compatibility_mode = true` pins the server to STRICT and the command
will refuse to override it, because a chat command that could defeat that setting would make the
config file a lie.

## Compatibility with large modpacks

This is the environment TickPilot exists for, so here is the honest picture rather than a
reassurance.

**What has actually been tested.** Every live check in this project's development ran with
**Lithium 0.15.4** installed on both server and client. No conflict was observed, on either side,
across chunk generation, entity ticking, block entity ticking and the client HUD. That is one mod,
not a modpack.

**Why conflicts are unlikely by construction.** The mod uses eleven Mixins, all of them `@Inject`,
and **no `@Overwrite` and no `@Redirect`**. That is not a stylistic preference — `@Redirect` is
exclusive per instruction, so two mods redirecting the same call are hard-incompatible and Mixin
fails at apply time rather than degrading. Lithium uses `@Redirect` in exactly the methods
TickPilot needs, for instance in `Level.tickBlockEntities()`. Because TickPilot only injects, the
two coexist.

**Where a real conflict could still come from**, in rough order of likelihood:

- **Another mod that reorders chunk loading** — a pregenerator, a chunk-loading mod, or another
  performance mod with its own chunk budget. They will not crash each other, but two schedulers
  making independent decisions about the same queue is a good way to get behaviour neither author
  predicted. If you run one, leave `enable_chunk_budget = false`.
- **A mod that replaces the tick loop wholesale.** TickPilot measures through Fabric's tick events;
  a mod that bypasses `MinecraftServer.tickServer` would make the measurements describe something
  other than the real tick.
- **A mod whose mobs depend on `Mob.serverAiStep` running every tick.** Thinning is opt-in per type
  through `throttle_allowlist`, so this only bites if you put such a type on the list yourself.
  When in doubt, do not.
- **Mixin version skew.** TickPilot uses MixinExtras 0.5.4, which ships inside Fabric Loader; it
  adds no dependency of its own and bundles nothing.

**If you suspect TickPilot**, the fastest test is `/tickpilot mode strict` — it disables every
intervention immediately, without a restart, while leaving the measurements running. If the problem
survives STRICT, it is not TickPilot.

## Limits and known conflicts

- **The chunk budget does nothing on a vanilla server.** See [Chunk budget](#chunk-budget): every
  vanilla chunk ticket type is player-critical by construction, so there is nothing optional to cap
  unless another mod is loading chunks.
- **Block entity throttling is not implemented at all.** Only diagnostics. The types worth thinning
  turned out to keep their state in the ticker itself (`cookingProgress++`, `spawnDelay--`), so a
  skipped tick loses work rather than delaying it. `CHANGELOG.md` lists the seven types examined.
- **The main cost breakdown needs a profiling session.** Outside `/tickpilot profile`, `explain`
  and the HUD say `n/a` rather than inventing a number from categories that read zero.
- **No coordinates in recommendations.** `explain` can tell you *which type* is expensive, not
  *where* it is. That needs a bounded per-position buffer that has not been built.
- **MSPT reads slightly low** against vanilla's own tick timer — see
  [How tick time is measured](#how-tick-time-is-measured-and-what-that-misses).
- **The client HUD is singleplayer-only**, and TickPilot adds no network protocol to change that.
- **The config parser is a TOML subset.** See [The supported TOML subset](#the-supported-toml-subset).

## Why not just make it multithreaded?

This is the single most common idea for "making a server faster", and it is the one thing this mod
will not do. It is worth being concrete about why.

Minecraft's server is single-threaded by design, and almost nothing in it is written to be
thread-safe. `Level`, `ServerLevel`, `Entity`, `BlockEntity`, `LevelChunk`, the registries and the
game's collections all assume exactly one thread touches them. Moving a piece of that work onto
another thread does not merely risk a rare crash — it produces silent corruption:

- entity lists and chunk maps are plain collections; concurrent mutation gives you a lost entity,
  a duplicated one, or a `ConcurrentModificationException` in an unrelated system three ticks later;
- block updates are ordered. Two threads applying updates to neighbouring blocks can produce a
  state that no sequence of single-threaded ticks could ever produce, and that state then gets saved;
- much of the game reads a value, decides, and writes it back with no locking anywhere between;
- the damage is usually *written to disk* before anybody notices, so "it worked on my server for a
  week" is not evidence of safety.

Mods that genuinely parallelise Minecraft do it by rewriting specific subsystems whose data
dependencies have been analysed — chunk generation, lighting, pathfinding — not by moving arbitrary
code off the main thread. That is a different and much larger project.

So TickPilot's rule (`INV-1`) is absolute: **no `Level`, `Entity`, `BlockEntity`, `LevelChunk`,
`MinecraftServer`, registry or game collection is touched from any thread but the server thread.**
Background threads may only do pure arithmetic on copied primitives. Every measurement in this mod
is built that way: the tick hooks record `long`s, and anything that needs to look at the world does
it on the server thread or not at all. The public API enforces the same rule on other mods —
`TickPilotApi.submit` called from the wrong thread returns `WRONG_THREAD` and refuses to run or
queue the work, rather than "helpfully" executing it somewhere unsafe.

## Comparing with spark

[spark](https://spark.lucko.me/) is the profiler most operators already know, and the two answer
different questions. If you have both, use them together:

| | spark | TickPilot |
|---|---|---|
| Method | JVM sampling profiler — where the *call stack* spends time | instrumentation at named tick phases — where the *tick* spends time |
| Best at | finding a specific hot method, including inside a mod's own code | attributing cost to entity and block entity **types**, and to mod IDs |
| Output | a call tree you read in a browser | numbers in chat, with a recommendation |
| Overhead | low, but sampling | ~0.01–0.02 ms/tick, measured and reported by `/tickpilot status` |

To compare them on the same load, run both over the same window:

```
/spark profiler start --timeout 60
/tickpilot profile 60
```

The two should agree on the *shape* of the problem — if spark says the tree is dominated by
`ServerLevel.tickNonPassenger` and TickPilot says `ENTITIES` is 70 % of the tick, that is the same
finding in two vocabularies. If they disagree, trust spark on *where in the code* and TickPilot on
*which content*, and please report the disagreement.

For a vanilla cross-check with no mods at all, `/tick query` prints the server's own average and
percentiles. TickPilot's numbers read slightly lower than vanilla's for the reason described below.

## How tick time is measured, and what that misses

TickPilot measures ticks through the Fabric API events `ServerTickEvents.START_SERVER_TICK` and
`END_SERVER_TICK`. It installs **no mixin** for measurement.

Being honest about the limit: Fabric fires those events around the body of
`MinecraftServer.tickServer(...)` — from the `tickChildren(...)` call to the end of the method.
Everything the server does in a tick is inside that span, but two things are not: the handful of
statements at the top of `tickServer`, and the task draining that `runServer` does around it. So
TickPilot's MSPT reads slightly *lower* than vanilla's own tick timer. The difference is small and
constant, and it changes none of the conclusions — comparing ticks with each other, percentiles
and the load level are all unaffected — which is why it is not corrected with a mixin into the
server loop, where the compatibility risk with other performance mods would be real.

## `/tick freeze` and `/tick rate`

Vanilla can freeze the game or change the target tick rate. When it does, TPS below 20 is
configured rather than a symptom, and `/tickpilot status` says so explicitly instead of reporting
a fake problem.

## Overhead

Measured, not asserted. `/tickpilot status` prints it:

```
TickPilot overhead: 0.01 ms/tick (0.11% of MSPT), worst slice 17.6 us
```

What is timed is the mod's own tick work — the ring buffer write, the load level update and the
bookkeeping around them — bracketed by two extra `nanoTime()` calls per tick. With no profiling
session running, that is all TickPilot does: the Mixin hooks are a field read and a null check
each, with no clock call at all.

Two things this number will not tell you, said plainly rather than left to be discovered:

**The percentage is meaningless on an idle server.** A vanilla server with nobody on it ticks in
about 0.2 ms, so 0.01 ms of mod work reads as 5 %. The absolute figure is the one that matters and
it does not move; the ratio only becomes informative once the server has real work to do, which is
also the only time anyone cares. Measured on a loaded server it sits around 0.1 %.

**The worst slice includes JIT warm-up.** The first ticks after startup run interpreted, so the
peak is usually set in the first second and then never beaten. Treat it as "nothing pathological
happened later", not as a typical cost.

**While a profiling session runs, the number above does not cover it.** Each hook reads its
timestamp before doing its bookkeeping, so that bookkeeping lands inside the category it is
measuring. Deep profiling inflates the categories it reports, which is a large part of why it is
off by default and time-limited.

## For other mod developers

TickPilot has a small public API in `com.tickpilot.api`. It does two things: it runs work you have
declared safe to delay a little later than you asked, and it tells you what has been measured.
Nothing in it makes the game do less — see [What the API cannot do](#what-the-api-cannot-do).

### Deferring your own work

Declare what a kind of task is once, then submit occurrences of it:

```java
public final class MyModTasks {
    public static final ResourceLocation REBUILD_CACHE =
            ResourceLocation.fromNamespaceAndPath("mymod", "rebuild_cache");

    public static void register() {
        TickPilotApi.registerTaskProfile(REBUILD_CACHE, TaskProfile.builder()
                .deferrable(true)          // may run in a later tick than the one that asked
                .maxDelayTicks(40)         // but never more than two seconds later
                .priority(TaskPriority.LOW)
                .coalescable(true)         // ten "it is dirty" in one tick means one rebuild
                .build());
    }
}

// later, on the server thread:
SubmitResult result = TickPilotApi.submit(MyModTasks.REBUILD_CACHE, () -> rebuildCache(level));

if (result.rejected()) {
    rebuildCache(level);   // the queue was full; the work is still yours
}
```

The rules worth knowing before you use it:

- **The server thread, always.** A submission from another thread is refused with
  `SubmitResult.WRONG_THREAD` and neither runs nor queues. TickPilot will not run your work
  off-thread, because that is where world corruption comes from.
- **Check the result.** `submit` never throws, so the return value is how you learn what happened.
  `result.rejected()` means the work is still yours; the four-line pattern above is the whole
  handling most mods need.
- **Critical work is never delayed.** `TaskProfile.criticalTask()` runs inside `submit`, is never
  queued, and therefore can never be dropped when the queue is full.
- **Unregistered ids run immediately.** If you forget `registerTaskProfile`, your work still runs —
  in the tick you submitted it, with a warning in the log. TickPilot does not assume that work it
  knows nothing about is safe to delay.
- **A shutdown discards what is still queued.** Anything that must survive a stop must not be
  deferrable.
- **STRICT mode defers nothing.** With `default_mode = "STRICT"` or `enable_adaptive_mode = false`,
  every submission runs immediately, exactly as it would without TickPilot installed.

### Protecting your content from future throttling

```java
TickPilotApi.registerPolicy(
        ResourceLocation.fromNamespaceAndPath("mymod", "policy"),
        (typeId, load) -> typeId.getNamespace().equals("mymod") && isTimingSensitive(typeId)
                ? ThrottleAdvice.NEVER_THROTTLE
                : ThrottleAdvice.NO_OPINION);
```

`NEVER_THROTTLE` is a veto that cannot be overridden, including from the config. `SAFE_TO_THROTTLE`
is only ever a permission: TickPilot still requires the type to be on the operator's allowlist.

**Nothing consults a policy today.** Entity and block entity throttling is not implemented in this
version, so a registered policy has no effect yet. `registerPolicy` writes one log line saying so,
so that "my policy does nothing" is not mistaken for a bug in your integration. The same is true of
`markSafeToDefer` and `markSafeForAsyncCompute`: both are recorded and neither changes any
behaviour yet.

### Reading the metrics

```java
TickPilotApi.metrics().ifPresent(m ->
        LOGGER.info("{} TPS, {} ms/tick, load {}", m.tps(), m.avgMspt5s(), m.load()));
```

An empty `Optional` means no server is running, TickPilot has disabled itself, or nothing has been
measured yet. The snapshot is a copy and never changes after you receive it.

### Not crashing when TickPilot is absent

`TickPilotApi` returning `UNAVAILABLE` covers a server that is not running. It cannot cover the mod
not being installed at all: if the jar is missing, the class does not exist and touching it throws
`NoClassDefFoundError`. Keep every reference inside a class you only load after checking, and TickPilot
becomes a genuine soft dependency:

```java
// MyMod.java - loaded always. Mentions no TickPilot type.
public class MyMod implements ModInitializer {
    private static boolean tickPilotPresent;

    @Override
    public void onInitialize() {
        tickPilotPresent = FabricLoader.getInstance().isModLoaded("tickpilot");

        if (tickPilotPresent) {
            TickPilotSupport.register();   // first mention of the API is behind the check
        }
    }

    public static void rebuildCacheSoon(ServerLevel level) {
        if (tickPilotPresent && TickPilotSupport.deferred(level)) {
            return;
        }

        rebuildCache(level);   // no TickPilot, or it refused: do it now
    }
}

// TickPilotSupport.java - a separate class, loaded only when the check passed.
final class TickPilotSupport {
    static void register() { /* registerTaskProfile, registerPolicy */ }

    static boolean deferred(ServerLevel level) {
        return TickPilotApi.submit(MyModTasks.REBUILD_CACHE, () -> rebuildCache(level)).queued();
    }
}
```

Two details make this work. The check happens before any TickPilot class is named, and the calls
live in a *separate class* — the JVM loads a class the first time it is used, so a method that
merely mentions `TickPilotApi` in a branch that never runs is still safe, but a field or a
signature of the enclosing class would not be. Declare TickPilot as an optional dependency ("suggests")
in your `fabric.mod.json` and nothing else is needed.

### What the API cannot do

- It does not schedule Minecraft's own work. Only tasks another mod registered and submitted go
  through the queue; TickPilot never intercepts a `Runnable` of the game's. Vanilla's tasks carry
  no profile saying whether anything is waiting on them, so there is no honest way to reorder them.
- It does not run anything off the server thread, and no flag in it will.
- It does not throttle entities or block entities in this version.

## Building and testing

```bash
./gradlew build   # compile, test, remap jar
./gradlew test    # unit tests only
```

The unit tests do not launch Minecraft: `com.tickpilot.metrics.TickMetrics`,
`com.tickpilot.budget.TickBudget`, `com.tickpilot.scheduler.AdaptiveScheduler` and everything in
`com.tickpilot.config` are plain Java classes with no `net.minecraft` imports. The clock is supplied
by the test, and the config tests run against a temporary directory. The scheduler is generic over
its task id type for the same reason — the mod keys it by `ResourceLocation`, the tests by `String`,
so priority order, deadlines and queue overflow are all exercised without the game.

## Licence

MIT. See `LICENSE`.
