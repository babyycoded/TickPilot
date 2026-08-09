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

Measurement costs two `System.nanoTime()` calls and a few array writes per tick, with no
allocation in the tick loop. Percentiles, averages and TPS are computed only when you run a
command. It is not free, but it is not something you will see in MSPT either.

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
