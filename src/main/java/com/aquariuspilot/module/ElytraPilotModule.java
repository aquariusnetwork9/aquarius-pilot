package com.aquariuspilot.module;

import com.aquariuspilot.AquariusPilotPlugin;
import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.player.ClickTarget;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.type.ByteEntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BOT;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.EVENT_BUS;
import static com.zenith.Globals.INPUTS;
import static com.zenith.Globals.INVENTORY;
import static com.zenith.Globals.MODULE;

/**
 * ElytraPilot — long-haul elytra autopilot, ported (and substantially trimmed) from AquariusProxy's
 * {@code com.aquarius.module.impl.ElytraPilot} (~3,400 lines). This is a scope-reduced reimplementation, not
 * a line-for-line port: it keeps the headline flight loop — pre-flight gating, takeoff, the ground e-bounce,
 * a firework-sustained cruise glide, elytra-wear-aware mid-flight resupply via {@link RegearModule}, and
 * landing / an optional goal-stop logout — while deferring the fork's heavier machinery (nether-native
 * routing via {@link NetherRouter}, the ring-road grief reroute via the highway graph, the flight-angle
 * physics solver / obstacle pass, bed/anchor set-spawn, XP-bottle Mending choreography, AirPlace-based
 * escape portals). See the repo README's "Known limitations" and ROADMAP.md for the full list.
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
 */
public class ElytraPilotModule extends Module {

    public enum Phase { IDLE, PREFLIGHT, TAKEOFF, BOUNCE, CRUISE, RESUPPLY, LAND, DONE, EMERGENCY }

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private int bounceStallTicks;
    private int emergencyTicks;

    private static com.aquariuspilot.config.AquariusPilotConfig.ElytraPilot cfg() {
        return AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot;
    }

    public Phase getPhase() { return phase; }

