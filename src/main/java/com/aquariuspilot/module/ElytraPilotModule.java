package com.aquariuspilot.module;

import com.aquariuspilot.AquariusPilotPlugin;
import com.github.rfresh2.EventConsumer;
import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;
import static com.zenith.Globals.MODULE;

/**
 * ElytraPilot — long-haul elytra autopilot, ported (and substantially trimmed) from AquariusProxy's
 * {@code com.aquarius.module.impl.ElytraPilot} (~3,400 lines). This is a scope-reduced reimplementation, not
 * a line-for-line port: it keeps the headline flight loop — pre-flight gating, takeoff, the ground e-bounce,
 * a firework-sustained cruise glide, elytra-wear-aware resupply via {@link RegearModule}, and landing —
 * while deferring the fork's heavier machinery (nether-native routing via {@link NetherRouter}, the ring-road
 * grief reroute via the highway graph, the flight-angle physics solver / obstacle pass, bed/anchor
 * set-spawn, XP-bottle Mending choreography, AirPlace-based escape portals). See the repo README's "Known
 * limitations" and ROADMAP.md for the full list.
 *
 * <h2>How the e-bounce holds the glide</h2>
 * The bounce re-engages fall-flying every airborne tick. On stock ZenithProxy this is done entirely from
 * this module, with no core changes: ZenithProxy runs the whole client tick and all inbound packet handling
 * serialized on one event loop, and module {@link ClientBotTick} subscribers (default priority) run earlier
 * in that single dispatch than {@code Bot}'s own tick ({@code Bot.TICK_PRIORITY}). {@code Bot} re-derives
 * its fall-flying state at the start of each tick from the cached self-entity metadata (the shared flags
 * byte at index 0, bit {@code 0x80} — the same bit its own deploy path sets), so {@link #tickBounce} writes
 * that bit through the entity cache just before {@code Bot} reads it: the physics step glides that very
 * same tick, and anything the server pushes into the cache between ticks is re-applied on the next tick
 * before it is ever consumed. See {@link #holdBounceGlide}.
 *
 * <h2>Pitch sign</h2>
 * Positive pitch is nose-<b>DOWN</b> ({@code MathHelper.calculateViewVector} has {@code y = -sin(pitch)},
 * and {@code Bot#travelFallFlying} only trades horizontal speed for altitude while {@code pitch < 0}). Every
 * descent in this class is therefore driven with a small POSITIVE pitch and flares with a small NEGATIVE
 * one; nothing here ever commits to a steep dive.
 *
 * <h2>Coordinates</h2>
 * Nothing in this class logs, alerts or embeds an absolute position. Altitude and distance are always
 * reported relative to something ("12 blocks below the road", "480 blocks out"), never as world coordinates.
 */
public class ElytraPilotModule extends Module {

    public enum Phase {
        IDLE, PREFLIGHT, TAKEOFF, BOUNCE, CRUISE,
        /** Controlled glide down to solid ground before doing anything that needs the bot standing still. */
        DESCEND,
        /** On the ground: swap a fresh elytra into the chest slot. */
        SWAP_ELYTRA,
        /** On the ground: Regear is refilling from the carried ender chest. */
        RESUPPLY,
        LAND, DONE, EMERGENCY
    }

    /** What the bot came down to do once {@link Phase#DESCEND} reaches the ground. */
    private enum GroundTask { NONE, PREFLIGHT, SWAP_ELYTRA, RESUPPLY }

    /** Player-inventory index of the chestplate/elytra equipment slot. */
    private static final int CHEST_EQUIP_SLOT = 6;
    /** Inventory-action priority. Deliberately BELOW AutoTotem (13000) — its swaps save the bot's life. */
    private static final int INVENTORY_PRIORITY = 3500;
    /**
     * Priority for the "release the controls" no-input submitted while Regear owns the bot. Low on purpose:
     * it only has to beat nothing at all, and Baritone (7000) must keep outranking it so Regear can path.
     */
    private static final int RELEASE_INPUT_PRIORITY = 100;
    /** Sentinel for "the ground under the bot could not be measured" — never treated as a height. */
    private static final double GROUND_UNKNOWN = Double.NEGATIVE_INFINITY;
    /** How many times the worn-elytra swap may be re-submitted before giving up. */
    private static final int MAX_SWAP_ATTEMPTS = 3;

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int bounceStallTicks;
    private int emergencyTicks;
    private int fireworkCooldown;

    private GroundTask groundTask = GroundTask.NONE;
    private int swapAttempts;
    private int swapWaitTicks;
    private int groundSettleTicks;
    /** Where {@link Phase#SWAP_ELYTRA} goes once the swap is verified. */
    private Phase swapReturnPhase = Phase.TAKEOFF;

    /** Only ever stop a Regear run this module started — an operator's standalone run is not ours to kill. */
    private boolean ownsRegear;

    private int gearUpAttempts;
    private int gearUpBestScore = Integer.MIN_VALUE;
    private int resupplyAttempts;
    private int resupplyBestScore = Integer.MIN_VALUE;

    // per-tick ground-scan cache (the scan is 9 columns deep, so never run it twice in one tick)
    private long tickCounter;
    private long groundScanTick = Long.MIN_VALUE;
    private double groundScanValue = GROUND_UNKNOWN;

    private static com.aquariuspilot.config.AquariusPilotConfig.ElytraPilot cfg() {
        return AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot;
    }

    public Phase getPhase() { return phase; }

