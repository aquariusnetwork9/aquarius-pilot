package com.aquariuspilot.module;

import com.aquariuspilot.AquariusPilotPlugin;
import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.jspecify.annotations.Nullable;

import java.util.List;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INVENTORY;

/**
 * Regear — resupply the bot from a pre-stocked "kit" shulker held in an ender chest. Ported from
 * AquariusProxy's {@code com.aquarius.module.impl.Regear} onto stock ZenithProxy + this plugin's own
 * {@link AbstractFieldModule}, trimmed to drop: the named {@code KitProfile} override system (this plugin
 * has one flat kit config, not a profile registry), the ghost-hand no-LOS open (no stock equivalent), and
 * the "flight refill" mode used by AquariusProxy's separate trip-planner module (this plugin keeps the
 * standalone gear-up cycle and the e-bounce elytra-refill cycle, both of which ARE ported).
 *
 * <p>Cycle: place the bot's own carried ender chest beside it (or walk to a placed one), open it, pull the
 * kit shulker (falling back to a content-based cherry-pick search across every shulker in the chest if no
 * single shulker matches), empty it into the inventory, break + recover the emptied shulker, return it,
 * recover the ender chest with a silk-touch pickaxe, then equip armour + offhand totem.
 *
 * <p>Cherry-pick is a first-class storage layout, not just a fallback: with
 * {@code regear.singleItemShulkers} the primary kit match is skipped outright and every round pulls only
 * still-needed items out of the richest matching shulker, which is how separate single-item shulkers (an
 * elytra shulker, a rocket shulker, a boots shulker, ...) are meant to be consumed. What counts as
 * "still needed" is {@link #regearStillNeeds}, driven by the full pre-flight checklist.
 *
 * <h2>Two invariants worth knowing before editing this class</h2>
 * <ol>
 *   <li><b>Never leave a state that opened a window without closing it.</b> While a container is open
 *       {@code Bot#tick} resets every movement and interaction input (so nothing can be placed, broken or
 *       walked to) <i>and</i> {@code InventoryManager#executeNextAction} silently skips any action whose
 *       container id doesn't match the open window while still advancing its index — which turns a
 *       three-action swap macro into a two-action one that strands an item on the cursor. Every exit from a
 *       window state therefore routes through {@link #goAfterClose}, and the states that cannot survive an
 *       open window call {@link #blockedByOpenContainer()} as a backstop.</li>
 *   <li><b>Every "did it work?" check must be state-based.</b> {@code InventoryManager#submit} returns a
 *       rejected future when something higher-priority owns the queue (Regear runs at 3000; AutoTotem 13000,
 *       AutoArmor 12000, AutoEat 11000, KillAura 8000 and Baritone 7000 all outrank it), and
 *       {@link #inventoryBusy()} does not cover a submitted-but-not-yet-executing single action. So progress
 *       is only ever concluded from observed state: the equipment slot changed, the window opened, the block
 *       appeared, the item left the inventory.</li>
 * </ol>
 */
public class RegearModule extends AbstractFieldModule {

    private enum State {
        IDLE, RELOCATE, ACQUIRE, PLACE_ECHEST, PATH_ECHEST, OPEN_ECHEST, PULL_KIT, CLOSE_ECHEST,
        PLACE_KIT, OPEN_KIT, EMPTY_KIT, CLOSE_KIT, BREAK_KIT,
        RETURN_OPEN, RETURN_DEPOSIT, RETURN_CLOSE, CHERRY_CHECK, RECOVER_ECHEST, GEAR_UP,
        ABORT_CLEANUP, DONE
    }

    /** Ticks a state may sit blocked behind an open container before the run is abandoned. */
    private static final int OPEN_CONTAINER_BLOCK_LIMIT = 100;
    /** Attempts a normal-priority close gets before escalating to {@link #closeContainerForced()}. */
    private static final int CLOSE_ESCALATE_AFTER = 10;
    /** Total close attempts before giving up on a window entirely. */
    private static final int CLOSE_GIVE_UP_AFTER = 40;
    /** Ticks the cycle waits for the bot to be standing on the ground before giving up. */
    private static final int GROUND_WAIT_LIMIT = 600;
    /** Shift-clicks a single deposit / pull / empty phase may spend. */
    private static final int DEPOSIT_ATTEMPT_LIMIT = 10;
    private static final int PULL_ATTEMPT_LIMIT = 60;
    private static final int EMPTY_ATTEMPT_LIMIT = 200;
    /** Break attempts (one per tick-through) before a block is written off. */
    private static final int BREAK_ATTEMPT_LIMIT = 200;
    /** Tick-throughs spent chasing a broken container's drop. */
    private static final int PICKUP_WAIT_LIMIT = 60;
    /** Ticks spent walking back to the ender chest to return the kit. */
    private static final int RETURN_PATH_LIMIT = 40;
    /** Re-submits of a single equip swap before the slot is written off. */
    private static final int EQUIP_ATTEMPT_LIMIT = 6;
    /** Ticks to wait for an equip swap to show up in the equipment slot before re-submitting. */
    private static final int EQUIP_WAIT_TICKS = 20;
    /** How far the drop chase may wander from where the container was broken before it's abandoned. */
    private static final double PICKUP_ABANDON_RANGE = 24.0;

    private State state = State.IDLE;
    private int step;
    private int timer;
    private int attempts;
    /** A second per-state counter, for states that bound two different things and whose steps loop back on
     *  each other — sharing {@link #attempts} there resets the cap and turns the retry into a spin. */
    private int auxAttempts;

    private boolean ownEchest;
    private @Nullable BlockPos echPos;
    private @Nullable ItemData echItem;
    private @Nullable GoalNear pathGoal;
    private @Nullable BlockPos shulkPos;
    private @Nullable ItemData kitShulkerItem;
    private @Nullable BlockPos avoidSpot;
    /** Where the kit shulker was broken, so the drop chase can be abandoned if it wanders off after
     *  something else. {@code shulkPos} is cleared once the block is gone; this outlives it. */
    private @Nullable BlockPos pickupAnchor;
    /**
     * Player-inventory slots (9-44) that already held a shulker box when the baseline was taken — i.e. the
     * bot's <b>own</b> shulkers, none of which are Regear's to place, empty, break or deposit. Anything
     * shulker-shaped in a slot outside this set is the kit Regear itself pulled; see
     * {@link #findPulledShulkerSlot()}.
     *
     * <p>The baseline is taken exactly twice per cycle, at the only two moments when Regear provably holds no
     * kit of its own: on first entry to {@link State#PULL_KIT}, and again the instant a kit is placed into
     * the world. Re-taking it on <i>every</i> PULL_KIT entry (as this used to) mis-classifies an
     * already-carried kit as one of the bot's own after a reconnect or a PULL_KIT -> OPEN_ECHEST bounce, and
     * that kit is then never placed, emptied or returned. The second capture also re-syncs the set after
     * Baritone's placement macro swaps hotbar slot 6 with whatever slot the kit came from.
     *
     * <p>Known limitation: this assumes the bot's own shulkers stay in their slots between the baseline and
     * the return. Regear no longer moves them itself (it breaks with {@code autoTool=false} and only ever
     * swaps the tracked kit into hotbar slot 0, fixing the mapping when it does), but another module
     * rearranging the inventory mid-cycle would still invalidate it.
     */
    private final java.util.Set<Integer> foreignShulkerSlots = new java.util.HashSet<>();
    private boolean baselineCaptured;

    private boolean paused;
    private boolean complete;
    private boolean hazardPaused;
    private boolean elytraRefill;   // set by ElytraPilotModule's e-bounce resupply: pull ONLY fresh elytras
    private int elytraRefillTarget;
    private boolean oneShot;

    private int gearArmorIdx;
    private int gearAttempts;
    private boolean gearSubmitted;
    private int gearWaitTicks;
    private int cherryPickAttempts;
    private int relocateAttempts;
    private boolean relocateForceKill;
    private boolean expectDeath;
    private boolean wasAlive = true;
    private double pathBestDist;
    private int pathStuckTicks;
    private int groundWaitTicks;
    private int containerBlockTicks;
    private int placeItemCountBefore;
    private int echCountBeforeBreak;

    /** Where {@link State#CLOSE_ECHEST} goes once the window is actually shut. */
    private State pendingAfterClose = State.PLACE_KIT;
    /** Where {@link State#ABORT_CLEANUP} goes when it finishes; {@link State#IDLE} means "then pause". */
    private State cleanupThen = State.IDLE;
    private String abortReason = "";

    /** ElytraPilotModule's e-bounce resupply: pull FRESH elytras from the kit until the inventory holds
     *  {@code target}, dumping SPENT ones back into the kit. The worn (armor-slot) elytra is never touched. */
    public void setElytraRefill(boolean b, int target) { elytraRefill = b; elytraRefillTarget = target; }

