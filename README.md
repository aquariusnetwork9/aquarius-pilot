# Aquarius Pilot

A [ZenithProxy](https://github.com/rfresh2/ZenithProxy) plugin that ports the elytra long-haul flight
autopilot ("ElytraPilot") and gear-up module ("Regear") from the
[AquariusProxy](https://github.com/aquariusnetwork9/AquariusProxy) fork into a standalone plugin for
**stock** ZenithProxy — it runs on official ZenithProxy releases, with no core patches, so it works for any
ZenithProxy user, not just the fork.

Command prefix: **`.aqp`** (e.g. `.aqp fly on`, `.aqp fly to 100 -200`, `.aqp regear on`).

## Requirements

**E-bounce (the ground-bounce flight technique) only works when the proxy's outbound connection to the
server declares protocol 1.20.3-1.20.4 (protocol version 765) via ZenithProxy's stock `client.viaversion`
feature.** This is not optional tuning — it is extensively validated: under later protocol versions the
timing-sensitive glide-state handling the bounce depends on gets rejected/corrected by the server (visible
as constant rubber-banding / near-zero net speed).

Before enabling the bounce, set the outbound protocol to 1.20.4:

```
via zenithToServer version 1.20.4
```

or set it directly in `config.json` under `client.viaversion` (`enabled: true`, `protocolVersion: 765`).

By default this plugin **checks this on every enable** (`elytraPilot.requireProtocolCheck`, default
`true`) and refuses to start e-bounce if the protocol isn't 765, logging a loud warning explaining why. Set
`requireProtocolCheck` to `false` if you want to fly with only the firework cruise (which is not protocol
-sensitive) and skip the gate — but the bounce itself will still not perform well under any other protocol.

Also required:
- Java 25+ to build (JDK used to compile; ZenithProxy's plugin annotation processor requires it). The
  compiled bytecode itself targets Java 21, matching what ZenithProxy runs.
- `dev.babbaj:nether-pathfinder:1.6` (native nether A* library) — added automatically as a build dependency
  and shaded into the plugin jar; ships native binaries for linux/windows/macos.

## What this is

- **ElytraPilot** (`.aqp fly ...`) — pre-flight gear check, takeoff, the ground e-bounce, a
  firework-sustained cruise glide, elytra-wear-aware mid-flight resupply (hands off to Regear), landing, and
  an optional goal-stop logout.
- **Regear** (`.aqp regear ...`) — gear up from an ender chest, carried or placed nearby. Storage can be a
  single hand-packed kit shulker (matched by name / colour / contents) **or separate single-item shulkers**
  — one for elytras, one for rockets, one per armour piece and so on — which Regear consumes by cherry
  -picking only the still-missing items out of each, up to `cherryPickMaxShulkers` (12) of them. What counts
  as missing is the full pre-flight checklist (`regear.fillFlightChecklist`, on by default), so a gear-up
  that reports complete actually clears ElytraPilot's pre-flight gate. Also does armour + offhand totem
  equip, and the e-bounce mid-flight elytra-only refill mode.

## Known limitations (deferred from the ~8,000-line AquariusProxy source)

This is a scope-reduced **reimplementation** of the fork's flight loop and gear-up cycle, not a
line-for-line port — the fork's version is ~3,400 (ElytraPilot) + ~850 (Regear) + supporting modules lines
of tuning built up over many iterations on 2b2t. Ported in full: the container/pathing primitives
(`AbstractFieldModule`), the flight-readiness checklist (`FlightGear`), the coarse-grid A* look-ahead
(`ElytraPathfinder`), the native nether router (`NetherRouter`), and the bundled highway map
(`Highway`/`HighwayNetwork`). Deliberately trimmed or deferred, and **not silently missing** — see
`ROADMAP.md` for the full, itemised list. Headline items:

- No nether-native route planning wired into the flight loop yet (`NetherRouter` is ported and present, but
  `ElytraPilotModule` doesn't call it — it currently flies point-to-point / along the ground road).
- No ring-road grief reroute (`HighwayGraph`/`GriefMap` were not ported at all).
- No flight-angle physics solver / obstacle pass — obstacle handling is a simple stall-detect-then-climb,
  not the fork's full look-ahead solver.
- No bed/anchor set-spawn — goal-stop is logout-only.
- No AirPlace-based escape portal (no stock equivalent exists).
- No XP-bottle Mending choreography.
- No named `KitProfile` system — one flat kit config instead of a profile registry.
- No ghost-hand (no-line-of-sight) container interact (no stock equivalent exists) — Regear always paths to
  a normal line of sight.

## Building

```
./gradlew build
```

Place a ZenithProxy jar at `libs/ZenithProxy.jar`, or point at one with
`-Pzenith_jar=/path/to/ZenithProxy.jar`. Any official
[rfresh2/ZenithProxy release](https://github.com/rfresh2/ZenithProxy/releases) jar for the MC version in
`gradle.properties` works (the `+java.1.21.4` release channel) — CI fetches one the same way, see
`.github/workflows/build.yml`.

## Installing

Drop the built jar (`build/libs/AquariusPilot-<version>.jar`) into your ZenithProxy install's `plugins/`
directory and restart. Configuration lives at `plugins/config/aquarius-pilot.json`.
