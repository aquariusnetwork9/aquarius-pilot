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

On 2b2t you also have to stop ViaVersion switching itself off there:

```
via zenithToServer on
via zenithToServer disableOn2b2t off
```

or set it directly in `config.json` under `client.viaversion`: `enabled: true`, `disableOn2b2t: false`,
`autoProtocolVersion: false`, `protocolVersion: 765`.

**All four matter.** `protocolVersion: 765` is the stock default, so checking it alone proves nothing — an
untouched config reads "765" while ViaVersion is disabled outright, switched off for 2b2t, or in `auto`
mode (which ignores the version field entirely). This plugin checks the whole set, on enable **and** again
on every (re)connect (`elytraPilot.requireProtocolCheck`, default `true`), and refuses to start if the
downgrade is not actually active — logging exactly which of the four is wrong. `.aqp status` shows the same
breakdown. Set `requireProtocolCheck` to `false` if you want to fly with only the firework cruise (which is
not protocol-sensitive) and skip the gate — but the bounce itself will still not perform well under any
other protocol.

ElytraPilot also **refuses to start while stock AutoArmor is enabled** (AutoArmor fills the chest slot with
the best chestplate it can find, which means stripping the worn elytra mid-flight), and warns loudly about
**AntiAFK** and **AutoEat** — both enabled by default — which otherwise fight the flight for control of the
bot. The flight's movement-input priority (`elytraPilot.inputPriority`, default 12000) outranks both.

**Eating is the one deliberate exception.** `Bot` only sprints while hunger is above 6, and the e-bounce runs
on sprint-jumping, so a flight that can never eat degrades permanently on a long haul. With
`elytraPilot.allowEating` (default on), the flight opens a short *yield window* when hunger reaches
`elytraPilot.eatHungerThreshold` (default 10): it keeps submitting inputs every tick, just at a priority one
below AutoEat's, so AutoEat wins the arbitration and does its normal swap-and-eat — then the flight takes
full priority straight back. The glide never depends on winning the input (it is held through the cached
fall-flying bit), so a cruise eat costs almost nothing; a bounce eat costs the sprint for the ~50-tick eat
plus a few seconds of rebuilt speed, every few minutes. The window is bounded
(`elytraPilot.eatWindowMaxTicks`), never opens during a descent, landing, emergency or ground stop, and
suppresses the bounce stall detector while it is open so the pause is not mistaken for an obstacle. Food is
part of the pre-flight checklist too (`elytraPilot.preflightMinFood`), so Regear fetches it during a gear-up.

A flight is always started explicitly and never resumes on its own: ElytraPilot does not auto-enable from
its config, refuses to enable without a target (unless `elytraPilot.allowFreeFly` is set), and clears the
target on arrival — so a proxy restart can never silently re-fly an old route. The target dimension
(`.aqp fly nether on/off`, `elytraPilot.targetIsNether`) is a hard pre-flight gate, not a hint: there is no
portal traversal here, so a mismatch is refused rather than flown.

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