    /**
     * Never auto-start. Plugin configs are saved after every command, so a persisted {@code enabled:true}
     * plus a persisted target used to mean a proxy restart silently re-flew the whole route unsupervised.
     * A flight is always started explicitly ({@code .aqp fly ...}); {@code elytraPilot.enabled} is kept in
     * sync purely as a status mirror.
     */
    @Override
    public boolean enabledSetting() { return false; }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onStarting),
            of(ClientDeathEvent.class, this::onDeath)
        );
    }

    @Override
    public void onEnable() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        bounceStallTicks = 0;
        emergencyTicks = 0;
        fireworkCooldown = 0;
        groundTask = GroundTask.NONE;
        swapAttempts = 0;
        swapWaitTicks = 0;
        groundSettleTicks = 0;
        swapReturnPhase = Phase.TAKEOFF;
        ownsRegear = false;
        gearUpAttempts = 0;
        gearUpBestScore = Integer.MIN_VALUE;
        resupplyAttempts = 0;
        resupplyBestScore = Integer.MIN_VALUE;
        groundScanTick = Long.MIN_VALUE;

        var c = cfg();
        c.enabled = true; // status mirror; enabledSetting() never reads it back

        if (!c.hasTarget && !c.allowFreeFly) {
            error("ElytraPilot: refusing to start with no flight target - a targetless flight has no arrival "
                + "condition and would fly straight ahead until the elytra died. Use `.aqp fly to <x> <z>`, "
                + "or set elytraPilot.allowFreeFly if an open-ended heading flight is really what you want.");
            stopFlight();
            return;
        }
        if (!c.hasTarget) {
            warn("ElytraPilot: allowFreeFly is set and no target was given - this flight has NO arrival "
                + "condition. It ends only when the gear runs out or you disable it.");
        }

        if (!checkStockModuleConflicts()) { stopFlight(); return; }
        if (!checkProtocolGate()) { stopFlight(); return; }

        goPhase(Phase.PREFLIGHT);
    }

    @Override
    public void onDisable() {
        phase = Phase.IDLE;
        groundTask = GroundTask.NONE;
        cfg().enabled = false;
        clearGlideIfSafe();
        stopOwnedRegear();
    }

    // ---------------------------------------------------------------- lifecycle events

    /**
     * (Re)connect. The gate is re-checked here rather than only in {@link #onEnable} because a module can be
     * enabled before the client is connected, and because ViaVersion state can change between sessions. Any
     * phase that was mid-flight goes back through the pre-flight checklist rather than resuming blind.
     */
    private void onStarting(ClientBotTick.Starting event) {
        groundScanTick = Long.MIN_VALUE;
        if (!checkProtocolGate()) { stopFlight(); return; }
        if (phase == Phase.IDLE || phase == Phase.DONE) return;
        info("Reconnected mid-flight - re-running the pre-flight check before resuming.");
        stopOwnedRegear();
        groundTask = GroundTask.NONE;
        goPhase(Phase.PREFLIGHT);
    }

    /**
     * Death. AutoRespawn puts the bot back at spawn with an empty inventory; resuming the flight from there
     * would just walk it forward forever, so the autopilot stops and says so.
     */
    private void onDeath(ClientDeathEvent event) {
        error("ElytraPilot: the bot died mid-flight - disabling the autopilot. Its gear is wherever it fell.");
        inGameAlertActivePlayer("<red>ElytraPilot: died mid-flight - autopilot disabled");
        stopFlight();
    }

    /** Turns the module off <i>and</i> clears the persisted status mirror so nothing restarts on its own. */
    private void stopFlight() {
        cfg().enabled = false;
        disable();
    }

    // ---------------------------------------------------------------- start-up gates

    /** @return false if a stock module makes flying unsafe (the caller must refuse to start). */
    private boolean checkStockModuleConflicts() {
        var extra = CONFIG.client.extra;
        boolean ok = true;
        if (extra.autoArmor.enabled) {
            error("=====================================================================");
            error(" ElytraPilot: AutoArmor is ENABLED. It fills the chest slot with the");
            error(" best chestplate it can find - which means it will strip the worn");
            error(" elytra mid-flight and drop the bot out of the sky.");
            error(" Refusing to fly. Run: autoArmor off");
            error("=====================================================================");
            ok = false;
        }
        int flight = cfg().inputPriority;
        if (extra.antiafk.enabled) {
            int p = stockPriority(extra.antiafk.priority, 5000);
            warn("ElytraPilot: AntiAFK is enabled (it is on by DEFAULT). Its walk/rotate inputs fight the "
                + "flight and its 'reached goal' test can never match while airborne. The flight input "
                + "priority ({}) outranks its movement inputs ({}), but its periodic swing uses priority {} "
                + "and will still steal a tick. Run: antiafk off", flight, p, p * 10);
        }
        if (extra.autoEat.enabled) {
            int p = stockPriority(extra.autoEat.priority, 11000);
            warn("ElytraPilot: AutoEat is enabled (it is on by DEFAULT). It holds a no-input for ~50 ticks "
                + "while eating, which drops the bounce. The flight input priority ({}) outranks it ({}), so "
                + "the bot will NOT eat while flying - start the trip fed. Run: autoEat off", flight, p);
        }
        return ok;
    }

    private static int stockPriority(@Nullable Integer configured, int fallback) {
        return Objects.requireNonNullElse(configured, fallback);
    }

    /**
     * Is an <i>active</i> ViaVersion downgrade to 1.20.3-1.20.4 (protocol 765) in place on the
     * zenith-&gt;server link? Checking {@code protocolVersion == 765} alone is a false positive: 765 is the
     * stock default, so an untouched config passes while ViaVersion is switched off entirely, disabled for
     * 2b2t, or in "auto" mode (which ignores the field).
     */
    public static boolean viaGateOk() {
        var via = CONFIG.client.viaversion;
        if (!via.enabled) return false;
        if (via.disableOn2b2t && Proxy.getInstance().isOn2b2t()) return false;
        if (via.autoProtocolVersion) return false;
        return via.protocolVersion == 765;
    }

    /** @return false if the module must not run (gate failed and requireProtocolCheck is on). */
    private boolean checkProtocolGate() {
        if (viaGateOk()) return true;
        var via = CONFIG.client.viaversion;
        warn("=====================================================================");
        warn(" ElytraPilot e-bounce needs an ACTIVE ViaVersion downgrade to");
        warn(" 1.20.3-1.20.4 (protocol 765) on the zenith->server link. Right now:");
        warn("   viaversion.enabled             = {}  (needs true)", via.enabled);
        warn("   viaversion.disableOn2b2t       = {}  (needs false while on 2b2t; on2b2t={})",
            via.disableOn2b2t, Proxy.getInstance().isOn2b2t());
        warn("   viaversion.autoProtocolVersion = {}  (needs false - 'auto' ignores the", via.autoProtocolVersion);
        warn("                                        version field below entirely)");
        warn("   viaversion.protocolVersion     = {}  (needs 765 - this is also the", via.protocolVersion);
        warn("                                        DEFAULT, so it proves nothing alone)");
        warn(" Run: via zenithToServer on");
        warn("      via zenithToServer disableOn2b2t off");
        warn("      via zenithToServer version 1.20.4");
        warn(" Flying WILL NOT work reliably without it.");
        warn("=====================================================================");
        if (cfg().requireProtocolCheck) {
            error("ElytraPilot: refusing to start - the ViaVersion downgrade is not active and "
                + "elytraPilot.requireProtocolCheck is true.");
            return false;
        }
        return true;
    }

    /**
     * The target dimension is a hard gate, not a hint: this plugin has no portal traversal, so flying an
     * overworld target while standing in the nether (or the reverse) means flying to the wrong place at 8x
     * the wrong scale.
     */
    private boolean checkDimension() {
        boolean inNether = "the_nether".equals(World.getCurrentDimension().name());
        if (inNether == cfg().targetIsNether) return true;
        error("ElytraPilot: the target is set for the {} but the bot is in the {}. This plugin has no portal "
            + "traversal - set elytraPilot.targetIsNether to match, or fly from the right dimension.",
            cfg().targetIsNether ? "nether" : "overworld", inNether ? "nether" : "overworld");
        inGameAlertActivePlayer("<red>ElytraPilot: target dimension does not match the bot's dimension");
        return false;
    }

    // ---------------------------------------------------------------- glide-state hold (the e-bounce redeploy)

    /**
     * Re-engages fall-flying for THIS tick's physics. Module {@link ClientBotTick} subscribers run earlier
     * in the same serialized dispatch than {@code Bot}'s own tick, and {@code Bot} re-derives its
     * fall-flying state at the start of its tick from the cached self-entity metadata bit this writes — so
     * a write here is consumed by the physics step of the very same tick.
     *
     * <p>Deliberately NOT gated on {@code BOT.isFallFlying()}: at module-subscriber time that getter still
     * holds the previous tick's derived value; the metadata cache is the current state. On a false→true
     * transition of the cached bit this also sends the deploy packet, mirroring the bot's own deploy path.
     */
    private void holdBounceGlide(boolean onGround, double y) {
        var c = cfg();
        if (onGround) return;
        if (y < c.roadY + c.bounceDeployHeight) return; // too low - the server won't accept a deploy here
        // only re-engage at/near the apex: the rise stays ballistic (full gravity, low apex), the glide
        // catches the descent
        if (BOT.getVelocity().getY() >= c.bounceRedeployMaxVy) return;
        deployGlide();
    }

    /** Keep (or re-engage) an ordinary glide while airborne — no bounce geometry, used by every descent. */
    private void holdGlide() {
        if (BOT.isOnGround()) return;
        deployGlide();
    }

    private void deployGlide() {
        if (!elytraUsable()) return;
        if (glideBitSet()) return; // still gliding - nothing to re-engage
        setGlideBit(true);
        sendClientPacketAsync(new ServerboundPlayerCommandPacket(
            CACHE.getPlayerCache().getEntityId(), PlayerState.START_ELYTRA_FLYING));
    }

    /** The cached self-entity fall-flying flag (shared flags byte, metadata index 0, bit 0x80). */
    private boolean glideBitSet() {
        return CACHE.getPlayerCache().getThePlayer().getMetadata().get(0) instanceof ByteEntityMetadata b
            && (b.getPrimitiveValue() & 0x80) != 0;
    }

    /** Sets/clears the cached fall-flying flag — the value {@code Bot} derives its glide state from. */
    private void setGlideBit(boolean glide) {
        var metadata = CACHE.getPlayerCache().getThePlayer().getMetadata();
        if (metadata.get(0) instanceof ByteEntityMetadata b) {
            byte v = b.getPrimitiveValue();
            b.setValue(glide ? (byte) (v | 0x80) : (byte) (v & ~0x80));
        } else if (glide) {
            metadata.put(0, new ByteEntityMetadata(0, MetadataTypes.BYTE, (byte) 0x80));
        }
    }

    /**
     * Drops the glide bit on any exit where it could be a leftover of OUR forcing — but never mid-air while
     * a real, server-agreed glide is in progress. The bit is the local physics sim's copy of a state the
     * server also holds: clearing it while genuinely gliding makes {@code Bot} simulate a free-fall the
     * server never agreed to, which on an anticheat server is a setback, not a safety measure.
     */
    private void clearGlideIfSafe() {
        if (BOT.isOnGround() || !elytraUsable()) setGlideBit(false);
    }

    /**
     * The bot's own can-glide check, via public API: not creative-flying, not in water, not in a vehicle, no
     * levitation effect, and a usable (not fully damaged) elytra in the chestplate slot. Mirrors
     * {@code Bot#canGlide} + {@code Bot#tryToStartFallFlying} — the module must not force the bit into a
     * state the server will refuse.
     */
    private boolean elytraUsable() {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (CACHE.getPlayerCache().isFlying()) return false;   // creative/spectator flight
        if (BOT.isTouchingWater()) return false;
        if (player.isInVehicle()) return false;
        if (player.getPotionEffectMap().get(Effect.LEVITATION) != null) return false;
        ItemStack chest = CACHE.getPlayerCache().getEquipment(EquipmentSlot.CHESTPLATE);
        if (chest == Container.EMPTY_STACK) return false;
        ItemData itemData = ItemRegistry.REGISTRY.get(chest.getId());
        if (itemData != ItemRegistry.ELYTRA) return false;
        var damage = chest.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        if (damage == null) return true;
        var maxDamage = itemData.components().get(DataComponentTypes.MAX_DAMAGE);
        return maxDamage == null || damage < maxDamage;
    }

    // ---------------------------------------------------------------- main tick

    private void onTick(ClientBotTick event) {
        tickCounter++;
        if (fireworkCooldown > 0) fireworkCooldown--;
        if (notReady()) return; // deliberately does NOT advance phaseTicks: a stalled world is not progress
        phaseTicks++;
        switch (phase) {
            case PREFLIGHT -> tickPreflight();
            case TAKEOFF -> tickTakeoff();
            case BOUNCE -> tickBounce();
            case CRUISE -> tickCruise();
            case DESCEND -> tickDescend();
            case SWAP_ELYTRA -> tickSwapElytra();
            case RESUPPLY -> tickResupply();
            case LAND -> tickLand();
            case EMERGENCY -> tickEmergency();
            case DONE, IDLE -> { }
        }
    }

    /**
     * True when the bot cannot meaningfully act this tick: dead, its own chunk not loaded (just
     * (re)connected, or a chunk-unload hiccup), or riding something.
     *
     * <p>An open container means some other subsystem is mid-inventory-op, and the flight defers to it — but
     * only <b>on the ground</b>. Refusing to tick while airborne would stop the glide hold and drop the bot
     * out of the sky, which is a far worse outcome than fighting over a chest. A container Regear opened on
     * our behalf is not a conflict at all.
     */
    private boolean notReady() {
        var p = CACHE.getPlayerCache();
        if (!p.isAlive()) return true;
        if (!World.isChunkLoadedBlockPos(MathHelper.floorI(p.getX()), MathHelper.floorI(p.getZ()))) return true;
        if (p.getThePlayer().isInVehicle()) return true;
        if (ownsRegear || phase == Phase.RESUPPLY) return false;
        return BOT.isOnGround() && p.getInventoryCache().getOpenContainerId() != 0;
    }

    /** Resets every per-phase counter — leftovers used to trip the stall/emergency logic on the next phase. */
    private void goPhase(Phase p) {
        phase = p;
        phaseTicks = 0;
        bounceStallTicks = 0;
        emergencyTicks = 0;
        swapWaitTicks = 0;
        groundSettleTicks = 0;
        // Both ground stops must not carry a forced glide bit into their inventory work. Guarded on
        // isOnGround so a defensive entry from the air can never turn a live glide into a free-fall.
        if ((p == Phase.RESUPPLY || p == Phase.SWAP_ELYTRA) && BOT.isOnGround()) setGlideBit(false);
    }

    // ---------------------------------------------------------------- pre-flight

    private void tickPreflight() {
        var c = cfg();
        if (!checkDimension()) { stopFlight(); return; }

        if (FlightGear.ready()) {
            info("Pre-flight check passed:\n{}", FlightGear.report());
            gearUpAttempts = 0;
            stopOwnedRegear();
            goPhase(Phase.TAKEOFF);
            return;
        }
        if (!c.autoGearUp) {
            error("Pre-flight check failed and elytraPilot.autoGearUp is false:\n{}", FlightGear.report());
            stopFlight();
            return;
        }
        if (!BOT.isOnGround()) {
            // Regear places an ender chest beside the bot; in mid-air that always aborts. Come down first.
            groundTask = GroundTask.PREFLIGHT;
            goPhase(Phase.DESCEND);
            return;
        }
        // A spent worn elytra is a swap, not a shopping trip: Regear's GEAR_UP only fills an EMPTY chest slot
        // (and its refill mode never touches the worn one), so without this the checklist could never clear.
        if (wornElytraNearDeath() && findFreshElytraSlot() != -1) {
            info("Pre-flight: the worn elytra is spent - swapping in a fresh one before anything else.");
            swapReturnPhase = Phase.PREFLIGHT;
            goPhase(Phase.SWAP_ELYTRA);
            return;
        }
        var regear = MODULE.get(RegearModule.class);
        if (regear == null) { error("RegearModule not registered - cannot auto gear-up."); stopFlight(); return; }

        if (regear.isPaused()) {
            error("ElytraPilot: the gear-up Regear run aborted - not flying.\n{}", FlightGear.report());
            inGameAlertActivePlayer("<red>ElytraPilot: gear-up aborted - not flying");
            stopOwnedRegear();
            stopFlight();
            return;
        }
        if (regear.isEnabled() && !regear.isComplete()) {
            // still working - only the timeout below applies
            if (phaseTicks > c.groundPhaseTimeoutTicks) {
                error("ElytraPilot: the gear-up has been running for {}s with no result - giving up.",
                    c.groundPhaseTimeoutTicks / 20);
                inGameAlertActivePlayer("<red>ElytraPilot: gear-up timed out - not flying");
                stopOwnedRegear();
                stopFlight();
            }
            return;
        }

        // A run finished (or none has started) and the checklist is still short. Only spend another run if
        // the last one actually moved the needle - otherwise this loops forever, re-placing the ender chest
        // (and, with selfKillRelocate, self-killing) on every cycle.
        int score = gearScore();
        if (gearUpAttempts > 0 && score <= gearUpBestScore) {
            error("ElytraPilot: gear-up run {} did not improve the checklist at all - giving up rather than "
                + "looping the ender-chest cycle.\n{}", gearUpAttempts, FlightGear.report());
            inGameAlertActivePlayer("<red>ElytraPilot: gear-up made no progress - not flying");
            stopOwnedRegear();
            stopFlight();
            return;
        }
        if (gearUpAttempts >= c.gearUpMaxAttempts) {
            error("ElytraPilot: gave up gearing up after {} Regear run(s).\n{}", gearUpAttempts, FlightGear.report());
            inGameAlertActivePlayer("<red>ElytraPilot: could not complete the pre-flight checklist - not flying");
            stopOwnedRegear();
            stopFlight();
            return;
        }
        gearUpBestScore = Math.max(gearUpBestScore, score);
        gearUpAttempts++;
        stopOwnedRegear(); // clean slate between runs
        info("Pre-flight check failed - starting Regear to gear up (attempt {}/{}):\n{}",
            gearUpAttempts, c.gearUpMaxAttempts, FlightGear.report());
        startOwnedRegear(regear);
        phaseTicks = 0; // each run gets the full timeout budget
    }

    /**
     * A monotone "how much of the checklist is met" number. Used only to answer "did the last Regear run
     * improve anything?" — an unsatisfiable checklist must not be retried forever.
     */
    private int gearScore() {
        var c = cfg();
        return (FlightGear.elytraWornFresh() ? 1000 : 0)
            + Math.min(FlightGear.freshElytraCount(), Math.max(1, c.preflightMinElytras)) * 100
            + Math.min(FlightGear.armorPiecesAnywhere(), c.preflightMinArmor) * 20
            + Math.min(FlightGear.echestCount(), c.preflightMinEchests) * 20
            + Math.min(FlightGear.totemCount(), c.preflightMinTotems) * 10
            + Math.min(FlightGear.egapCount(), c.preflightMinEgaps) * 2
            + Math.min(FlightGear.fireworkCount(), c.preflightMinFireworks)
            + (FlightGear.offhandTotem() ? 5 : 0)
            + (FlightGear.hasPickaxe() ? 5 : 0);
    }

    // ---------------------------------------------------------------- Regear ownership

    /**
     * Starts a Regear run that this module owns.
     *
     * <p>The run is marked {@link RegearModule#setOneShot(boolean) one-shot} <i>before</i> it is enabled, so
     * Regear stops itself the moment the cycle finishes without consulting — or writing — any persisted
     * setting. Nothing in this module touches {@code PLUGIN_CONFIG.regear}: {@code regear.enabled} and
     * {@code regear.disableWhenDone} are the operator's, and a module borrowing Regear for one cycle has no
     * business persisting a change to either. A one-shot finish still sets {@code complete} and clears
     * {@code enabled} on the module object, which is exactly what {@link #tickPreflight} and
     * {@link #tickResupply} poll.
     */
    private void startOwnedRegear(RegearModule regear) {
        ownsRegear = true;
        regear.setOneShot(true);
        regear.setEnabled(true);
    }

    /** Stops a Regear run only if we started it. */
    private void stopOwnedRegear() {
        var regear = MODULE.get(RegearModule.class);
        if (ownsRegear && regear != null && regear.isEnabled()) regear.setEnabled(false);
        ownsRegear = false;
    }

    // ---------------------------------------------------------------- takeoff

    private void tickTakeoff() {
        var p = CACHE.getPlayerCache();
        if (BOT.isOnGround()) {
            submitMove(true, true, true, false, desiredYaw(), 0f);
        } else {
            if (!BOT.isFallFlying()) {
                sendClientPacketAsync(new ServerboundPlayerCommandPacket(p.getEntityId(), PlayerState.START_ELYTRA_FLYING));
            }
            submitMove(true, false, false, false, desiredYaw(), 20f);
            if (BOT.isFallFlying() || phaseTicks > 100) {
                if (cfg().bounceEnabled) {
                    goPhase(Phase.BOUNCE);
                    info("Takeoff complete - entering e-bounce.");
                } else {
                    goPhase(Phase.CRUISE);
                    info("Takeoff complete - entering cruise.");
                }
                return;
            }
        }
        if (phaseTicks > 200) { warn("Takeoff timed out - descending."); goPhase(Phase.EMERGENCY); }
    }

    // ---------------------------------------------------------------- e-bounce

    private void tickBounce() {
        var c = cfg();
        double x = CACHE.getPlayerCache().getX(), y = CACHE.getPlayerCache().getY(), z = CACHE.getPlayerCache().getZ();
        float yaw = desiredYaw();

        if (y < c.roadY - c.roadDropAbort) {
            int below = (int) Math.round(c.roadY - y);
            if (c.recoverFromDrop) {
                warn("Bounce: {} blocks below the road - climbing back with a normal glide.", below);
                goPhase(Phase.CRUISE);
            } else {
                warn("Bounce: {} blocks below the road and recoverFromDrop is off - descending.", below);
                goPhase(Phase.EMERGENCY);
            }
            return;
        }

        if (manageElytraWear()) return; // handed the tick off to a ground stop

        boolean onGround = BOT.isOnGround();
        if (c.bounceClearOnGround && onGround) setGlideBit(false);

        double bps = velocityBps();
        if (bps < c.bounceStallSpeed) {
            if (++bounceStallTicks > c.bounceStallLimit) {
                bounceStallTicks = 0;
                if (c.passObstacles) {
                    warn("Bounce stalled (no forward progress) - climbing over via cruise, then resuming bounce.");
                    goPhase(Phase.CRUISE);
                } else {
                    warn("Bounce stalled and passObstacles is off - descending.");
                    goPhase(Phase.EMERGENCY);
                }
                return;
            }
        } else {
            bounceStallTicks = 0;
        }

        // Re-engage fall-flying for this tick's physics - Bot's own tick runs later in this same serialized
        // dispatch and consumes the glide state written here (see holdBounceGlide).
        holdBounceGlide(onGround, y);

        boolean wantSprint = bps < c.bounceSpeed;
        double aboveRoad = y - c.roadY;
        float pitch;
        if (c.bounceConstantPitchOnDiagonal && isDiagonalHighway()) {
            pitch = c.bounceDiagonalPitch;
        } else {
            pitch = aboveRoad <= c.bounceDiveHeight
                ? c.bouncePitch
                : (float) Math.min(c.bounceDivePitch, (aboveRoad - c.bounceDiveHeight) * c.bounceDiveGain);
        }
        submitMove(true, true, wantSprint, false, yaw, pitch);

        if (c.bounceDebug) {
            // dy is relative to the configured road height, never an absolute position
            info("[bounce] og={} glide={} dy={} bps={} vy={} sprint={} pitch={} stall={}",
                onGround, glideBitSet(), String.format("%+.2f", aboveRoad),
                String.format("%.1f", bps), String.format("%+.3f", BOT.getVelocity().getY()),
                wantSprint, pitch, bounceStallTicks);
        }

        checkArrival(x, z);
    }

    private boolean isDiagonalHighway() {
        // Simplified: without the full highway-graph snap, treat a non-axis-aligned heading toward the
        // target as "diagonal". Good enough for the constant-pitch tuning knob; see ROADMAP.md.
        if (!cfg().hasTarget) return false;
        double dx = cfg().targetX - CACHE.getPlayerCache().getX();
        double dz = cfg().targetZ - CACHE.getPlayerCache().getZ();
        return Math.abs(Math.abs(dx) - Math.abs(dz)) < Math.max(Math.abs(dx), Math.abs(dz)) * 0.1;
    }

    // ---------------------------------------------------------------- firework cruise

    private void tickCruise() {
        var c = cfg();
        double x = CACHE.getPlayerCache().getX(), z = CACHE.getPlayerCache().getZ();
        float yaw = desiredYaw();
        double bps = velocityBps();

        if (!BOT.isFallFlying()) {
            sendClientPacketAsync(new ServerboundPlayerCommandPacket(
                CACHE.getPlayerCache().getEntityId(), PlayerState.START_ELYTRA_FLYING));
        }

        // Rising edge + cooldown. A rocket's boost lasts ~2s; holding rightClick down burns a stack in three
        // seconds and the stacked boosts look exactly like a speed hack.
        boolean fire = false;
        if (bps < c.cruiseMinSpeed && fireworkCooldown <= 0 && ensureFireworkHeld()) {
            fire = true;
            fireworkCooldown = Math.max(20, c.fireworkCooldownTicks);
        }
        submitMove(true, false, false, fire, yaw, c.cruisePitch);

        if (manageElytraWear()) return;

        // resume the bounce once back over the road, if the bounce is what we were doing before
        if (c.bounceEnabled && CACHE.getPlayerCache().getY() >= c.roadY - 1 && phaseTicks > 40) {
            info("Cruise: back over the road - resuming bounce.");
            goPhase(Phase.BOUNCE);
            return;
        }
        checkArrival(x, z);
    }

    // ---------------------------------------------------------------- elytra wear management

    /**
     * True if this tick was handed off to a ground stop (the caller must stop its own tick immediately).
     *
     * <p>Everything here lands the bot FIRST. Regear places an ender chest beside itself, which can only
     * work on the ground, and an inventory swap while the bounce input is latched is a good way to fall out
     * of the sky.
     */
    private boolean manageElytraWear() {
        var c = cfg();

        // 1) the WORN elytra is nearly dead. Nothing else matters - one more failure and the bot free-falls.
        if (wornElytraNearDeath()) {
            if (findFreshElytraSlot() != -1) {
                info("ElytraPilot: the worn elytra is nearly spent - landing to swap in a fresh one.");
                groundTask = GroundTask.SWAP_ELYTRA;
                goPhase(Phase.DESCEND);
                return true;
            }
            if (canResupply()) {
                info("ElytraPilot: the worn elytra is nearly spent and there is no fresh spare - landing to resupply.");
                groundTask = GroundTask.RESUPPLY;
                goPhase(Phase.DESCEND);
                return true;
            }
            warn("ElytraPilot: the worn elytra is nearly spent and there is nothing to replace it with - landing now.");
            inGameAlertActivePlayer("<yellow>ElytraPilot: worn elytra nearly spent - landing");
            goPhase(Phase.LAND);
            return true;
        }

        // 2) running low on FRESH spares - top up while the worn one still has life left in it
        if (!c.resupplyFromEchest) return false;
        int spares = countFreshElytraSpares();
        if (spares > c.resupplySpareThreshold) return false;
        if (!canResupply()) return false;
        info("ElytraPilot: down to {} fresh spare elytra(s) - landing to resupply from the carried ender chest.", spares);
        groundTask = GroundTask.RESUPPLY;
        goPhase(Phase.DESCEND);
        return true;
    }

    private boolean canResupply() {
        if (FlightGear.echestCount() <= 0) return false; // no echest carried - nothing to resupply from
        if (MODULE.get(RegearModule.class) == null) return false;
        return resupplyAttempts < cfg().gearUpMaxAttempts;
    }

    /** Durability left on an item, or {@link Integer#MAX_VALUE} for items that do not take damage. */
    private int remainingDurability(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return 0;
        ItemData data = ItemRegistry.REGISTRY.get(s.getId());
        if (data == null) return 0;
        Integer maxDamage = data.components().get(DataComponentTypes.MAX_DAMAGE);
        if (maxDamage == null) return Integer.MAX_VALUE;
        Integer damage = s.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        return maxDamage - (damage == null ? 0 : damage);
    }

    private boolean isFreshElytra(@Nullable ItemStack s) {
        return FlightGear.isElytra(s) && remainingDurability(s) > cfg().freshElytraMinDurability;
    }

    /**
     * Spare elytras in the bag that are actually flyable. Without the durability filter this counted dead
     * elytras as spares, so {@code resupplySpareThreshold} was satisfied by a bag full of scrap and the bot
     * flew its worn one to zero.
     */
    private int countFreshElytraSpares() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 44; i++) if (isFreshElytra(inv.get(i))) n++;
        return n;
    }

    /** Player-inventory slot (9-44) of a fresh spare elytra, or -1. */
    private int findFreshElytraSlot() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) if (isFreshElytra(inv.get(i))) return i;
        return -1;
    }

    private boolean wornElytraNearDeath() {
        ItemStack chest = CACHE.getPlayerCache().getEquipment(EquipmentSlot.CHESTPLATE);
        return FlightGear.isElytra(chest) && remainingDurability(chest) <= cfg().freshElytraMinDurability;
    }

    private boolean wornElytraFresh() {
        return isFreshElytra(CACHE.getPlayerCache().getEquipment(EquipmentSlot.CHESTPLATE));
    }

    // ---------------------------------------------------------------- ground stops

    /** Controlled glide down to whatever the bot has to do on the ground. */
    private void tickDescend() {
        if (descendTick(desiredYaw())) {
            switch (groundTask) {
                case SWAP_ELYTRA -> { swapReturnPhase = Phase.TAKEOFF; goPhase(Phase.SWAP_ELYTRA); }
                case RESUPPLY -> beginResupply();
                case PREFLIGHT -> { groundTask = GroundTask.NONE; goPhase(Phase.PREFLIGHT); }
                case NONE -> goPhase(cfg().bounceEnabled ? Phase.BOUNCE : Phase.CRUISE);
            }
            return;
        }
        if (phaseTicks > cfg().descentTimeoutTicks) {
            warn("Descent for a ground stop timed out - switching to the emergency descent.");
            goPhase(Phase.EMERGENCY);
        }
    }

    /**
     * Swaps a fresh spare elytra into the chest slot, on the ground, and verifies the equipment slot really
     * changed before flying again. Regear's refill mode deliberately never touches the worn elytra, so
     * without this the bot rides one to zero with a bag full of spares.
     */
    private void tickSwapElytra() {
        float yaw = CACHE.getPlayerCache().getYaw();
        if (!BOT.isOnGround()) {
            // Defensive only - every entry into this phase is from the ground. Keep gliding rather than
            // stalling here, and hand back to a proper controlled descent.
            holdGlide();
            submitMove(true, false, false, false, yaw, 8f);
            if (phaseTicks > 40) { groundTask = GroundTask.SWAP_ELYTRA; goPhase(Phase.DESCEND); }
            return;
        }
        setGlideBit(false);
        submitMove(false, false, false, false, yaw, 0f);

        if (wornElytraFresh()) { // verified: the equipment slot actually changed
            info("Worn elytra replaced with a fresh spare.");
            swapAttempts = 0;
            groundTask = GroundTask.NONE;
            goPhase(swapReturnPhase);
            swapReturnPhase = Phase.TAKEOFF;
            return;
        }
        if (swapWaitTicks > 0) { swapWaitTicks--; return; }
        if (INVENTORY.hasActiveRequest()) return;

        int fresh = findFreshElytraSlot();
        if (fresh == -1) {
            warn("ElytraPilot: no fresh spare elytra to swap in.");
            if (canResupply()) { beginResupply(); return; }
            error("ElytraPilot: out of usable elytras and nothing to resupply from - stopping on the ground.");
            inGameAlertActivePlayer("<red>ElytraPilot: out of usable elytras - stopped");
            stopFlight();
            return;
        }
        if (++swapAttempts > MAX_SWAP_ATTEMPTS) {
            error("ElytraPilot: the worn-elytra swap did not take after {} attempts - stopping on the ground.",
                MAX_SWAP_ATTEMPTS);
            inGameAlertActivePlayer("<red>ElytraPilot: could not swap in a fresh elytra - stopped");
            stopFlight();
            return;
        }
        INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .actions(InventoryActionMacros.swapSlots(fresh, CHEST_EQUIP_SLOT))
            .priority(INVENTORY_PRIORITY).build());
        swapWaitTicks = Math.max(4, AquariusPilotPlugin.PLUGIN_CONFIG.regear.actionDelayTicks * 2);
    }

    private void beginResupply() {
        var regear = MODULE.get(RegearModule.class);
        if (regear == null) { error("RegearModule not registered - cannot resupply."); goPhase(Phase.LAND); return; }
        int score = gearScore();
        if (resupplyAttempts > 0 && score <= resupplyBestScore) {
            error("ElytraPilot: the last resupply did not improve anything - giving up rather than looping the "
                + "ender-chest cycle. Stopping on the ground.");
            inGameAlertActivePlayer("<red>ElytraPilot: resupply made no progress - stopped");
            stopFlight();
            return;
        }
        if (resupplyAttempts >= cfg().gearUpMaxAttempts) {
            error("ElytraPilot: gave up resupplying after {} attempt(s). Stopping on the ground.", resupplyAttempts);
            inGameAlertActivePlayer("<red>ElytraPilot: resupply gave up - stopped");
            stopFlight();
            return;
        }
        resupplyBestScore = Math.max(resupplyBestScore, score);
        resupplyAttempts++;
        stopOwnedRegear();
        regear.setElytraRefill(true, Math.max(2, cfg().preflightMinElytras));
        info("ElytraPilot: resupplying from the carried ender chest (attempt {}/{}).",
            resupplyAttempts, cfg().gearUpMaxAttempts);
        groundTask = GroundTask.NONE;
        goPhase(Phase.RESUPPLY);
        startOwnedRegear(regear);
    }

    private void tickResupply() {
        var c = cfg();
        // Release the controls explicitly. Bot latches the last Input it was handed, so without this the
        // bounce's forward+jump+sprint stays pressed for the whole ground cycle. Submitted at a LOW priority
        // so Regear's own Baritone pathing (7000) still outranks it.
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder()
                .hand(Hand.MAIN_HAND)
                .clickTarget(ClickTarget.None.INSTANCE)
                .clickRequiresRotation(false)
                .build())
            .priority(RELEASE_INPUT_PRIORITY)
            .build());

        var regear = MODULE.get(RegearModule.class);
        if (regear == null) { error("RegearModule vanished during resupply - descending."); goPhase(Phase.EMERGENCY); return; }

        // Paused is checked FIRST and unconditionally: Regear.abort() sets paused while staying ENABLED, so
        // the old "if (!isEnabled()) { if (isPaused()) ... }" nesting could never see an aborted run and the
        // flight hung mid-cycle forever, submitting nothing.
        if (regear.isPaused()) {
            error("ElytraPilot: the resupply Regear run aborted - stopping here.");
            stopOwnedRegear();
            goPhase(Phase.EMERGENCY);
            return;
        }
        if (regear.isComplete() || !regear.isEnabled()) {
            stopOwnedRegear();
            info("Resupply complete - taking off again.");
            groundTask = GroundTask.NONE;
            // A resupply that pulled fresh elytras but left a spent one worn still needs the swap.
            swapReturnPhase = Phase.TAKEOFF;
            goPhase(wornElytraNearDeath() && findFreshElytraSlot() != -1 ? Phase.SWAP_ELYTRA : Phase.TAKEOFF);
            return;
        }
        if (phaseTicks > c.groundPhaseTimeoutTicks) {
            error("ElytraPilot: the resupply has been running for {}s with no result - aborting to a controlled stop.",
                c.groundPhaseTimeoutTicks / 20);
            stopOwnedRegear();
            goPhase(Phase.EMERGENCY);
        }
    }

    // ---------------------------------------------------------------- landing

    private void checkArrival(double x, double z) {
        var c = cfg();
        if (!c.hasTarget) return;
        double dx = x - c.targetX, dz = z - c.targetZ;
        if (Math.hypot(dx, dz) <= c.arriveRadius) {
            info("Reached the target - landing.");
            // One-shot: clearing this here is what stops a persisted target from being re-flown later.
            c.hasTarget = false;
            goPhase(Phase.LAND);
        }
    }

    private void tickLand() {
        if (descendTick(CACHE.getPlayerCache().getYaw())) {
            info("Landed.");
            goPhase(Phase.DONE);
            if (cfg().goalLogout) {
                info("goalLogout enabled - disabling ElytraPilot. The logout itself is left to the operator / a "
                    + "process supervisor: this plugin deliberately does not disconnect a shared bot on its own.");
            }
            stopFlight();
            return;
        }
        if (phaseTicks > cfg().descentTimeoutTicks) {
            warn("Landing timed out - switching to the emergency descent.");
            goPhase(Phase.EMERGENCY);
        }
    }

    /**
     * One tick of a controlled elytra descent. Returns true once the bot is on the ground and settled.
     *
     * <p>The glide is held all the way down and only cut within {@code landingFlareHeight} of MEASURED
     * ground (see {@link #groundY()}); if the ground cannot be measured the glide is never cut, because the
     * previous behaviour — cutting against a fabricated {@code roadY - 60} — dropped the bot ~30 blocks up
     * over nether lava and never fired at all over overworld terrain above y≈63.
     */
    private boolean descendTick(float yaw) {
        var c = cfg();
        if (BOT.isOnGround()) {
            setGlideBit(false);
            submitMove(false, false, false, false, yaw, 0f);
            // settle counted from TOUCHDOWN, not from phase entry, so a long glide down doesn't skip it
            return ++groundSettleTicks > 20;
        }
        groundSettleTicks = 0;
        double y = CACHE.getPlayerCache().getY();
        double ground = groundY();
        boolean known = ground != GROUND_UNKNOWN;
        double alt = known ? y - ground : Double.MAX_VALUE;

        if (known && alt <= c.landingFlareHeight) {
            // touching down: drop the glide so vanilla landing rules apply over the last couple of blocks
            setGlideBit(false);
            submitMove(false, false, false, false, yaw, 0f);
            return false;
        }
        holdGlide();
        // positive pitch is nose-DOWN: sink while high, level off as the measured ground comes up, flare last
        float pitch = !known || alt > 40 ? 12f : alt > 12 ? 6f : alt > 4 ? 0f : -6f;
        submitMove(true, false, false, false, yaw, pitch);
        return false;
    }

    /**
     * Highest solid, non-fluid block surface under the bot, as a real downward world scan over the 3x3
     * column block around it (the max keeps a one-block hole from reading as "the ground is far away").
     *
     * @return the Y of the first standable surface, or {@link #GROUND_UNKNOWN} when any column's chunk is
     *         not loaded or nothing solid was found within {@code groundScanDepth} — callers must treat
     *         that as "keep gliding", never as a height.
     */
    private double groundY() {
        if (groundScanTick == tickCounter) return groundScanValue;
        groundScanTick = tickCounter;
        groundScanValue = scanGroundY();
        return groundScanValue;
    }

    private double scanGroundY() {
        var p = CACHE.getPlayerCache();
        int px = MathHelper.floorI(p.getX()), pz = MathHelper.floorI(p.getZ());
        int startY = MathHelper.floorI(p.getY());
        var dim = World.getCurrentDimension();
        int lo = Math.max(dim.minY(), startY - cfg().groundScanDepth);
        int hi = Math.min(startY, dim.buildHeight());
        double best = GROUND_UNKNOWN;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                int x = px + dx, z = pz + dz;
                // an unloaded column is unknown terrain, not empty terrain - fail safe and keep gliding
                if (!World.isChunkLoadedBlockPos(x, z)) return GROUND_UNKNOWN;
                for (int y = hi; y >= lo; y--) {
                    Block b = World.getBlock(x, y, z);
                    if (b.isAir() || World.isFluid(b) || !World.blocksMotion(b)) continue;
                    double surface = y + 1;
                    if (best == GROUND_UNKNOWN || surface > best) best = surface;
                    break;
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- emergency

    /**
     * Emergency descent. NOT a dive: the previous version pitched +40 (nose-down, since positive pitch is
     * down here) with no flare and no ground reference, which is a power dive into whatever is below. This
     * levels out to bleed vertical speed, keeps the glide alive, and flares against the measured ground.
     */
    private void tickEmergency() {
        var c = cfg();
        emergencyTicks++;
        float yaw = CACHE.getPlayerCache().getYaw();

        if (BOT.isOnGround()) {
            setGlideBit(false);
            submitMove(false, false, false, false, yaw, 0f);
            error("ElytraPilot: emergency stop complete - on the ground, disabling.");
            inGameAlertActivePlayer("<yellow>ElytraPilot: emergency landing complete - autopilot disabled");
            stopFlight();
            return;
        }

        double y = CACHE.getPlayerCache().getY();
        double ground = groundY();
        boolean known = ground != GROUND_UNKNOWN;
        double alt = known ? y - ground : Double.MAX_VALUE;

        if (known && alt <= c.landingFlareHeight) {
            setGlideBit(false);
            submitMove(false, false, false, false, yaw, 0f);
        } else {
            holdGlide();
            // level (0) sheds vertical speed without gaining distance; a small NEGATIVE pitch flares
            float pitch = !known || alt > 24 ? 0f : -8f;
            submitMove(false, false, false, false, yaw, pitch);
        }

        if (emergencyTicks > c.emergencyTimeoutTicks) {
            error("ElytraPilot: emergency descent did not reach the ground within {}s - disabling where it is.",
                c.emergencyTimeoutTicks / 20);
            inGameAlertActivePlayer("<red>ElytraPilot: emergency descent timed out - autopilot disabled");
            stopFlight();
        }
    }

    // ---------------------------------------------------------------- movement / item helpers

    private void submitMove(boolean forward, boolean jump, boolean sprint, boolean fire, float yaw, float pitch) {
        INPUTS.submit(InputRequest.builder()
            .owner(this)
            .input(Input.builder()
                .pressingForward(forward)
                .jumping(jump)
                .sprinting(sprint)
                .rightClick(fire)
                .hand(Hand.MAIN_HAND)
                .clickTarget(ClickTarget.None.INSTANCE)
                .clickRequiresRotation(false)
                .build())
            .yaw(yaw)
            .pitch(pitch)
            .priority(cfg().inputPriority)
            .build());
    }

    private float desiredYaw() {
        if (!cfg().hasTarget) return CACHE.getPlayerCache().getYaw();
        double dx = cfg().targetX - CACHE.getPlayerCache().getX();
        double dz = cfg().targetZ - CACHE.getPlayerCache().getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** Horizontal distance to the target in blocks, or -1 when there is no target. Relative, never absolute. */
    public double distanceToTarget() {
        var c = cfg();
        if (!c.hasTarget) return -1;
        return Math.hypot(c.targetX - CACHE.getPlayerCache().getX(), c.targetZ - CACHE.getPlayerCache().getZ());
    }

    private double velocityBps() {
        var v = BOT.getVelocity();
        return Math.hypot(v.getX(), v.getZ()) * 20.0;
    }

    /** Ensure a firework is the held hotbar item; returns true once one is (already) held. */
    private boolean ensureFireworkHeld() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int held = 36 + CACHE.getPlayerCache().getHeldItemSlot();
        if (FlightGear.isFirework(inv.get(held))) return true;
        for (int i = 36; i <= 44; i++) {
            if (FlightGear.isFirework(inv.get(i))) {
                if (!INVENTORY.hasActiveRequest()) {
                    INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                        .actions(new SetHeldItem(i - 36)).priority(INVENTORY_PRIORITY).build());
                }
                return false; // will be held next tick(s)
            }
        }
        return false; // out of fireworks
    }
}