    @Override
    public boolean enabledSetting() { return cfg().enabled; }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick)
        );
    }

    @Override
    public void onEnable() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        bounceStallTicks = 0;
        emergencyTicks = 0;

        // --- ViaVersion protocol requirement (see README "Requirements") ---
        var via = CONFIG.client.viaversion;
        boolean protocolOk = via.protocolVersion == 765;
        if (!protocolOk) {
            warn("=====================================================================");
            warn(" ElytraPilot e-bounce requires the outbound ViaVersion protocol set to");
            warn(" 1.20.3-1.20.4 (protocol 765). Current client.viaversion.protocolVersion={}", via.protocolVersion);
            warn(" Run: via zenithToServer version 1.20.4   (or set client.viaversion in");
            warn(" config.json) BEFORE enabling e-bounce. Flying WILL NOT work reliably");
            warn(" under any other protocol version.");
            warn("=====================================================================");
            if (cfg().requireProtocolCheck) {
                error("ElytraPilot: refusing to start - protocol check failed and elytraPilot.requireProtocolCheck is true.");
                disable();
                return;
            }
        }

        goPreflight();
    }

    @Override
    public void onDisable() {
        phase = Phase.IDLE;
        var regear = MODULE.get(RegearModule.class);
        if (regear != null && regear.isEnabled()) regear.setEnabled(false);
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
     * The essentials of the bot's own can-glide check, via public API: not in a vehicle, no levitation
     * effect, and a usable (not fully damaged) elytra in the chestplate slot.
     */
    private boolean elytraUsable() {
        var player = CACHE.getPlayerCache().getThePlayer();
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
        if (!CACHE.getPlayerCache().isAlive()) return;
        phaseTicks++;
        switch (phase) {
            case PREFLIGHT -> tickPreflight();
            case TAKEOFF -> tickTakeoff();
            case BOUNCE -> tickBounce();
            case CRUISE -> tickCruise();
            case RESUPPLY -> tickResupply();
            case LAND -> tickLand();
            case EMERGENCY -> tickEmergency();
            case DONE, IDLE -> { }
        }
    }

    private void goPhase(Phase p) { phase = p; phaseTicks = 0; }

    private void goPreflight() { goPhase(Phase.PREFLIGHT); }

    private void tickPreflight() {
        if (FlightGear.ready()) {
            info("Pre-flight check passed:\n{}", FlightGear.report());
            goPhase(Phase.TAKEOFF);
            return;
        }
        if (!cfg().autoGearUp) {
            error("Pre-flight check failed and elytraPilot.autoGearUp is false:\n{}", FlightGear.report());
            disable();
            return;
        }
        var regear = MODULE.get(RegearModule.class);
        if (regear == null) { error("RegearModule not registered - cannot auto gear-up."); disable(); return; }
        if (!regear.isEnabled()) {
            info("Pre-flight check failed - starting Regear to gear up:\n{}", FlightGear.report());
            AquariusPilotPlugin.PLUGIN_CONFIG.regear.disableWhenDone = true;
            regear.setEnabled(true);
            return;
        }
        if (regear.isPaused()) {
            error("ElytraPilot: Regear paused during auto gear-up - aborting flight.");
            disable();
        }
        // else: still running, wait
    }

    private void tickTakeoff() {
        var p = CACHE.getPlayerCache();
        boolean onGround = BOT.isOnGround();
        if (onGround) {
            submitMove(true, true, true, false, desiredYaw(), 0f);
        } else {
            if (!BOT.isFallFlying()) {
                sendClientPacketAsync(new ServerboundPlayerCommandPacket(p.getEntityId(), PlayerState.START_ELYTRA_FLYING));
            }
            submitMove(true, false, false, false, desiredYaw(), 20f);
            if (BOT.isFallFlying() || phaseTicks > 100) {
                if (cfg().bounceEnabled) {
                    bounceStallTicks = 0;
                    goPhase(Phase.BOUNCE);
                    info("Takeoff complete - entering e-bounce.");
                } else {
                    goPhase(Phase.CRUISE);
                    info("Takeoff complete - entering cruise.");
                }
            }
        }
        if (phaseTicks > 200) { warn("Takeoff timed out - aborting."); goPhase(Phase.EMERGENCY); }
    }

    // ---------------------------------------------------------------- e-bounce

    private void tickBounce() {
        var c = cfg();
        double x = CACHE.getPlayerCache().getX(), y = CACHE.getPlayerCache().getY(), z = CACHE.getPlayerCache().getZ();
        float yaw = desiredYaw();

        if (y < c.roadY - c.roadDropAbort) {
            if (c.recoverFromDrop) {
                warn("Bounce: dropped below the road (y={} < {}) - climbing back with a normal glide.", (int) y, c.roadY);
                goPhase(Phase.CRUISE);
            } else {
                warn("Bounce: dropped below the road and recoverFromDrop is off - emergency.");
                goPhase(Phase.EMERGENCY);
            }
            return;
        }

        if (manageElytraWear()) return; // may have entered RESUPPLY

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
                    warn("Bounce stalled and passObstacles is off - emergency.");
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
            info("[bounce] og={} glide={} y={} bps={} sprint={} pitch={}", onGround, glideBitSet(), String.format("%.2f", y),
                String.format("%.1f", bps), wantSprint, pitch);
        }

        checkArrival(x, z);
    }

    private boolean isDiagonalHighway() {
        // Simplified: without the full highway-graph snap, treat a non-axis-aligned heading toward the
        // target as "diagonal". Good enough for the constant-pitch tuning knob; see ROADMAP.md.
        double dx = cfg().targetX - CACHE.getPlayerCache().getX();
        double dz = cfg().targetZ - CACHE.getPlayerCache().getZ();
        return Math.abs(Math.abs(dx) - Math.abs(dz)) < Math.max(Math.abs(dx), Math.abs(dz)) * 0.1;
    }

    // ---------------------------------------------------------------- firework cruise

    private void tickCruise() {
        double x = CACHE.getPlayerCache().getX(), z = CACHE.getPlayerCache().getZ();
        float yaw = desiredYaw();
        double bps = velocityBps();

        if (!BOT.isFallFlying()) {
            sendClientPacketAsync(new ServerboundPlayerCommandPacket(
                CACHE.getPlayerCache().getEntityId(), PlayerState.START_ELYTRA_FLYING));
        }

        boolean fire = false;
        if (bps < cfg().cruiseMinSpeed) {
            if (ensureFireworkHeld()) fire = true;
        }
        submitMove(true, false, false, fire, yaw, cfg().cruisePitch);

        if (manageElytraWear()) return;

        // resume the bounce once back over the road, if the bounce is what we were doing before
        if (cfg().bounceEnabled && CACHE.getPlayerCache().getY() >= cfg().roadY - 1 && phaseTicks > 40) {
            info("Cruise: back over the road - resuming bounce.");
            goPhase(Phase.BOUNCE);
            return;
        }
        checkArrival(x, z);
    }

    // ---------------------------------------------------------------- resupply (mid-flight elytra refill)

    private void tickResupply() {
        var regear = MODULE.get(RegearModule.class);
        if (regear == null) { error("RegearModule missing during resupply - emergency."); goPhase(Phase.EMERGENCY); return; }
        if (!regear.isEnabled()) {
            if (regear.isPaused()) {
                error("ElytraPilot: resupply Regear paused - emergency landing.");
                goPhase(Phase.EMERGENCY);
                return;
            }
            info("Resupply complete - resuming flight.");
            goPhase(cfg().bounceEnabled ? Phase.BOUNCE : Phase.CRUISE);
        }
        // else: Regear still running this tick, nothing to do here
    }

    /** True if it just entered RESUPPLY (caller should stop its own tick this call). */
    private boolean manageElytraWear() {
        int spares = countFreshElytraSpares();
        if (!cfg().resupplyFromEchest) return false;
        if (spares > cfg().resupplySpareThreshold) return false;
        if (FlightGear.echestCount() <= 0) return false; // no echest carried - can't resupply mid-air
        var regear = MODULE.get(RegearModule.class);
        if (regear == null) return false;
        info("ElytraPilot: low on fresh elytras ({} spare) - pausing to resupply from the carried echest.", spares);
        regear.setElytraRefill(true, Math.max(2, cfg().preflightMinElytras));
        AquariusPilotPlugin.PLUGIN_CONFIG.regear.disableWhenDone = true;
        regear.setEnabled(true);
        goPhase(Phase.RESUPPLY);
        return true;
    }

    private int countFreshElytraSpares() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = inv.get(i);
            if (s == Container.EMPTY_STACK) continue;
            if (!FlightGear.isElytra(s)) continue;
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- landing

    private void checkArrival(double x, double z) {
        if (!cfg().hasTarget) return;
        double dx = x - cfg().targetX, dz = z - cfg().targetZ;
        if (Math.hypot(dx, dz) <= cfg().arriveRadius) {
            info("Reached target - landing.");
            goPhase(Phase.LAND);
        }
    }

    private void tickLand() {
        double y = CACHE.getPlayerCache().getY();
        float yaw = CACHE.getPlayerCache().getYaw();
        boolean onGround = BOT.isOnGround();
        if (onGround) {
            submitMove(false, false, false, false, yaw, 0f);
            if (phaseTicks > 20) {
                info("Landed.");
                goPhase(Phase.DONE);
                if (cfg().goalLogout) {
                    info("goalLogout enabled - disabling ElytraPilot and logging out.");
                    disable();
                    // Logout itself is left to the operator / a process supervisor: this plugin deliberately
                    // does not call System.exit or a disconnect here to avoid surprising a shared bot fleet.
                    // See README known limitations re: bed/anchor set-spawn + auto-relogin timers.
                } else {
                    disable();
                }
            }
        } else {
            // gentle, level-ish glide down; cut fall-flying near the ground so vanilla fall damage rules
            // (feather falling / elytra glide-to-ground) apply normally rather than free-falling
            submitMove(true, false, false, false, yaw, 15f);
            if (y - groundYGuess() < 3) setGlideBit(false);
        }
    }

    private double groundYGuess() {
        // Without a full ground-scan (kept out of scope), fall back to the configured road/target Y as the
        // best guess of "close to the ground" so the landing flare kicks in near it.
        return cfg().hasTarget ? Math.max(cfg().roadY - 60, 0) : cfg().roadY - 60;
    }

    // ---------------------------------------------------------------- emergency

    private void tickEmergency() {
        emergencyTicks++;
        double y = CACHE.getPlayerCache().getY();
        float yaw = CACHE.getPlayerCache().getYaw();
        if (!BOT.isFallFlying() && y > groundYGuess() + 5) {
            sendClientPacketAsync(new ServerboundPlayerCommandPacket(
                CACHE.getPlayerCache().getEntityId(), PlayerState.START_ELYTRA_FLYING));
        }
        submitMove(false, false, false, false, yaw, 40f); // dive gently, no forward - just get down safely
        if (BOT.isOnGround() || emergencyTicks > 600) {
            error("ElytraPilot: emergency landing complete - disabling.");
            disable();
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
            .priority(3500)
            .build());
    }

    private float desiredYaw() {
        if (!cfg().hasTarget) return CACHE.getPlayerCache().getYaw();
        double dx = cfg().targetX - CACHE.getPlayerCache().getX();
        double dz = cfg().targetZ - CACHE.getPlayerCache().getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    private double velocityBps() {
        var v = BOT.getVelocity();
        return Math.hypot(v.getX(), v.getZ()) * 20.0;
    }

    private boolean isFirework(ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return false;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        return d != null && "firework_rocket".equals(d.name());
    }

    /** Ensure a firework is the held hotbar item; returns true once one is (already) held. */
    private boolean ensureFireworkHeld() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int held = 36 + CACHE.getPlayerCache().getHeldItemSlot();
        if (isFirework(inv.get(held))) return true;
        for (int i = 36; i <= 44; i++) {
            if (isFirework(inv.get(i))) {
                if (!INVENTORY.hasActiveRequest()) {
                    INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                        .actions(new SetHeldItem(i - 36)).priority(3500).build());
                }
                return false; // will be held next tick(s)
            }
        }
        return false; // out of fireworks
    }
}
