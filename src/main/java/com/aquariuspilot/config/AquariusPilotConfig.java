package com.aquariuspilot.config;

/**
 * Aquarius Pilot's own configuration (saved to {@code plugins/config/aquarius-pilot.json}, independent of
 * ZenithProxy's core {@code config.json}). This is a deliberately trimmed-down surface compared to the
 * AquariusProxy fork's {@code Config.Client.Extra.ElytraPilot}/{@code .Regear} sections (which have well
 * over a hundred combined fields) — see the repo README's "Known limitations" and ROADMAP.md for exactly
 * what was left out and why.
 */
public class AquariusPilotConfig {

    public final ElytraPilot elytraPilot = new ElytraPilot();
    public final Regear regear = new Regear();

    public static class ElytraPilot {
        public boolean enabled = false;

        /** Gate e-bounce entirely until CONFIG.client.viaversion reports protocol 765 (1.20.3-1.20.4). See README. */
        public boolean requireProtocolCheck = true;

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
        public int bounceHostileScanRadius = 12;
        public int bounceHostileFightLimit = 200;
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
        public boolean highwayCruise = false;
        public float cruisePitch = 8f;
        public double cruiseMinSpeed = 25.0;       // b/s floor before firing another rocket

        // ---- elytra durability / resupply ----
        public int freshElytraMinDurability = 20;
        public boolean resupplyFromEchest = true;
        public int resupplySpareThreshold = 1;

        // ---- trip target ----
        public boolean hasTarget = false;
        public double targetX = 0;
        public double targetZ = 0;
        public boolean targetIsNether = false;
        public double arriveRadius = 32;

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

        // ---- pitstop / goal-stop (simplified: logout only — see README known limitations for bed/anchor set-spawn) ----
        public boolean pitstopEnabled = false;
        public double pitstopX = 0;
        public double pitstopZ = 0;
        public double pitstopRadius = 48;
        public boolean goalLogout = false;
        public int goalRelogMinutes = 0; // <= 0 stays logged out

        // ---- highway-hardening (nether grief awareness; see NetherRouter/HighwayGraph) ----
        public boolean highway = true;
        public boolean recordGrief = false;
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

        public int echestScanRadius = 24;
        public boolean selfKillRelocate = false;
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
}
