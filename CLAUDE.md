# CLAUDE.md — TickPilot

<!--
  Этот файл Claude Code читает в начале КАЖДОЙ сессии.
  Он намеренно на английском и максимально коротким: он занимает контекст всегда.
  Держи его < 150 строк. Всё длинное живёт в SPEC.md и подгружается по запросу.
-->

## Project

Fabric performance mod for **Minecraft Java Edition 1.21.1**, Java 21.
Mod id `tickpilot`, package `com.tickpilot`. Build: `./gradlew build`.
Purpose: measure tick cost → explain it to a human → cautiously throttle optional work.

Requirements live in `SPEC.md` (FR-*, AC-*, INV-*, NG-*). Read the relevant section
before implementing. Do not invent requirements that are not there.

## Non-negotiable invariants

1. **Never** touch `World`, `ServerWorld`, `Entity`, `BlockEntity`, `Chunk`,
   `MinecraftServer`, registries or game collections off the server thread.
   Background threads may only do pure computation on copied primitives.
2. All world mutation happens on the server thread.
3. Risky optimizations are **off by default** and gated behind a config flag.
4. No global mutable static state that survives a world reload. State is per-`MinecraftServer`.
5. No `MinecraftClient` in common/server code — not even an import.
6. No allocations per entity/per tick in hot paths. Ring buffers and primitives only.
7. TickPilot must never crash the server: catch, log once with cooldown, disable the
   subsystem, continue.
8. Player-critical work (chunk loading for a player, teleport, force-loaded chunks) is
   never throttled.

## API verification protocol — the #1 failure mode

Minecraft/Yarn method names change between versions and my training data is not
authoritative for 1.21.1. Therefore:

- **Never write a Minecraft API call from memory.** Verify each one first.
- Verify by: `./gradlew genSources` then grep the decompiled sources, or check the
  jar in `~/.gradle/caches/fabric-loom/`, or `https://mappings.dev` / linkie for 1.21.1.
- If a Mixin target does not resolve against 1.21.1 mappings: **stop**, re-check
  mappings, report it. Do not guess a similar-looking name.
- If no safe hook exists for a feature: implement profiling only, add a row to the
  decision log in `SPEC.md` §13, and say so out loud. Do not fake it.

## Workflow rules

- **Plan first.** For any task bigger than one file, present a short plan and wait for
  approval before editing.
- **One phase at a time.** Follow the phase order in `PROMPT.md`. Do not jump ahead.
- After every phase: `./gradlew build` and `./gradlew test` must pass, then commit.
- Commit format: `feat(FR-5): adaptive load level with hysteresis`. Reference FR/AC ids.
- If a build fails, fix the root cause. **Never** delete, skip, `@Disabled`, or weaken a
  test to make the build green. Never comment out code to silence a compiler error.
- If something cannot be implemented safely, write a `TODO(FR-n): reason` plus a safe
  fallback and list it in `CHANGELOG.md` under "Not implemented". Do not ship pseudo-code
  disguised as a working feature.
- Ask before adding any new third-party dependency; justify it and pin the version.
- Do not touch `gradle.properties` versions without checking meta.fabricmc.net first.

## Code style

- Java 21. Records for immutable snapshots, sealed interfaces where they help.
- Package layout:
  `com.tickpilot` (entrypoint) · `.metrics` · `.profiler` · `.budget` · `.scheduler`
  · `.zones` · `.policy` · `.config` · `.command` · `.api` (public, javadoc'd)
  · `.mixin` (server) · `.client` (client-only) · `.client.mixin`
- Public API classes and methods require Javadoc. Internal classes need a one-line
  class comment saying what it owns.
- Every Mixin needs a Javadoc block: target method, why, compatibility risk, why no
  safer hook exists.
- Player-facing text uses `Text` + translation keys in `assets/tickpilot/lang/en_us.json`.
- Logging via SLF4J logger named `tickpilot`. No per-tick logging, ever.

## Commands

```bash
./gradlew build          # compile + test + remap jar
./gradlew test           # unit tests only
./gradlew genSources     # decompile MC sources for API verification
./gradlew runServer      # dedicated test server (accept EULA in run/)
./gradlew runClient      # test client for singleplayer checks
```

## Definition of done for any task

- Code compiles, tests pass, no new warnings about unresolved Mixin targets.
- Relevant AC in `SPEC.md` is actually satisfied (re-read it before claiming done).
- No invariant above is violated.
- New behavior is documented in `README.md` and `CHANGELOG.md`.
- You state honestly what is implemented, what is stubbed, and what was skipped.

## Communication

- Be concise. Report what you changed, what you verified, and what is uncertain.
- If you are guessing about a Minecraft API, say "unverified" explicitly.
- Prefer "I could not find a safe hook" over a plausible-looking wrong implementation.
