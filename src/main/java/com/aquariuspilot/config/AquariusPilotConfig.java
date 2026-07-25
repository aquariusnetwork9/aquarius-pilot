package com.aquariuspilot.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Aquarius Pilot's own configuration (saved to {@code plugins/config/aquarius-pilot.json}, independent of
 * ZenithProxy's core {@code config.json}). This is a deliberately trimmed-down surface compared to the
 * AquariusProxy fork's {@code Config.Client.Extra.ElytraPilot}/{@code .Regear} sections (which have well
 * over a hundred combined fields) — see the repo README's "Known limitations" and ROADMAP.md for exactly
 * what was left out and why.
 *
 * <p>Every field here is read by something. Fields that were carried over from the fork but wired to nothing
 * ({@code pitstop*}, {@code goalRelogMinutes}, {@code highway}, {@code highwayCruise}, {@code recordGrief},
 * {@code bounceHostile*}) have been removed rather than left as decoys — a config knob that silently does
 * nothing is worse than no knob. {@code targetIsNether} was the dangerous one of that set (the target
 * dimension was accepted and then ignored) and is now a real pre-flight gate: see
 * {@code ElytraPilotModule#checkDimension}.
 *
 * <p>{@link #validate()} clamps hand-edited values to ranges the flight loop can actually survive and is run
 * once at plugin load.
 */
public class AquariusPilotConfig {

    public final ElytraPilot elytraPilot = new ElytraPilot();
    public final Regear regear = new Regear();

    /** Vanilla elytra max damage; durability thresholds above this can never be met. */
    public static final int ELYTRA_MAX_DAMAGE = 432;

    public static class ElytraPilot {
        /**
         * Status mirror only. The module deliberately does <b>not</b> auto-start from this
         * ({@code enabledSetting()} returns {@code false}), so a proxy restart never re-flies an old route
         * unsupervised — start a flight with {@code .aqp fly to <x> <z>}.
         */
        public boolean enabled = false;

        /** Gate e-bounce entirely until an ACTIVE ViaVersion downgrade to protocol 765 is in place. See README. */
        public boolean requireProtocolCheck = true;

        /**
         * Movement-input priority for the flight. Must outrank the stock modules that would otherwise steal
         * the bot mid-air — AntiAFK (5000 by default, and enabled by default) and AutoEat (11000). Leave the
         * <i>inventory</i> priority alone: AutoTotem (13000) must keep winning there.
         */
        public int inputPriority = 12000;

        // ---- e-bounce (ground bounce; per-tick glide-state hold — see ElytraPilotModule#holdBounceGlide) ----
        public boolean bounceEnabled = true;
        public int roadY = 120;
        public int roadDropAbort = 8;
        public boolean bounceClearOnGround = true;
        public double bounceDeployHeight = 0.3;
        /** Only re-engage the glide while vertical velocity is below this: the rise stays ballistic, the glide catches the descent. */
        public double bounceRedeployMaxVy = 5.0;
        public double bounceSpeed = 38.0;          // target b/s before dropping sprint
        public double bounceStallSpeed = 3.0;      // b/s below which we count stall ticks
        public int bounceStallLimit = 60;
        public boolean bounceConstantPitchOnDiagonal = true;
        public float bounceDiagonalPitch = 62f;
        public float bouncePitch = 25f;
        public float bounceDivePitch = 75f;
        public double bounceDiveHeight = 3.0;
        public double bounceDiveGain = 12.0;
        public boolean bounceDebug = false;
        public boolean passObstacles = true;
        public boolean recoverFromDrop = true;

        // ---- firework cruise (used for the overworld-direct leg, and as a highway fallback) ----
        public float cruisePitch = 8f;
        public double cruiseMinSpeed = 25.0;       // b/s floor before firing another rocket
        /**
         * Minimum ticks between rocket uses. A rocket's boost lasts about two seconds; firing every tick
         * burns a stack in three seconds and the stacked boosts read as speed-hacking.
         */
        public int fireworkCooldownTicks = 40;

        // ---- ground scan / descent ----
        /** How far below the bot the landing/emergency ground scan looks before giving up. */
        public int groundScanDepth = 256;
        /** Cut the glide only within this many blocks of MEASURED ground (never against a guess). */
        public double landingFlareHeight = 2.0;
        /** Ticks a controlled descent may take before it escalates to the emergency descent. */
        public int descentTimeoutTicks = 2400;
        /** Ticks the emergency descent may take before the module gives up and disables. */
        public int emergencyTimeoutTicks = 1200;

        // ---- elytra durability / resupply ----
        public int freshElytraMinDurability = 20;
        public boolean resupplyFromEchest = true;
        public int resupplySpareThreshold = 1;

        // ---- trip target ----
        public boolean hasTarget = false;
        public double targetX = 0;
        public double targetZ = 0;
        /** Which dimension the target coordinates are in. The flight has no portal traversal, so a mismatch
         *  against the bot's current dimension is refused at pre-flight rather than flown anyway. */
        public boolean targetIsNether = false;
        public double arriveRadius = 32;
        /** Allow enabling with no target at all. There is no arrival condition in that mode — the flight only
         *  ends when the gear runs out or an operator disables it. Off by default on purpose. */
        public boolean allowFreeFly = false;

        // ---- pre-flight checklist minimums (mirrors FlightGear) ----
        public boolean autoGearUp = true;
        public int preflightMinArmor = 3;
        public int preflightMinElytras = 2;
        public int preflightMinTotems = 1;
        public boolean preflightOffhandTotem = true;
        public int preflightMinFireworks = 64;
        public int preflightMinEgaps = 0;
        public boolean preflightRequirePickaxe = true;
        public boolean preflightWantWeapon = false;
        public int preflightMinEchests = 1;

        /** How many Regear runs a single gear-up / resupply may spend before giving up. */
        public int gearUpMaxAttempts = 3;
        /** Hard ceiling on any single ground phase (pre-flight gear-up, mid-flight resupply). */
        public int groundPhaseTimeoutTicks = 12000;

        // ---- goal stop ----
        public boolean goalLogout = false;
    }

    public static class Regear {
        public boolean enabled = false;

        // kit shulker matching (name/colour/contents — no KitProfile system, single flat config; see ROADMAP.md)
        public String kitShulkerName = "kit";
        public boolean matchByColor = false;
        public String kitShulkerColor = "";
        public boolean matchByContents = true;
        public boolean matchByElytraCount = false;
        public int kitElytraCount = 2;

        public boolean equipArmor = true;
        public boolean equipElytra = true;
        public boolean offhandTotem = true;
        public boolean returnShulker = true;

        public boolean cherryPickFallback = true;
        /** A full single-item gear-up can need ~9 shulkers (elytra, helmet, leggings, boots, totem,
         *  fireworks, gapples, pickaxe, echest), so this has headroom over that. */
        public int cherryPickMaxShulkers = 12;
        /** Cherry-pick against the FULL pre-flight checklist ({@link com.aquariuspilot.module.FlightGear}),
         *  not just what needs equipping. Set false for the old equip-only behaviour (elytra to wear, empty
         *  armour slots, offhand totem) if you run Regear standalone and don't want it hauling fireworks. */
        public boolean fillFlightChecklist = true;
        /** Storage is separate single-item shulkers (one per item type) rather than a hand-packed kit:
         *  skip the primary kit-shulker match entirely and cherry-pick from the first round. */
        public boolean singleItemShulkers = false;

        /** Radius of a synchronous O(r²) world scan run on the tick thread — keep it small. */
        public int echestScanRadius = 24;
        public boolean selfKillRelocate = false;
        /**
         * Let relocation {@code /kill} the bot even when it is carrying something. Off by default, and it
         * should stay off unless the bot is deliberately expendable: {@code /kill} drops the <b>entire</b>
         * inventory on an anarchy server — worn armour, the elytra, totems, and any kit already pulled — and
         * RELOCATE is the very first state of the cycle, so enabling Regear on a geared bot with this on
         * destroys the loadout immediately. With it off, relocation refuses (and says so) whenever there is
         * anything to lose.
         */
        public boolean relocateAllowGearLoss = false;
        public int relocateMinSkyClearance = 3;
        public int relocateMaxAttempts = 5;
        public int relocateKillWaitTicks = 100;
        public int relocateStuckTicks = 200;

        public boolean pauseOnPlayer = true;
        public double playerPauseRange = 64;

        public int actionDelayTicks = 4;
        public int settleTicks = 10;

        public boolean disableWhenDone = true;
    }

    // ---------------------------------------------------------------- validation

    /**
     * Clamps hand-edited values into ranges the flight loop and the Regear cycle can actually survive, and
     * reports everything it changed. Called once from {@code AquariusPilotPlugin#onLoad}.
     *
     * @return human-readable descriptions of every value that was corrected (empty if the config was sane)
     */
    public List<String> validate() {
        List<String> changes = new ArrayList<>();
        var e = elytraPilot;
        var r = regear;

        // --- flight loop -------------------------------------------------------------------------------
        e.inputPriority = clampI(changes, "elytraPilot.inputPriority", e.inputPriority, 1, 1_000_000);
        e.arriveRadius = clampD(changes, "elytraPilot.arriveRadius", e.arriveRadius, 4, 10_000);
        e.roadY = clampI(changes, "elytraPilot.roadY", e.roadY, -64, 319);
        e.roadDropAbort = clampI(changes, "elytraPilot.roadDropAbort", e.roadDropAbort, 1, 384);
        e.bounceStallLimit = clampI(changes, "elytraPilot.bounceStallLimit", e.bounceStallLimit, 1, 12_000);
        e.bounceStallSpeed = clampD(changes, "elytraPilot.bounceStallSpeed", e.bounceStallSpeed, 0, 200);
        e.bounceSpeed = clampD(changes, "elytraPilot.bounceSpeed", e.bounceSpeed, 1, 200);
        e.bounceDeployHeight = clampD(changes, "elytraPilot.bounceDeployHeight", e.bounceDeployHeight, 0, 64);
        // must be > 0: at <= 0 the redeploy gate can never open and the bounce silently stops bouncing
        if (!(e.bounceRedeployMaxVy > 0)) {
            changes.add("elytraPilot.bounceRedeployMaxVy " + e.bounceRedeployMaxVy + " -> 5.0 (must be > 0, "
                + "or the glide is never re-engaged and the bounce stops working)");
            e.bounceRedeployMaxVy = 5.0;
        }
        e.bounceDiveHeight = clampD(changes, "elytraPilot.bounceDiveHeight", e.bounceDiveHeight, 0, 256);
        e.bounceDiveGain = clampD(changes, "elytraPilot.bounceDiveGain", e.bounceDiveGain, 0, 90);
        e.bouncePitch = clampF(changes, "elytraPilot.bouncePitch", e.bouncePitch, -90, 90);
        e.bounceDivePitch = clampF(changes, "elytraPilot.bounceDivePitch", e.bounceDivePitch, -90, 90);
        e.bounceDiagonalPitch = clampF(changes, "elytraPilot.bounceDiagonalPitch", e.bounceDiagonalPitch, -90, 90);
        e.cruisePitch = clampF(changes, "elytraPilot.cruisePitch", e.cruisePitch, -90, 90);
        e.cruiseMinSpeed = clampD(changes, "elytraPilot.cruiseMinSpeed", e.cruiseMinSpeed, 1, 200);
        e.fireworkCooldownTicks = clampI(changes, "elytraPilot.fireworkCooldownTicks", e.fireworkCooldownTicks, 20, 400);

        // --- descent / ground scan ---------------------------------------------------------------------
        e.groundScanDepth = clampI(changes, "elytraPilot.groundScanDepth", e.groundScanDepth, 8, 384);
        e.landingFlareHeight = clampD(changes, "elytraPilot.landingFlareHeight", e.landingFlareHeight, 0.5, 8);
        e.descentTimeoutTicks = clampI(changes, "elytraPilot.descentTimeoutTicks", e.descentTimeoutTicks, 200, 72_000);
        e.emergencyTimeoutTicks = clampI(changes, "elytraPilot.emergencyTimeoutTicks", e.emergencyTimeoutTicks, 200, 72_000);

        // --- durability / resupply ---------------------------------------------------------------------
        // at >= ELYTRA_MAX_DAMAGE no elytra in the game is ever "fresh" and the bot would resupply forever
        e.freshElytraMinDurability = clampI(changes, "elytraPilot.freshElytraMinDurability",
            e.freshElytraMinDurability, 0, ELYTRA_MAX_DAMAGE - 1);
        e.resupplySpareThreshold = clampI(changes, "elytraPilot.resupplySpareThreshold", e.resupplySpareThreshold, 0, 36);

        // --- pre-flight checklist ----------------------------------------------------------------------
        e.preflightMinArmor = clampI(changes, "elytraPilot.preflightMinArmor", e.preflightMinArmor, 0, 36);
        e.preflightMinElytras = clampI(changes, "elytraPilot.preflightMinElytras", e.preflightMinElytras, 1, 36);
        e.preflightMinTotems = clampI(changes, "elytraPilot.preflightMinTotems", e.preflightMinTotems, 0, 36 * 64);
        e.preflightMinFireworks = clampI(changes, "elytraPilot.preflightMinFireworks", e.preflightMinFireworks, 0, 36 * 64);
        e.preflightMinEgaps = clampI(changes, "elytraPilot.preflightMinEgaps", e.preflightMinEgaps, 0, 36 * 64);
        e.preflightMinEchests = clampI(changes, "elytraPilot.preflightMinEchests", e.preflightMinEchests, 0, 36 * 64);
        e.gearUpMaxAttempts = clampI(changes, "elytraPilot.gearUpMaxAttempts", e.gearUpMaxAttempts, 1, 5);
        e.groundPhaseTimeoutTicks = clampI(changes, "elytraPilot.groundPhaseTimeoutTicks", e.groundPhaseTimeoutTicks, 400, 144_000);

        // --- Regear ------------------------------------------------------------------------------------
        // 0 would spin every state machine that uses them as a "wait one beat" delay
        r.actionDelayTicks = clampI(changes, "regear.actionDelayTicks", r.actionDelayTicks, 1, 200);
        r.settleTicks = clampI(changes, "regear.settleTicks", r.settleTicks, 1, 400);
        // an ender chest holds 27 slots, so more shulker rounds than that can never help
        r.cherryPickMaxShulkers = clampI(changes, "regear.cherryPickMaxShulkers", r.cherryPickMaxShulkers, 1, 27);
        // O(r²) synchronous world scan on the tick thread — 32 already costs ~3k block lookups per scan
        r.echestScanRadius = clampI(changes, "regear.echestScanRadius", r.echestScanRadius, 1, 32);
        r.playerPauseRange = clampD(changes, "regear.playerPauseRange", r.playerPauseRange, 0, 128);
        r.relocateMinSkyClearance = clampI(changes, "regear.relocateMinSkyClearance", r.relocateMinSkyClearance, 1, 64);
        r.relocateMaxAttempts = clampI(changes, "regear.relocateMaxAttempts", r.relocateMaxAttempts, 1, 20);
        r.relocateKillWaitTicks = clampI(changes, "regear.relocateKillWaitTicks", r.relocateKillWaitTicks, 20, 6000);
        r.relocateStuckTicks = clampI(changes, "regear.relocateStuckTicks", r.relocateStuckTicks, 20, 12_000);
        r.kitElytraCount = clampI(changes, "regear.kitElytraCount", r.kitElytraCount, 1, 27);

        return changes;
    }

    private static int clampI(List<String> changes, String name, int v, int lo, int hi) {
        int c = Math.min(hi, Math.max(lo, v));
        if (c != v) changes.add(name + " " + v + " -> " + c + " (allowed " + lo + ".." + hi + ")");
        return c;
    }

    private static double clampD(List<String> changes, String name, double v, double lo, double hi) {
        double c = Double.isNaN(v) ? lo : Math.min(hi, Math.max(lo, v));
        if (c != v) changes.add(name + " " + v + " -> " + c + " (allowed " + lo + ".." + hi + ")");
        return c;
    }

    private static float clampF(List<String> changes, String name, float v, float lo, float hi) {
        float c = Float.isNaN(v) ? lo : Math.min(hi, Math.max(lo, v));
        if (c != v) changes.add(name + " " + v + " -> " + c + " (allowed " + lo + ".." + hi + ")");
        return c;
    }
}
