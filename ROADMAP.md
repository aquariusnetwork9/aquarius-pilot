# Roadmap / porting status

Source: `aquariusnetwork9/AquariusProxy`, branch `dev/regear-echest-mend`, package `com.aquarius.*`
(`com.aquarius.module.impl.{ElytraPilot,Regear,ElytraTrip,FlightGear,ElytraPathfinder,NetherRouter}`,
`com.aquarius.feature.highways.*`, `com.aquarius.command.impl.{ElytraPilotCommand,RegearCommand,HighwayCommand}`).

## Ported (fully or near-verbatim, package renamed to `com.zenith`/`com.aquariuspilot`)

- `AbstractFieldModule` (450 lines) → `com.aquariuspilot.module.AbstractFieldModule` — turned out to NOT be
  a stock ZenithProxy class as earlier notes assumed; it's fork-only helper code built entirely on stock
  primitives, so it ports here almost verbatim. Dropped: the ghost-hand no-LOS open (`openGhost`/
  `ghostUseItemOn`), which depends on AquariusProxy's `emitAirPlace` (no stock equivalent).
- `FlightGear` (240 lines) → `com.aquariuspilot.module.FlightGear` — flight-readiness checklist, trimmed to
  this plugin's flat (non-`KitProfile`) config.
- `ElytraPathfinder` (144 lines) → `com.aquariuspilot.module.ElytraPathfinder` — verbatim, pure Java, no
  external deps.
- `NetherRouter` (188 lines) → `com.aquariuspilot.module.NetherRouter` — near-verbatim; one change: the
  observed-chunk tracking set uses `java.util.HashSet<Long>` instead of
  `it.unimi.dsi.fastutil.longs.LongOpenHashSet` (that fastutil "longs" package isn't safely available to a
  plugin compiling only against the ZenithProxy API jar). **Ported but not yet wired into
  `ElytraPilotModule`'s flight loop** — present for a future native-routed cruise leg.
- `Highway` / `HighwayNetwork` (bundled `highways/nether_highways.json` map + snap-to-road) → verbatim.

## Reimplemented (not a line-for-line port)

- `ElytraPilot` (~3,439 lines) → `com.aquariuspilot.module.ElytraPilotModule` (~450 lines). Keeps: pre
  -flight gating + auto gear-up handoff to Regear, takeoff, the ground e-bounce (via the new
  `BotPrePhysicsTick` hook + `Bot#setFallFlying`), a firework cruise glide, elytra-wear-aware mid-flight
  resupply, landing, and an optional goal-stop logout. The fork's version accumulated dozens of specific
  hardening passes (see its own `EBOUNCE_LOG.md`) — this plugin ports the core technique and the load
  -bearing safety checks (setback/frontier holds are simplified to a stall counter; the road-drop recovery
  and obstacle-stall-then-climb are kept, simplified).
- `Regear` (846 lines) → `com.aquariuspilot.module.RegearModule` (~430 lines) — the state machine ports
  essentially 1:1 onto stock APIs; dropped the `KitProfile` push/pop override system and the `flightRefill`
  mode (owned by AquariusProxy's separate `ElytraTrip` module, not ported).

## Not ported (explicitly out of scope for v0.1.0)

- `ElytraTrip` (716 lines) — the fork's separate pre-flight trip planner/gate. `ElytraPilotModule` folds a
  minimal version of its job (the pre-flight checklist + auto gear-up) directly in.
- `com.aquarius.feature.highways.HighwayGraph` (232 lines) — ring-road A* re-router for griefed sections.
- `com.aquarius.feature.highways.GriefMap` (138 lines) — the bot's local hazard memory that feeds the graph.
- The flight-angle physics solver (`solveAngles`/`solvePitch`/`simClear`/etc. in the fork's `ElytraPilot`) —
  the multi-hundred-line ray-casting solver used for free 3D flight and obstacle avoidance during cruise.
- AirPlace-based escape portal handling — no stock ghost-interact primitive exists to build it on.
- XP-bottle Mending repair choreography during flight.
- Bed / glowstone-anchor set-spawn for pitstop/goal-stop (goal-stop is logout-only in this plugin).
- `com.aquarius.command.impl.HighwayCommand` (142 lines) — highway map inspection/debug commands.
- Named `KitProfile` registry (multiple named kit templates) — this plugin has one flat kit config.

## Next steps (not blocking v0.1.0)

1. Wire `NetherRouter` into a real nether-routed cruise leg (currently ported but unused).
2. Port `HighwayGraph` + `GriefMap` for ring-road rerouting around griefed highway sections.
3. Bed/anchor set-spawn for goal-stop.
4. Once [rfresh2/ZenithProxy#313](https://github.com/rfresh2/ZenithProxy/pull/313) merges upstream, switch
   `.github/workflows/build.yml`/`publish.yml` back to downloading an official `+java.1.21.4` release
   instead of building the `aquariusnetwork9/ZenithProxy` fork branch from source (see the `TODO`s in both
   files).