    /**
     * Marks this run as owned by another module (ElytraPilot's pre-flight gear-up and mid-flight resupply)
     * rather than by an operator.
     *
     * <p>A one-shot run always disables the module when it finishes, regardless of the persisted
     * {@code regear.disableWhenDone}, and — crucially — never writes {@code regear.enabled}. Both of those
     * are the operator's settings; a module borrowing Regear for one cycle has no business persisting a
     * change to either. The flag is cleared in {@link #onDisable()}, so it cannot leak into the next run
     * whichever way the module is stopped.
     */
    public void setOneShot(boolean oneShot) { this.oneShot = oneShot; }

    @Override
    public boolean enabledSetting() { return AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled; }

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
        paused = false; complete = false; hazardPaused = false;
        resetCycleCounters();
        foreignShulkerSlots.clear(); baselineCaptured = false;
        kitShulkerItem = null; avoidSpot = null; pathGoal = null; pickupAnchor = null;
        wasAlive = CACHE.getPlayerCache().isAlive();
        var c = AquariusPilotPlugin.PLUGIN_CONFIG.regear;

        // A previous run can have been interrupted (aborted, disabled, disconnected) with containers still
        // standing in the world. Anything that is demonstrably still there is recovered first rather than
        // orphaned - that used to mean the next cycle placed a second ender chest and walked away from the
        // first one, with the kit shulker still full beside it.
        boolean leftoverShulker = placed(shulkPos);
        boolean leftoverEchest = ownEchest && placed(echPos);
        if (!leftoverShulker) shulkPos = null;
        if (!leftoverEchest) { echPos = null; ownEchest = false; }
        if (leftoverShulker || leftoverEchest) {
            warn("Regear: a previous run left {}{}{} behind - recovering that before starting the cycle.",
                leftoverShulker ? "a kit shulker " + describeRelative(shulkPos) : "",
                leftoverShulker && leftoverEchest ? " and " : "",
                leftoverEchest ? "an ender chest " + describeRelative(echPos) : "");
            cleanupThen = State.ACQUIRE;
            go(State.ABORT_CLEANUP);
        } else if (c.selfKillRelocate) {
            go(State.RELOCATE);
            info("Regear: starting - relocation enabled, scanning for an open-sky spot with a reachable echest.");
        } else {
            go(State.ACQUIRE);
            info("Regear: starting - looking for the kit shulker.");
        }
        int carried = countInInv(this::isShulkerBox);
        if (carried > 0) {
            info("Regear: you are carrying {} shulker box(es) - they will be left alone. Regear tracks the "
                + "specific shulker it pulls from the ender chest and only places, empties, breaks and returns "
                + "that one.", carried);
        }
    }

    @Override
    public void onDisable() {
        if (BARITONE.isActive()) BARITONE.stop();
        restoreBreaking();
        state = State.IDLE;
        elytraRefill = false;
        oneShot = false;
        // complete/paused are sticky while the module stays enabled (Module#enable no-ops when it already is),
        // so `.aqp regear on` after a finish or an abort would do nothing at all. Clearing them here is what
        // makes the documented off/on retry work.
        complete = false;
        paused = false;
        pathGoal = null; kitShulkerItem = null; avoidSpot = null; pickupAnchor = null;
        foreignShulkerSlots.clear(); baselineCaptured = false;
        // echPos / shulkPos are deliberately KEPT: they are the only record of what is still standing in the
        // world, and onEnable() uses them to recover a container an interrupted run left behind.
    }

    /** Everything that must not survive from one cycle (or one connection) into the next. */
    private void resetCycleCounters() {
        gearArmorIdx = 0; gearAttempts = 0; gearSubmitted = false; gearWaitTicks = 0;
        cherryPickAttempts = 0;
        relocateAttempts = 0; relocateForceKill = false; expectDeath = false;
        pathBestDist = Double.MAX_VALUE; pathStuckTicks = 0;
        groundWaitTicks = 0; containerBlockTicks = 0; auxAttempts = 0;
        placeItemCountBefore = -1; echCountBeforeBreak = -1;
        pendingAfterClose = State.PLACE_KIT;
        cleanupThen = State.IDLE;
    }

    /**
     * Reconnect. Everything positional is invalidated (the placed chest and shulker are in chunks that are
     * not loaded and may not even be the same session's world state), and every per-cycle counter has to go
     * back to zero — a stale {@code gearArmorIdx} used to make the restarted run skip equipping entirely
     * while still reporting success, and a stale {@code cherryPickAttempts} skipped the primary kit match.
     *
     * <p>The foreign-shulker baseline is the one thing deliberately kept: slot indices survive a reconnect,
     * and re-taking it here would re-classify a kit that is still carried as one of the bot's own.
     */
    private void onStarting(ClientBotTick.Starting event) {
        if (state == State.IDLE || complete || paused) return;
        boolean leftBehind = echPos != null || shulkPos != null;
        resetCycleCounters();
        echPos = null; shulkPos = null; pathGoal = null; avoidSpot = null; pickupAnchor = null;
        kitShulkerItem = null; ownEchest = false;
        if (leftBehind) {
            warn("Regear: reconnected mid-cycle. Anything it had placed is in an unloaded chunk and cannot be "
                + "tracked across the reconnect - check wherever the bot was before it dropped for a leftover "
                + "ender chest or kit shulker. Restarting the cycle from scratch.");
            inGameAlertActivePlayer("<yellow>Regear: reconnected mid-cycle - a placed chest/shulker may have been left behind");
        }
        go(State.ACQUIRE);
    }

    /**
     * Death. {@link #notReady()} freezes the machine while dead, so without this the run would simply resume
     * after AutoRespawn — at spawn, with {@code echPos} and {@code shulkPos} pointing into unloaded chunks
     * that read as air, which made BREAK_KIT conclude the shulker had broken and {@link #finishOk()} announce
     * "Regear complete" with the whole kit lying at the death site.
     */
    private void onDeath(ClientDeathEvent event) {
        if (state == State.IDLE || complete || paused) return;
        if (expectDeath || state == State.RELOCATE) { expectDeath = false; return; } // our own /kill
        boolean hadStuff = echPos != null || shulkPos != null;
        echPos = null; shulkPos = null; pathGoal = null; avoidSpot = null; pickupAnchor = null;
        kitShulkerItem = null; ownEchest = false;
        foreignShulkerSlots.clear(); baselineCaptured = false;
        finishAbort("the bot died mid-cycle" + (hadStuff
            ? " - its inventory, and anything it had placed, are at the death site" : ""));
    }

    private void go(State s) { state = s; step = 0; timer = 0; attempts = 0; auxAttempts = 0; containerBlockTicks = 0; }

    /** Leave a window state the only safe way: shut the window first, then go to {@code next}. */
    private void goAfterClose(State next) { pendingAfterClose = next; go(State.CLOSE_ECHEST); }

    /**
     * Abort the run. When something is still placed in the world (or a window is still open) this first runs
     * {@link State#ABORT_CLEANUP} — closing up, breaking and collecting the kit shulker (which on a
     * cherry-pick round still holds everything that wasn't taken) and recovering the ender chest — and only
     * then pauses. Abandoning a placed container and then forgetting where it was is not an acceptable
     * failure mode.
     */
    private void abort(String reason) {
        if (state == State.ABORT_CLEANUP) { finishAbort(reason); return; }
        if (shulkPos != null || (ownEchest && echPos != null) || openContainerId() != 0) {
            abortReason = reason;
            cleanupThen = State.IDLE;
            warn("Regear: aborting - {}. Closing up and recovering what was placed first.", reason);
            if (BARITONE.isActive()) BARITONE.stop();
            go(State.ABORT_CLEANUP);
            return;
        }
        finishAbort(reason);
    }

    /** The terminal half of an abort: stop, pause, tell the operator. Never runs cleanup (it may be what failed). */
    private void finishAbort(String reason) {
        if (BARITONE.isActive()) BARITONE.stop();
        restoreBreaking();
        paused = true;
        state = State.IDLE;
        elytraRefill = false;
        cleanupThen = State.IDLE; abortReason = "";
        foreignShulkerSlots.clear(); baselineCaptured = false;
        String left = "";
        if (shulkPos != null) left += " A kit shulker is still placed " + describeRelative(shulkPos) + ".";
        if (ownEchest && echPos != null) left += " The bot's ender chest is still placed " + describeRelative(echPos) + ".";
        warn("Regear paused: {}.{} Toggle .aqp regear off/on to retry.", reason, left);
        inGameAlertActivePlayer("<red>Regear paused: " + reason + left);
    }

    private void finishOk() {
        restoreBreaking();
        if (BARITONE.isActive()) BARITONE.stop();   // RECOVER_ECHEST's drop chase must not outlive the run
        state = State.IDLE;
        elytraRefill = false;
        cleanupThen = State.IDLE; abortReason = "";
        foreignShulkerSlots.clear(); baselineCaptured = false;
        info("Regear complete - geared up.");
        inGameAlertActivePlayer("<green>Regear complete");
        // A one-shot run is another module's, so it stops the module but never touches the persisted config.
        boolean owned = oneShot;
        if (owned || AquariusPilotPlugin.PLUGIN_CONFIG.regear.disableWhenDone) {
            if (!owned) AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled = false;
            disable();
        }
        complete = true;   // set last: onDisable() clears it
    }

    // ---------------------------------------------------------------- tick

    private void onTick(ClientBotTick event) {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        // Backstop for the death handler: latch the alive->dead edge here too, in case the event is missed.
        boolean alive = CACHE.getPlayerCache().isAlive();
        if (wasAlive && !alive) { wasAlive = false; onDeath(new ClientDeathEvent()); return; }
        wasAlive = alive;
        if (notReady()) return;
        if (state == State.IDLE || complete || paused) return;

        if (cfg.pauseOnPlayer && playerNearby(cfg.playerPauseRange)) {
            if (!hazardPaused) {
                hazardPaused = true;
                if (BARITONE.isActive()) BARITONE.stop();
                warn("Regear: player within {} blocks - pausing.", (int) cfg.playerPauseRange);
            }
            return;
        }
        if (hazardPaused) {
            hazardPaused = false;
            // The pause stopped Baritone and froze the clock; every stuck/attempt counter measured against
            // that frozen time would otherwise fire the moment we resume - and with relocate on, a passer-by
            // walking past during PATH_ECHEST would end in a self-kill.
            attempts = 0; auxAttempts = 0; pathStuckTicks = 0; pathBestDist = Double.MAX_VALUE;
            timer = 0;
            info("Regear: clear - resuming.");
        }

        if (timer > 0) { timer--; return; }

        // Nothing that places, breaks or walks can happen mid-air; wait for the ground rather than aborting.
        if (needsGround(state) && !onSolidGround()) {
            if (++groundWaitTicks > GROUND_WAIT_LIMIT) {
                abort("the bot never reached the ground (still airborne after " + (GROUND_WAIT_LIMIT / 20) + "s)");
                return;
            }
            if (groundWaitTicks == 1) info("Regear: waiting for the bot to be on the ground before placing anything.");
            timer = cfg.actionDelayTicks;
            return;
        }
        groundWaitTicks = 0;

        switch (state) {
            case RELOCATE -> tickRelocate();
            case ACQUIRE -> tickAcquire();
            case PLACE_ECHEST -> tickPlaceEchest();
            case PATH_ECHEST -> tickPathEchest();
            case OPEN_ECHEST -> tickOpenEchest();
            case PULL_KIT -> tickPullKit();
            case CLOSE_ECHEST -> tickCloseThen(pendingAfterClose);
            case PLACE_KIT -> tickPlaceKit();
            case OPEN_KIT -> tickOpenKit();
            case EMPTY_KIT -> tickEmptyKit();
            case CLOSE_KIT -> tickCloseThen(State.BREAK_KIT);
            case BREAK_KIT -> tickBreakKit();
            case RETURN_OPEN -> tickReturnOpen();
            case RETURN_DEPOSIT -> tickReturnDeposit();
            case RETURN_CLOSE -> tickCloseThen(State.CHERRY_CHECK);
            case CHERRY_CHECK -> tickCherryCheck();
            case RECOVER_ECHEST -> tickRecoverEchest();
            case GEAR_UP -> tickGearUp();
            case ABORT_CLEANUP -> tickAbortCleanup();
            case DONE -> finishOk();
            default -> { }
        }
    }

    /** States whose whole job is placing, breaking, or walking to something. */
    private static boolean needsGround(State s) {
        return s == State.RELOCATE || s == State.ACQUIRE || s == State.PLACE_ECHEST || s == State.PLACE_KIT;
    }

    /**
     * Backstop for the states that cannot do anything useful while a container window is open — see the
     * class javadoc. Returns true when the caller must not run this tick.
     */
    private boolean blockedByOpenContainer() {
        if (openContainerId() == 0) { containerBlockTicks = 0; return false; }
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (++containerBlockTicks == 1) {
            warn("Regear: a container is still open entering {} - shutting it before continuing.", state);
        }
        if (containerBlockTicks > OPEN_CONTAINER_BLOCK_LIMIT) {
            abort("a container window would not close, and nothing can be placed, broken or equipped while one is open");
            return true;
        }
        if (!inventoryBusy()) {
            if (containerBlockTicks > CLOSE_ESCALATE_AFTER) closeContainerForced(); else closeContainer();
        }
        timer = cfg.actionDelayTicks;
        return true;
    }

    // ---------------------------------------------------------------- phases

    private void tickRelocate() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        switch (step) {
            case 0 -> {
                if (relocateForceKill) { relocateForceKill = false; doSelfKill("echest here is unreachable"); return; }
                BlockPos pf = playerFeet();
                boolean sky = openSkyAbove(cfg.relocateMinSkyClearance);
                BlockPos ech = nearestBlock(cfg.echestScanRadius, pf.y() - 6, pf.y() + 6, n -> n.equals("ender_chest"));
                if (sky && ech != null) {
                    info("Relocate: good spot - open sky overhead and an ender chest {}. Gearing up.", describeRelative(ech));
                    go(State.ACQUIRE);
                    return;
                }
                doSelfKill((!sky ? "boxed-in" : "") + (ech == null ? (sky ? "no echest in range" : " + no echest") : ""));
            }
            default -> { step = 0; timer = cfg.actionDelayTicks; expectDeath = false; }
        }
    }

    /**
     * {@code /kill} on an anarchy server drops the <b>entire</b> inventory — worn armour, the elytra, totems,
     * and any kit already pulled. Relocation exists for a bot that is stuck somewhere with nothing to lose,
     * so it refuses to run when there is anything to lose, unless the operator explicitly opted in with
     * {@code regear.relocateAllowGearLoss}. It also needs AutoRespawn (nothing else here respawns the bot, so
     * without it RELOCATE would simply never progress) and solid ground (a self-kill mid-glide scatters the
     * drops along the flight path).
     */
    private void doSelfKill(String why) {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (!CONFIG.client.extra.autoRespawn.enabled) {
            abort("relocate needs AutoRespawn enabled (without it the bot stays dead and never gets anywhere) - "
                + "run `autoRespawn on`, or turn regear.selfKillRelocate off");
            return;
        }
        if (!onSolidGround()) { timer = cfg.actionDelayTicks; return; }   // the ground gate above will time out
        if (!cfg.relocateAllowGearLoss) {
            int carried = countCarriedItems();
            if (carried > 0) {
                abort("relocate wanted to /kill the bot to respawn it, but it is carrying " + carried
                    + " item stack(s) that would all drop on the ground. Set regear.relocateAllowGearLoss if "
                    + "that is really intended, or turn regear.selfKillRelocate off");
                return;
            }
        }
        if (++relocateAttempts > cfg.relocateMaxAttempts) {
            abort("relocate gave up after " + (relocateAttempts - 1) + " self-kills (no open-sky spot with a reachable echest)");
            return;
        }
        warn("Relocate: {} - self-killing to respawn (attempt {}/{}).", why, relocateAttempts, cfg.relocateMaxAttempts);
        inGameAlertActivePlayer("<yellow>Regear relocate: self-kill " + relocateAttempts + "/" + cfg.relocateMaxAttempts + " (" + why + ")");
        expectDeath = true;
        sendClientPacketAsync(new ServerboundChatPacket("/kill"));
        step = 1; timer = cfg.relocateKillWaitTicks;
    }

    /** Everything a {@code /kill} would drop: worn armour, offhand, main inventory and hotbar. */
    private int countCarriedItems() {
        int n = 0;
        for (int i = 5; i <= 8; i++) if (playerSlot(i) != Container.EMPTY_STACK) n++;
        if (playerSlot(45) != Container.EMPTY_STACK) n++;
        return n + countInInv(s -> s != Container.EMPTY_STACK);
    }

    private void tickAcquire() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (blockedByOpenContainer()) return;
        int echSlot = InventoryUtil.searchPlayerInventory(this::isEnderChestItem);
        if (echSlot != -1) {
            ownEchest = true;
            echItem = ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(echSlot).getId());
            go(State.PLACE_ECHEST);
            return;
        }
        BlockPos p = playerFeet();
        BlockPos found = nearestBlock(cfg.echestScanRadius, p.y() - 6, p.y() + 6, n -> n.equals("ender_chest"));
        if (found == null) { failOrRelocate("no ender chest carried and none placed within " + cfg.echestScanRadius + " blocks", false); return; }
        ownEchest = false;
        echPos = found;
        pathGoal = pathToNear(found);
        pathBestDist = Double.MAX_VALUE; pathStuckTicks = 0;
        info("Regear: no ender chest carried - walking to the placed one {}.", describeRelative(found));
        go(State.PATH_ECHEST);
    }

    private void failOrRelocate(String reason, boolean forceKill) {
        if (AquariusPilotPlugin.PLUGIN_CONFIG.regear.selfKillRelocate) {
            warn("Regear: {} - relocating.", reason);
            relocateForceKill = forceKill;
            go(State.RELOCATE);
        } else {
            abort(reason);
        }
    }

    private boolean openSkyAbove(int clearance) {
        BlockPos pf = playerFeet();
        for (int dy = 2; dy < 2 + Math.max(1, clearance); dy++) {
            if (!isAir(new BlockPos(pf.x(), pf.y() + dy, pf.z()))) return false;
        }
        return true;
    }

    private void tickPlaceEchest() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (blockedByOpenContainer()) return;
        switch (step) {
            case 0 -> {
                echPos = selectSpotBeside(avoidSpot);
                if (echPos == null) { abort("no clear spot beside the bot to place the ender chest"); return; }
                if (BARITONE.isActive()) BARITONE.stop();
                placeItemCountBefore = countItemsInInv(this::isEnderChestItem);
                place(echPos, echItem);
                timer = cfg.settleTicks; step = 1;
            }
            default -> {
                if (placed(echPos)) { avoidSpot = null; go(State.OPEN_ECHEST); return; }
                // The chest left the inventory, so a place DID happen - it just isn't where we expected (a
                // late block change, or Baritone resolved a different face). Adopt the real one rather than
                // walking away from it and placing a second.
                if (countItemsInInv(this::isEnderChestItem) < placeItemCountBefore) {
                    BlockPos real = adoptPlacedNearby(echPos, "ender_chest");
                    if (real != null) {
                        echPos = real; avoidSpot = null;
                        info("Regear: the ender chest actually landed {} - using that one.", describeRelative(real));
                        go(State.OPEN_ECHEST);
                        return;
                    }
                    abort("placed the ender chest but it can't be found anywhere in reach");
                    return;
                }
                if (++attempts >= 4) abort("ender chest placement kept failing");
                else { avoidSpot = echPos; step = 0; timer = cfg.actionDelayTicks; }
            }
        }
    }

    private void tickPathEchest() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (echPos == null) { failOrRelocate("lost track of the ender chest to walk to", false); return; }
        if (arrivedAt(pathGoal)) {
            if (BARITONE.isActive()) BARITONE.stop();
            go(State.OPEN_ECHEST);
            return;
        }
        if (!BARITONE.isActive()) {
            // Baritone idle without the goal reached means the path was dropped - by the safety pause, by a
            // higher-priority process, or by a failed calculation. Re-issue the goal instead of counting it
            // straight towards a failure (which, with relocate on, ends in a /kill).
            if (++attempts > 4) { failOrRelocate("couldn't path to the placed ender chest", true); return; }
            pathGoal = pathToNear(echPos);
            timer = cfg.actionDelayTicks;
            return;
        }
        double d = distToBot(echPos);
        if (d < pathBestDist - 0.5) { pathBestDist = d; pathStuckTicks = 0; }
        else { pathStuckTicks += cfg.actionDelayTicks; }
        if (pathStuckTicks >= cfg.relocateStuckTicks) failOrRelocate("no progress toward the echest (stuck)", true);
        else timer = cfg.actionDelayTicks;
    }

    private void tickOpenEchest() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        switch (step) {
            case 0 -> { open(echPos); timer = cfg.settleTicks; step = 1; }
            default -> {
                if (openContainerId() != 0) go(State.PULL_KIT);
                else if (++attempts >= 6) abort("ender chest wouldn't open");
                else { step = 0; timer = cfg.actionDelayTicks; }
            }
        }
    }

    private void tickPullKit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (openContainerId() == 0) {
            // The window went away on its own (Bot's auto-close, or someone else's forced close). Container 0
            // is authoritative again now, so if the pull had already landed we can see it here.
            if (findPulledShulkerSlot() != -1) { goAfterClose(State.PLACE_KIT); return; }
            go(State.OPEN_ECHEST);
            return;
        }
        Container c = openContainer();
        if (!windowReady(c)) {
            // Contents haven't arrived yet: iterating now would see zero slots and "prove" the chest empty.
            if (++attempts > PULL_ATTEMPT_LIMIT) { abort("the ender chest window never finished loading"); return; }
            timer = cfg.actionDelayTicks;
            return;
        }
        // Once per cycle only: see foreignShulkerSlots' javadoc.
        if (!baselineCaptured) { captureForeignShulkerSlots(); baselineCaptured = true; }
        if (findPulledShulkerSlot() != -1) { goAfterClose(State.PLACE_KIT); return; }

        // Single-item-shulker storage: skip the primary kit match entirely and cherry-pick from round 0, so a
        // shulker that merely *scores* as a kit is never dumped wholesale when selective pulls were wanted.
        // Not applied to elytraRefill, whose EMPTY_KIT branch also needs an open shulker to dump SPENT elytras
        // into and so must still fall back to the primary match when nothing holds a fresh one.
        boolean singleItem = cfg.singleItemShulkers && cfg.cherryPickFallback && !elytraRefill;

        int src;
        if (cherryPickAttempts == 0) {
            if (singleItem) {
                src = findRichestShulkerSlot(c, this::cherryPickStillNeeds);
                if (src != -1) cherryPickAttempts = 1;
            } else {
                src = cfg.matchByContents ? findBestKitShulkerSlot(c) : findContainerSlot(c, this::isKitShulker);
                if (src == -1 && cfg.cherryPickFallback) {
                    src = findRichestShulkerSlot(c, this::cherryPickStillNeeds);
                    if (src != -1) cherryPickAttempts = 1;
                }
            }
        } else {
            src = findRichestShulkerSlot(c, this::cherryPickStillNeeds);
        }

        if (src == -1) {
            if (cherryPickAttempts > 0) {
                info("Regear: cherry-pick found no more shulkers with missing items - continuing with what's gathered.");
                goAfterClose(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP);
                return;
            }
            if (singleItem) {
                abort("single-item-shulker mode: no shulker in the ender chest holds anything still needed");
                return;
            }
            String primary = cfg.matchByContents ? "no shulker matching the flight-kit contents (elytra + fireworks)"
                : cfg.matchByColor ? "no " + cfg.kitShulkerColor + " kit shulker"
                                   : "no kit shulker named '" + cfg.kitShulkerName + "'";
            abort(primary + " in the ender chest" + (cfg.cherryPickFallback
                ? ", and cherry-pick found no other shulker covering what's needed either" : ""));
            return;
        }
        if (++attempts > PULL_ATTEMPT_LIMIT) { abort("couldn't pull the kit shulker out of the ender chest"); return; }
        if (!inventoryBusy()) shiftClick(c, src);
        timer = cfg.actionDelayTicks;
    }

    /**
     * Shut the open window, then go to {@code next}. Proceeding with a window still open is never safe (see
     * the class javadoc), so when the normal-priority close keeps losing the inventory arbitration this
     * escalates to {@link #closeContainerForced()} rather than bailing into the next state blind.
     */
    private void tickCloseThen(State next) {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (openContainerId() == 0) { go(next); return; }
        attempts++;
        if (attempts > CLOSE_GIVE_UP_AFTER) {
            abort("the container window would not close");
            return;
        }
        if (!inventoryBusy()) {
            if (attempts > CLOSE_ESCALATE_AFTER) {
                if (attempts == CLOSE_ESCALATE_AFTER + 1) {
                    warn("Regear: the container won't close at normal priority - escalating above the stock inventory modules.");
                }
                closeContainerForced();
            } else {
                closeContainer();
            }
        }
        timer = cfg.actionDelayTicks;
    }

    private void tickPlaceKit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (blockedByOpenContainer()) return;
        switch (step) {
            case 0 -> {
                int slot = findPulledShulkerSlot();
                if (slot == -1) { abort("lost the shulker after pulling it"); return; }
                ItemStack stack = playerSlot(slot);
                if (stack == Container.EMPTY_STACK) { abort("lost the shulker after pulling it"); return; }
                kitShulkerItem = ItemRegistry.REGISTRY.get(stack.getId());
                if (kitShulkerItem == null) { abort("the pulled shulker has an unknown item id"); return; }

                // Baritone's place does NOT place the stack we hand it: InteractWithProcess.PlaceBlock
                // re-searches the inventory by item id (offhand, then hotbar, then main inventory) and takes
                // the first hit. With a same-coloured shulker of the bot's own earlier in that order, Regear
                // would place, empty, break and deposit the WRONG shulker. So make ours the first hit.
                int blocker = firstSameIdBefore(slot, kitShulkerItem.id());
                if (blocker != -1) {
                    if (blocker == 45) {
                        abort("an identical shulker box is in the bot's offhand, and the block placement always "
                            + "picks the offhand first - Regear can't tell the placement which stack to use. "
                            + "Move it out of the offhand and retry");
                        return;
                    }
                    if (++auxAttempts > 10) { abort("couldn't move the kit shulker somewhere the placement would actually pick it"); return; }
                    if (!inventoryBusy()) moveKitToHotbarZero(slot);
                    timer = cfg.actionDelayTicks;
                    return;   // stay in step 0 and re-verify from state next tick
                }

                shulkPos = selectSpotBeside(avoidSpot);
                if (shulkPos == null) { abort("no clear spot to place the kit shulker"); return; }
                placeItemCountBefore = countItemsInInv(this::isShulkerBox);
                place(shulkPos, kitShulkerItem);
                timer = cfg.settleTicks; step = 1;
            }
            default -> {
                if (placed(shulkPos)) { avoidSpot = null; onKitPlaced(); go(State.OPEN_KIT); return; }
                if (countItemsInInv(this::isShulkerBox) < placeItemCountBefore) {
                    BlockPos real = adoptPlacedNearby(shulkPos, blockNameFor(kitShulkerItem));
                    if (real != null) {
                        shulkPos = real; avoidSpot = null;
                        info("Regear: the kit shulker actually landed {} - using that one.", describeRelative(real));
                        onKitPlaced();
                        go(State.OPEN_KIT);
                        return;
                    }
                    abort("placed the kit shulker but it can't be found anywhere in reach");
                    return;
                }
                if (++attempts >= 4) abort("kit shulker placement kept failing");
                else { avoidSpot = shulkPos; step = 0; timer = cfg.actionDelayTicks; }
            }
        }
    }

    /**
     * The kit is now in the world, which makes this the one other moment in the cycle when every shulker in
     * the inventory is provably one of the bot's own. Re-baselining here also absorbs the slot swap Baritone's
     * placement macro performs ({@code MoveToHotbarSlot(slot, SLOT_6)}) on its way to placing.
     */
    private void onKitPlaced() {
        captureForeignShulkerSlots();
        baselineCaptured = true;
    }

    private void tickOpenKit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        switch (step) {
            case 0 -> { open(shulkPos); timer = cfg.settleTicks; step = 1; }
            default -> {
                if (openContainerId() != 0) go(State.EMPTY_KIT);
                else if (++attempts >= 6) abort("kit shulker wouldn't open");
                else { step = 0; timer = cfg.actionDelayTicks; }
            }
        }
    }

    private void tickEmptyKit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (openContainerId() == 0) { go(State.CLOSE_KIT); return; }
        Container c = openContainer();
        if (!windowReady(c)) {
            if (++attempts > EMPTY_ATTEMPT_LIMIT) { go(State.CLOSE_KIT); return; }
            timer = cfg.actionDelayTicks;
            return;
        }
        if (++attempts > EMPTY_ATTEMPT_LIMIT) {
            warn("Regear: emptying the kit shulker stalled (the window stopped responding to shift-clicks) - "
                + "closing up and continuing with what was taken.");
            go(State.CLOSE_KIT);
            return;
        }
        if (elytraRefill) {
            if (inventoryBusy()) { timer = cfg.actionDelayTicks; return; }
            int spent = findPlayerWindowSlot(c, this::isSpentElytra);
            if (spent != -1) { shiftClick(c, spent); timer = cfg.actionDelayTicks; return; }
            if (countInInv(this::isFreshElytra) < elytraRefillTarget) {
                int fresh = findContainerSlot(c, this::isFreshElytra);
                if (fresh != -1 && findEmptyPlayerWindowSlot(c) != -1) { shiftClick(c, fresh); timer = cfg.actionDelayTicks; return; }
            }
            // A mid-flight resupply tops up the consumables the flight actually burns, not just its elytras.
            // Food is one of them: the bot eats every few minutes in flight and stops sprinting at hunger 6.
            int other = findContainerSlot(c, s ->
                (FlightGear.isEgap(s) || FlightGear.isTotem(s) || FlightGear.isFood(s)) && FlightGear.stillNeeds(s));
            if (other != -1 && findEmptyPlayerWindowSlot(c) != -1) { shiftClick(c, other); timer = cfg.actionDelayTicks; return; }
            go(State.CLOSE_KIT);
            return;
        }
        int src = cherryPickAttempts > 0
            ? findContainerSlot(c, s -> s != Container.EMPTY_STACK && cherryPickStillNeeds(s))
            : findContainerSlot(c, s -> s != Container.EMPTY_STACK);
        if (src == -1) { go(State.CLOSE_KIT); return; }
        // Applies to the cherry-pick branch too (this runs before every shift-click, in both branches) - and
        // matters much more there, since pulling from up to cherryPickMaxShulkers shulkers fills the inventory
        // far more readily than emptying one kit does. Abort loudly rather than quietly gearing up short.
        if (emptyMainSlots() <= 0 && countInInv(s -> s == Container.EMPTY_STACK) == 0) {
            abort(cherryPickAttempts > 0
                ? "inventory full while cherry-picking (shulker " + cherryPickAttempts + "/" + cfg.cherryPickMaxShulkers
                    + ") - start with a clearer inventory, or lower the pre-flight minimums"
                : "inventory full while emptying the kit");
            return;
        }
        if (!inventoryBusy()) shiftClick(c, src);
        timer = cfg.actionDelayTicks;
    }

    private void tickBreakKit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (blockedByOpenContainer()) return;
        if (shulkPos != null && !chunkKnown(shulkPos)) {
            // "Not loaded" is not "gone" - concluding the break succeeded here is how a mid-cycle death used
            // to end with the whole kit abandoned and a cheerful "Regear complete".
            if (++attempts > BREAK_ATTEMPT_LIMIT) { abort("the placed kit shulker's chunk is no longer loaded"); return; }
            timer = cfg.actionDelayTicks;
            return;
        }
        if (placed(shulkPos)) {
            if (++attempts > BREAK_ATTEMPT_LIMIT) { abort("couldn't break the kit shulker"); return; }
            pickupAnchor = shulkPos;
            // autoTool=false on purpose: BreakBlock's autoTool branch submits MoveToHotbarSlot(tool, from(0)),
            // which SWAPS whatever sits in hotbar slot 0 into the tool's old slot. A shulker of the bot's own
            // in hotbar slot 0 would land in an untracked slot and then be treated as the kit. A shulker box
            // breaks with anything, so there is nothing to gain from the tool swap here.
            breakAt(shulkPos, false);
            return;
        }
        if (pickupAnchor != null && distToBot(pickupAnchor) > PICKUP_ABANDON_RANGE) {
            // The chase has wandered off after something else entirely - stop before it fills the inventory.
            if (BARITONE.isActive()) BARITONE.stop();
            warn("Regear: the drop chase wandered more than {} blocks from where the kit shulker was broken - "
                + "abandoning it. It is most likely still on the ground back there.", (int) PICKUP_ABANDON_RANGE);
            shulkPos = null; pickupAnchor = null; attempts = 0;
            go(State.CHERRY_CHECK);
            return;
        }
        // A typed pickup, not BARITONE.pickup(): the untyped one follows EVERY item entity in range, which on
        // an anarchy server walks the bot off after other people's drops, fills the inventory, and can collect
        // a foreign shulker that findPulledShulkerSlot() then treats as the kit.
        if (!BARITONE.isActive()) {
            if (kitShulkerItem != null) BARITONE.pickup(kitShulkerItem); else BARITONE.pickup();
        }
        // The recovered kit lands in a free slot, which by definition is not one of the foreign shulker slots
        // baselined when it was placed - so this waits for *the broken kit* to arrive rather than being
        // satisfied immediately by a shulker the bot happened to already carry.
        boolean collected = findPulledShulkerSlot() != -1;
        boolean gaveUp = !collected && ++step > PICKUP_WAIT_LIMIT;
        if (collected || gaveUp) {
            if (BARITONE.isActive()) BARITONE.stop();
            if (gaveUp) {
                warn("Regear: broke the kit shulker {} but never picked it up (gave up after ~{} ticks). "
                    + "It is most likely still lying on the ground there - out of reach, burnt, or the chunk "
                    + "hiccuped - and on a cherry-pick round it still holds everything that wasn't taken. "
                    + "Go recover it. Continuing the gear-up with what's already gathered.",
                    describeRelative(pickupAnchor), PICKUP_WAIT_LIMIT * Math.max(1, cfg.actionDelayTicks));
                inGameAlertActivePlayer("<yellow>Regear: broken kit shulker was not picked up - it may still be "
                    + "on the ground with items inside");
            }
            shulkPos = null; pickupAnchor = null; attempts = 0;
            go(State.CHERRY_CHECK);
        } else {
            timer = cfg.actionDelayTicks;
        }
    }

    private void tickReturnOpen() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (echPos == null) { go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP); return; }
        if (!chunkKnown(echPos)) {
            if (++auxAttempts > RETURN_PATH_LIMIT) {
                warn("Regear: the ender chest's chunk isn't loaded any more - keeping the kit shulker instead of returning it.");
                go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP);
                return;
            }
            timer = cfg.actionDelayTicks;
            return;
        }
        if (isAir(echPos)) { go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP); return; }
        switch (step) {
            case 0 -> {
                // auxAttempts, not attempts: step 1 loops back here on a failed open, and sharing the counter
                // would reset the open cap every time round.
                if (!arrivedAt(new GoalNear(echPos, REACH_RANGE_SQ))) {
                    if (++auxAttempts > RETURN_PATH_LIMIT) {
                        warn("Regear: couldn't get back to the ender chest to return the kit shulker - keeping it.");
                        go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP);
                        return;
                    }
                    if (!BARITONE.isActive()) pathGoal = pathToNear(echPos);
                    timer = cfg.actionDelayTicks;
                    return;
                }
                if (BARITONE.isActive()) BARITONE.stop();
                open(echPos); timer = cfg.settleTicks; step = 1;
            }
            default -> {
                if (openContainerId() != 0) go(State.RETURN_DEPOSIT);
                else if (++attempts >= 6) go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP);
                else { step = 0; timer = cfg.actionDelayTicks; }
            }
        }
    }

    private void tickReturnDeposit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (openContainerId() == 0) { go(State.RETURN_OPEN); return; }
        Container c = openContainer();
        if (!windowReady(c)) {
            if (++attempts > DEPOSIT_ATTEMPT_LIMIT) { go(State.RETURN_CLOSE); return; }
            timer = cfg.actionDelayTicks;
            return;
        }
        // Only ever deposit the shulker Regear itself pulled - an unrelated one the bot was already carrying is
        // not Regear's to put in someone's ender chest. -1 means there is nothing of ours left to return.
        int invSlot = findPulledShulkerSlot();
        if (invSlot == -1) { go(State.RETURN_CLOSE); return; }
        // A full ender chest makes the shift-click a server-side no-op, and ShiftClick.packet() returns null
        // (a silent no-op) whenever the cursor is occupied - either way the tracked slot never changes and
        // this would loop forever. The slot not changing IS the stall signal, so one cap covers both.
        if (++attempts > DEPOSIT_ATTEMPT_LIMIT) {
            warn("Regear: couldn't put the kit shulker back after {} tries (the ender chest is most likely full) "
                + "- keeping it in the inventory.", DEPOSIT_ATTEMPT_LIMIT);
            go(State.RETURN_CLOSE);
            return;
        }
        if (!inventoryBusy()) shiftClick(c, invSlotToWindowSlot(c, invSlot));
        timer = cfg.actionDelayTicks;
    }

    private void tickCherryCheck() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        boolean willContinue = cfg.cherryPickFallback && cherryPickAttempts < cfg.cherryPickMaxShulkers && !cherryPickSatisfied();
        if (findPulledShulkerSlot() != -1 && (willContinue || cfg.returnShulker)) { go(State.RETURN_OPEN); return; }
        if (!willContinue) { go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP); return; }
        cherryPickAttempts++;
        shulkPos = null; kitShulkerItem = null;
        info("Regear: still short after {} shulker(s) - cherry-picking another from the ender chest ({}/{}).",
            cherryPickAttempts, cherryPickAttempts, cfg.cherryPickMaxShulkers);
        go(State.OPEN_ECHEST);
    }

    private void tickRecoverEchest() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (blockedByOpenContainer()) return;
        if (echPos == null || !chunkKnown(echPos) || isAir(echPos)) { go(State.GEAR_UP); return; }
        switch (step) {
            case 0 -> {  // hold a silk pick
                int silk = findSilkPick();
                if (silk == -1) { warn("Regear: no silk-touch pickaxe - leaving the ender chest placed."); go(State.GEAR_UP); return; }
                if (isSilkPick(playerSlot(36 + CACHE.getPlayerCache().getHeldItemSlot()))) {
                    echCountBeforeBreak = countItemsInInv(this::isEnderChestItem);
                    step = 1; attempts = 0;
                    return;
                }
                if (!inventoryBusy()) holdItemAt(silk);
                timer = cfg.actionDelayTicks;
                if (++attempts >= 20) { warn("Regear: couldn't equip a silk pick - leaving the ender chest placed."); go(State.GEAR_UP); }
            }
            case 1 -> {  // break it
                if (!placed(echPos)) { step = 2; attempts = 0; return; }
                if (++attempts > BREAK_ATTEMPT_LIMIT) {
                    warn("Regear: couldn't break the ender chest - leaving it placed {}.", describeRelative(echPos));
                    inGameAlertActivePlayer("<yellow>Regear: left its ender chest placed (couldn't break it)");
                    echPos = null;
                    go(State.GEAR_UP);
                    return;
                }
                breakAt(echPos, false);
            }
            default -> {  // collect the drop, and actually wait for it: finishOk() stops Baritone
                if (countItemsInInv(this::isEnderChestItem) > echCountBeforeBreak) {
                    if (BARITONE.isActive()) BARITONE.stop();
                    echPos = null;
                    go(State.GEAR_UP);
                    return;
                }
                if (++attempts > PICKUP_WAIT_LIMIT) {
                    if (BARITONE.isActive()) BARITONE.stop();
                    warn("Regear: broke the ender chest {} but never picked it up - it is probably still on the ground there.",
                        describeRelative(echPos));
                    echPos = null;
                    go(State.GEAR_UP);
                    return;
                }
                if (!BARITONE.isActive()) {
                    if (echItem != null) BARITONE.pickup(echItem); else BARITONE.pickup();
                }
                timer = cfg.actionDelayTicks;
            }
        }
    }

    /**
     * Equip armour / an elytra / an offhand totem — verifying from the equipment slots, never from the fact
     * that a request was submitted. {@code InventoryManager#submit} rejects outright whenever something
     * higher-priority owns the queue, and it returns a completed-but-rejected future rather than throwing, so
     * the old "submit and advance" loop would silently skip a slot and then report "geared up" with nothing
     * on. The re-submit is gated on a wait window so a swap that is merely in flight is never undone by a
     * second swap of the same pair.
     */
    private void tickGearUp() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (blockedByOpenContainer()) return;
        if ((cfg.equipArmor || cfg.equipElytra) && gearArmorIdx < ARMOR_EQUIP.length) {
            if (inventoryBusy()) { timer = cfg.actionDelayTicks; return; }
            final int idx = gearArmorIdx;
            final boolean chestElytra = idx == 1 && cfg.equipElytra;
            var worn = CACHE.getPlayerCache().getEquipment(ARMOR_EQUIP[idx]);
            boolean wornIsElytra = worn != Container.EMPTY_STACK && ItemRegistry.REGISTRY.get(worn.getId()) == ItemRegistry.ELYTRA;
            boolean needFill = chestElytra ? !wornIsElytra : (cfg.equipArmor && worn == Container.EMPTY_STACK);
            if (!needFill) { nextGearSlot(); return; }   // already on (or was just put on) - move along

            int piece = chestElytra
                ? findInInv(s -> { String n = itemName(s); return n != null && n.equals("elytra"); })
                : findInInv(s -> { String n = itemName(s); return n != null && n.endsWith(ARMOR_SUFFIX[idx]); });
            if (piece == -1) { nextGearSlot(); return; }   // nothing to equip here

            if (gearSubmitted) {
                // Wait for the swap to show up in the equipment slot rather than re-swapping it straight back out.
                if (++gearWaitTicks < EQUIP_WAIT_TICKS) { timer = cfg.actionDelayTicks; return; }
                gearSubmitted = false;
            }
            if (++gearAttempts > EQUIP_ATTEMPT_LIMIT) {
                warn("Regear: couldn't equip the {} after {} tries - another module keeps winning the inventory. Skipping it.",
                    ARMOR_SUFFIX[idx].substring(1), EQUIP_ATTEMPT_LIMIT);
                nextGearSlot();
                return;
            }
            INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                .actions(InventoryActionMacros.swapSlots(piece, 5 + idx)).priority(ACTION_PRIORITY).build());
            gearSubmitted = true; gearWaitTicks = 0;
            timer = cfg.actionDelayTicks;
            return;
        }
        if (cfg.offhandTotem) {
            if (inventoryBusy()) { timer = cfg.actionDelayTicks; return; }
            if (CACHE.getPlayerCache().getEquipment(EquipmentSlot.OFF_HAND) == Container.EMPTY_STACK) {
                int totem = findInInv(s -> matchesName(s, "totem_of_undying"));
                if (totem != -1) {
                    if (gearSubmitted && ++gearWaitTicks < EQUIP_WAIT_TICKS) { timer = cfg.actionDelayTicks; return; }
                    gearSubmitted = false;
                    if (++gearAttempts <= EQUIP_ATTEMPT_LIMIT) {
                        moveToOffhand(totem);
                        gearSubmitted = true; gearWaitTicks = 0;
                        timer = cfg.actionDelayTicks;
                        return;
                    }
                    warn("Regear: couldn't get a totem into the offhand after {} tries - continuing without one.",
                        EQUIP_ATTEMPT_LIMIT);
                }
            }
        }
        go(State.DONE);
    }

    /** Advance to the next armour slot with a clean attempt budget. */
    private void nextGearSlot() {
        gearArmorIdx++;
        gearAttempts = 0;
        gearSubmitted = false;
        gearWaitTicks = 0;
        timer = AquariusPilotPlugin.PLUGIN_CONFIG.regear.actionDelayTicks;
    }

    /**
     * Undo an interrupted cycle before pausing (or, from {@link #onEnable()}, before starting a fresh one):
     * shut the window, break and collect the kit shulker, then recover the bot's own ender chest. Every step
     * is bounded and every failure is reported in relative terms so an operator can go and pick it up.
     */
    private void tickAbortCleanup() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        switch (step) {
            case 0 -> {  // close whatever is open
                if (openContainerId() == 0) { step = 1; attempts = 0; return; }
                attempts++;
                if (attempts > CLOSE_GIVE_UP_AFTER) {
                    // Nothing can be broken or picked up while a window is open, so skip straight to the end.
                    warn("Regear cleanup: the container window won't close - can't recover anything that was placed.");
                    step = 6; attempts = 0;
                    return;
                }
                if (!inventoryBusy()) {
                    if (attempts > CLOSE_ESCALATE_AFTER) closeContainerForced(); else closeContainer();
                }
                timer = cfg.actionDelayTicks;
            }
            case 1 -> {  // break the placed kit shulker
                if (shulkPos == null || !chunkKnown(shulkPos) || isAir(shulkPos)) { step = 2; attempts = 0; return; }
                if (!onSolidGround()) { timer = cfg.actionDelayTicks; return; }
                if (attempts == 0) { captureForeignShulkerSlots(); baselineCaptured = true; pickupAnchor = shulkPos; }
                if (++attempts > BREAK_ATTEMPT_LIMIT) {
                    warn("Regear cleanup: couldn't break the kit shulker - it is still placed {} and may still hold items.",
                        describeRelative(shulkPos));
                    inGameAlertActivePlayer("<yellow>Regear: a kit shulker was left placed with items inside");
                    shulkPos = null;
                    step = 3; attempts = 0;
                    return;
                }
                breakAt(shulkPos, false);
            }
            case 2 -> {  // collect it
                if (pickupAnchor == null) { step = 3; attempts = 0; return; }   // nothing was broken
                if (findPulledShulkerSlot() != -1 || ++attempts > PICKUP_WAIT_LIMIT
                    || (pickupAnchor != null && distToBot(pickupAnchor) > PICKUP_ABANDON_RANGE)) {
                    if (BARITONE.isActive()) BARITONE.stop();
                    if (findPulledShulkerSlot() == -1) {
                        warn("Regear cleanup: the broken kit shulker was not picked up - it is probably still on the "
                            + "ground {}, with whatever was left inside it.", describeRelative(pickupAnchor));
                        inGameAlertActivePlayer("<yellow>Regear: a broken kit shulker was left on the ground");
                    }
                    shulkPos = null; pickupAnchor = null;
                    step = 3; attempts = 0;
                    return;
                }
                if (!BARITONE.isActive()) {
                    if (kitShulkerItem != null) BARITONE.pickup(kitShulkerItem); else BARITONE.pickup();
                }
                timer = cfg.actionDelayTicks;
            }
            case 3 -> {  // recover the bot's own ender chest, if it placed one: hold a silk pick
                if (!ownEchest || echPos == null || !chunkKnown(echPos) || isAir(echPos)) { step = 6; attempts = 0; return; }
                if (!onSolidGround()) { timer = cfg.actionDelayTicks; return; }
                int silk = findSilkPick();
                if (silk == -1) {
                    warn("Regear cleanup: no silk-touch pickaxe - leaving the ender chest placed {}.", describeRelative(echPos));
                    step = 6; attempts = 0;
                    return;
                }
                if (!isSilkPick(playerSlot(36 + CACHE.getPlayerCache().getHeldItemSlot()))) {
                    if (!inventoryBusy()) holdItemAt(silk);
                    timer = cfg.actionDelayTicks;
                    if (++attempts >= 20) {
                        warn("Regear cleanup: couldn't equip a silk pick - leaving the ender chest placed {}.", describeRelative(echPos));
                        step = 6; attempts = 0;
                    }
                    return;
                }
                echCountBeforeBreak = countItemsInInv(this::isEnderChestItem);
                step = 4; attempts = 0;
            }
            case 4 -> {  // break it
                if (!placed(echPos)) { step = 5; attempts = 0; return; }
                if (++attempts > BREAK_ATTEMPT_LIMIT) {
                    warn("Regear cleanup: couldn't break the ender chest - leaving it placed {}.", describeRelative(echPos));
                    step = 6; attempts = 0;
                    return;
                }
                breakAt(echPos, false);
            }
            case 5 -> {  // collect it
                if (countItemsInInv(this::isEnderChestItem) > echCountBeforeBreak || ++attempts > PICKUP_WAIT_LIMIT) {
                    if (BARITONE.isActive()) BARITONE.stop();
                    if (countItemsInInv(this::isEnderChestItem) <= echCountBeforeBreak) {
                        warn("Regear cleanup: the broken ender chest was not picked up - it is probably still on the ground {}.",
                            describeRelative(echPos));
                    }
                    echPos = null; ownEchest = false;
                    step = 6; attempts = 0;
                    return;
                }
                if (!BARITONE.isActive()) {
                    if (echItem != null) BARITONE.pickup(echItem); else BARITONE.pickup();
                }
                timer = cfg.actionDelayTicks;
            }
            default -> {
                if (BARITONE.isActive()) BARITONE.stop();
                if (cleanupThen == State.IDLE) {
                    finishAbort(abortReason.isEmpty() ? "cycle aborted" : abortReason);
                } else {
                    State next = cleanupThen;
                    cleanupThen = State.IDLE;
                    info("Regear: cleanup finished - continuing.");
                    go(next);
                }
            }
        }
    }

    // ---------------------------------------------------------------- predicates / helpers

    private static final EquipmentSlot[] ARMOR_EQUIP =
        {EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};
    private static final String[] ARMOR_SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};

    // ------------------------------------------------- "which shulker is ours" tracking

    /** Record every player-inventory slot (9-44) that currently holds a shulker box as one of the bot's own. */
    private void captureForeignShulkerSlots() {
        foreignShulkerSlots.clear();
        for (int i = 9; i <= 44; i++) if (isShulkerBox(playerSlot(i))) foreignShulkerSlots.add(i);
    }

    /**
     * The player-inventory slot (9-44) holding the shulker Regear pulled this round — the first shulker box in
     * a slot that was <i>not</i> already holding one when the baseline was taken — or {@code -1} when Regear
     * is not currently holding a shulker of its own.
     *
     * <p>While a window is open the scan runs over the <b>window's</b> player portion, mapped back with
     * {@link #windowSlotToInvSlot}: both call sites (PULL_KIT and RETURN_DEPOSIT) run with a window open, and
     * container 0 is stale there — {@code ShiftClick} predicts no changed slots, so the only update is the
     * server's response against the window id, which {@code InventoryCache} keeps in a separate container
     * until the window closes. Reading container 0 mid-window meant PULL_KIT never saw its own pull (pulling
     * shulker after shulker until the chest was empty) and RETURN_DEPOSIT never saw its own deposit.
     */
    private int findPulledShulkerSlot() {
        Container w = playerWindow();
        if (w != null) {
            for (int win = playerWindowBase(w); win < w.getSize(); win++) {
                int inv = windowSlotToInvSlot(w, win);
                if (isShulkerBox(w.getItemStack(win)) && !foreignShulkerSlots.contains(inv)) return inv;
            }
            return -1;
        }
        for (int i = 9; i <= 44; i++) {
            if (isShulkerBox(playerSlot(i)) && !foreignShulkerSlots.contains(i)) return i;
        }
        return -1;
    }

    /**
     * The slot {@code InventoryUtil#searchPlayerInventory} would find <i>before</i> {@code slot} for the same
     * item id (offhand, then hotbar low-to-high, then main inventory low-to-high) — i.e. the stack Baritone's
     * placement would grab instead of ours. {@code -1} when ours is already first.
     */
    private int firstSameIdBefore(int slot, int itemId) {
        int mine = searchRank(slot);
        int best = -1, bestRank = mine;
        for (int i = 9; i <= 45; i++) {
            if (i == slot) continue;
            ItemStack s = playerSlot(i);
            if (s == Container.EMPTY_STACK || s.getId() != itemId) continue;
            int r = searchRank(i);
            if (r < bestRank) { bestRank = r; best = i; }
        }
        return best;
    }

    /** Position of a slot in {@code InventoryUtil#searchPlayerInventory}'s scan order. */
    private static int searchRank(int slot) {
        if (slot == 45) return 0;                       // offhand first
        if (slot >= 36 && slot <= 44) return 1 + (slot - 36);   // then hotbar
        return 10 + (slot - 9);                         // then main inventory
    }

    /** Swap the tracked kit into hotbar slot 0, keeping the foreign-slot mapping in step with the swap. */
    private void moveKitToHotbarZero(int slot) {
        if (slot == 36) return;
        // MoveToHotbarSlot swaps: whatever is in hotbar slot 0 ends up in `slot`.
        if (foreignShulkerSlots.remove(36)) foreignShulkerSlots.add(slot);
        moveToHotbarZero(slot);
    }

    /** The block name a placed shulker item produces, e.g. {@code red_shulker_box}. */
    private static String blockNameFor(@Nullable ItemData item) {
        return item == null ? "shulker_box" : item.name();
    }

    private boolean isKitShulker(@Nullable ItemStack s) {
        if (!isShulkerBox(s)) return false;
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (cfg.matchByElytraCount) return countElytrasIn(s) >= cfg.kitElytraCount;
        if (cfg.matchByContents) return kitContentsScore(s) >= 0;
        if (cfg.matchByColor && !cfg.kitShulkerColor.isBlank()) {
            String n = itemName(s);
            return n != null && n.startsWith(cfg.kitShulkerColor.toLowerCase() + "_");
        }
        String cn = customName(s);
        return cn != null && !cfg.kitShulkerName.isBlank()
            && cn.toLowerCase().contains(cfg.kitShulkerName.toLowerCase());
    }

    private int kitContentsScore(@Nullable ItemStack shulker) {
        boolean elytra = false, fw = false, food = false, pick = false, armor = false, weapon = false, echest = false;
        for (ItemStack inner : containerContents(shulker)) {
            if (inner == null || inner == Container.EMPTY_STACK) continue;
            if (FlightGear.isElytra(inner)) elytra = true;
            else if (FlightGear.isFirework(inner)) fw = true;
            // any edible item, not just egaps - a kit shulker packed with steak is a fed flight
            else if (FlightGear.isFood(inner)) food = true;
            else if (FlightGear.isPickaxe(inner)) pick = true;
            else if (FlightGear.isOtherArmor(inner)) armor = true;
            else if (FlightGear.isWeapon(inner)) weapon = true;
            else if (FlightGear.isEchest(inner)) echest = true;
        }
        if (!(elytra && fw)) return -1;
        return (elytra ? 64 : 0) + (fw ? 32 : 0) + (food ? 16 : 0) + (pick ? 8 : 0)
             + (armor ? 4 : 0) + (weapon ? 2 : 0) + (echest ? 1 : 0);
    }

    private int findBestKitShulkerSlot(Container c) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        int best = -1, bestScore = -1;
        for (int i = 0; i < chestSlots; i++) {
            ItemStack s = c.getItemStack(i);
            if (!isShulkerBox(s)) continue;
            int sc = kitContentsScore(s);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    private int remainingDurability(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return 0;
        var data = ItemRegistry.REGISTRY.get(s.getId());
        if (data == null) return 0;
        Integer maxDamage = data.components().get(DataComponentTypes.MAX_DAMAGE);
        if (maxDamage == null) return Integer.MAX_VALUE;
        Integer damage = s.getDataComponentsOrEmpty().get(DataComponentTypes.DAMAGE);
        return maxDamage - (damage == null ? 0 : damage);
    }
    private boolean isFreshElytra(@Nullable ItemStack s) {
        return FlightGear.isElytra(s) && remainingDurability(s) > AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.freshElytraMinDurability;
    }
    private boolean isSpentElytra(@Nullable ItemStack s) {
        return FlightGear.isElytra(s) && remainingDurability(s) <= AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot.freshElytraMinDurability;
    }
    private int countElytrasIn(@Nullable ItemStack shulker) {
        int n = 0;
        for (ItemStack inner : containerContents(shulker)) if (FlightGear.isElytra(inner)) n++;
        return n;
    }

    // ---------------------------------------------------------------- cherry-pick fallback

    private boolean cherryPickStillNeeds(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return false;
        if (elytraRefill) {
            if (FlightGear.isElytra(s)) return countInInv(this::isFreshElytra) < elytraRefillTarget;
            return (FlightGear.isEgap(s) || FlightGear.isTotem(s) || FlightGear.isFood(s)) && FlightGear.stillNeeds(s);
        }
        return regearStillNeeds(s);
    }

    private boolean cherryPickSatisfied() {
        if (elytraRefill) return countInInv(this::isFreshElytra) >= elytraRefillTarget
            && FlightGear.egapCountSatisfied() && FlightGear.totemCountSatisfied()
            && FlightGear.foodCountSatisfied();
        return regearSatisfied();
    }

    /**
     * What a standalone (non-{@code elytraRefill}) gear-up still wants out of the ender chest: the union of
     * <ul>
     *   <li>the <b>equip</b> needs — an elytra to wear, an empty armour slot to fill, an empty offhand to put
     *       a totem in. This is about equipping, not merely possessing, so it stays even when the count-based
     *       checklist is already satisfied by items sitting in the inventory; and</li>
     *   <li>the full count-based <b>pre-flight checklist</b> ({@link FlightGear#stillNeeds}) — spare elytras,
     *       fireworks, gapples, a pickaxe, an ender chest — gated on {@code regear.fillFlightChecklist}.</li>
     * </ul>
     * Before {@code fillFlightChecklist} existed this was the equip half only, so a standalone gear-up never
     * fetched fireworks / gapples / a pickaxe / an ender chest / a second elytra and Regear would report
     * "complete" while ElytraPilot's pre-flight gate (which uses the checklist) still refused to fly.
     */
    private boolean regearStillNeeds(ItemStack s) {
        if (equipStillNeeds(s)) return true;
        return AquariusPilotPlugin.PLUGIN_CONFIG.regear.fillFlightChecklist && FlightGear.stillNeeds(s);
    }

    /** Is there anything left that a further shulker could fix? Mirror of {@link #regearStillNeeds}. */
    private boolean regearSatisfied() {
        if (!equipSatisfied()) return false;
        return !AquariusPilotPlugin.PLUGIN_CONFIG.regear.fillFlightChecklist || !FlightGear.anySupplyDeficit();
    }

    private boolean equipStillNeeds(ItemStack s) {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        String n = itemName(s);
        if (n == null) return false;
        if (cfg.equipElytra && n.equals("elytra")) {
            return !wornIsElytra() && findInInv(s2 -> "elytra".equals(itemName(s2))) == -1;
        }
        if (cfg.equipArmor) {
            for (int i = 0; i < ARMOR_SUFFIX.length; i++) {
                if (i == 1 && cfg.equipElytra) continue;
                if (!n.endsWith(ARMOR_SUFFIX[i])) continue;
                if (CACHE.getPlayerCache().getEquipment(ARMOR_EQUIP[i]) != Container.EMPTY_STACK) return false;
                final int idx = i;
                return findInInv(s2 -> { String n2 = itemName(s2); return n2 != null && n2.endsWith(ARMOR_SUFFIX[idx]); }) == -1;
            }
        }
        if (cfg.offhandTotem && n.equals("totem_of_undying")) {
            return CACHE.getPlayerCache().getEquipment(EquipmentSlot.OFF_HAND) == Container.EMPTY_STACK
                && findInInv(s2 -> matchesName(s2, "totem_of_undying")) == -1;
        }
        return false;
    }

    private boolean equipSatisfied() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (cfg.equipElytra && !wornIsElytra() && findInInv(s -> "elytra".equals(itemName(s))) == -1) return false;
        if (cfg.equipArmor) {
            for (int i = 0; i < ARMOR_SUFFIX.length; i++) {
                if (i == 1 && cfg.equipElytra) continue;
                if (CACHE.getPlayerCache().getEquipment(ARMOR_EQUIP[i]) != Container.EMPTY_STACK) continue;
                final int idx = i;
                if (findInInv(s -> { String n = itemName(s); return n != null && n.endsWith(ARMOR_SUFFIX[idx]); }) == -1) return false;
            }
        }
        if (cfg.offhandTotem && CACHE.getPlayerCache().getEquipment(EquipmentSlot.OFF_HAND) == Container.EMPTY_STACK
            && findInInv(s -> matchesName(s, "totem_of_undying")) == -1) return false;
        return true;
    }

    private boolean wornIsElytra() {
        var worn = CACHE.getPlayerCache().getEquipment(EquipmentSlot.CHESTPLATE);
        return worn != Container.EMPTY_STACK && "elytra".equals(itemName(worn));
    }

    private int findSilkPick() {
        for (int i = 9; i <= 44; i++) if (isSilkPick(playerSlot(i))) return i;
        return -1;
    }
    private boolean isSilkPick(@Nullable ItemStack s) {
        String n = itemName(s);
        if (n == null || !n.endsWith("pickaxe")) return false;
        var ench = s.getDataComponentsOrEmpty().get(DataComponentTypes.ENCHANTMENTS);
        return ench != null && ench.getEnchantments()
            .containsKey(com.zenith.mc.enchantment.EnchantmentRegistry.SILK_TOUCH.get().id());
    }

    // ---------------------------------------------------------------- status

    public String statusLine() {
        if (complete) return "complete";
        if (paused) return "paused";
        if (state == State.IDLE) return "idle";
        return state.name().toLowerCase();
    }
    public boolean isPaused() { return paused; }
    public boolean isComplete() { return complete; }
    public boolean isRelocating() { return state == State.RELOCATE; }
}
