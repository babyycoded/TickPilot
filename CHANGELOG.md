# Changelog

All notable changes to TickPilot are recorded here. Requirement ids (`FR-*`, `AC-*`, `INV-*`)
refer to `SPEC.md`; decisions that deviate from it are logged in `SPEC.md` §13.

## Unreleased

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
