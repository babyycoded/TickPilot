# TickPilot

A server-side Fabric performance mod for Minecraft Java Edition 1.21.1 (Java 21).

**Measure first, explain second, throttle cautiously last.** TickPilot makes no promises about
magically raising your TPS. At this point in development it only measures and reports; nothing
in the game is throttled or changed.

> This README is a work in progress and grows with each development phase. The full document
> required by `SPEC.md` §10 lands in Phase 11.

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

Example output:

```
TickPilot - 14203 ticks measured, uptime 11m 50s
TPS: 20.00
MSPT: last 8.41, avg 5s 9.02, 1m 8.77, 5m 8.61
MSPT: p95 14.30, p99 22.85 (last 1m)
MSPT: max 61.20, 3m 12s ago; p95 15.02, p99 40.11 (history: 5m 00s)
Load level: NORMAL (target 40.00 / high 45.00 / critical 50.00 ms)
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
deferred-task count reads `n/a` because the scheduler does not exist yet; a `0` there would read as
a measured empty queue.

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
`/tickpilot reload` applies changes without a restart. Every setting and its default is listed in
the generated file itself, so the file is the reference rather than this section.

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

## Building and testing

```bash
./gradlew build   # compile, test, remap jar
./gradlew test    # unit tests only
```

The unit tests do not launch Minecraft: `com.tickpilot.metrics.TickMetrics`,
`com.tickpilot.budget.TickBudget` and everything in `com.tickpilot.config` are plain Java classes
with no `net.minecraft` imports. The clock is supplied by the test, and the config tests run
against a temporary directory.

## Licence

MIT. See `LICENSE`.
