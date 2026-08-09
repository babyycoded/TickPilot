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

Example output:

```
TickPilot — 14203 ticks measured, uptime 11m 50s
TPS: 20.00
MSPT: last 8.41, avg 5s 9.02, 1m 8.77, 5m 8.61
MSPT: p95 14.30, p99 22.85, max 61.20 (last 5 min)
Load level: NORMAL (target 40.00 / high 45.00 / critical 50.00 ms)
```

## Load levels

Computed from the 5 s average MSPT. With the default budget (target 40 ms, critical 50 ms):

| Level | Average MSPT |
|---|---|
| NORMAL | below 40 ms |
| ELEVATED | 40–45 ms |
| HIGH | 45–50 ms |
| CRITICAL | 50 ms and above |

The level does not chatter on a boundary: leaving a level downwards requires the average to fall
a margin below the threshold *and* the level to have been held for at least five seconds.
Escalation is immediate, because the input is already a five-second average.

Load level is **not** the same thing as the adaptive mode (STRICT / BALANCED / AGGRESSIVE, arriving
in a later phase). The level says how bad things are and is computed; the mode says how far the
mod may intervene and is chosen by you.

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

The unit tests do not launch Minecraft: `com.tickpilot.metrics.TickMetrics` and
`com.tickpilot.budget.TickBudget` are plain Java classes with no `net.minecraft` imports, and the
clock is supplied by the test.

## Licence

MIT. See `LICENSE`.
