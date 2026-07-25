package com.aquariuspilot.module;

import com.aquariuspilot.AquariusPilotPlugin;
import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.inventory.Container;
import com.zenith.event.client.ClientBotTick;
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
 */
public class RegearModule extends AbstractFieldModule {

    private enum State {
        IDLE, RELOCATE, ACQUIRE, PLACE_ECHEST, PATH_ECHEST, OPEN_ECHEST, PULL_KIT, CLOSE_ECHEST,
        PLACE_KIT, OPEN_KIT, EMPTY_KIT, CLOSE_KIT, BREAK_KIT,
        RETURN_OPEN, RETURN_DEPOSIT, RETURN_CLOSE, CHERRY_CHECK, RECOVER_ECHEST, GEAR_UP, DONE
    }

    private State state = State.IDLE;
    private int step;
    private int timer;
    private int attempts;

    private boolean ownEchest;
    private @Nullable BlockPos echPos;
    private @Nullable ItemData echItem;
    private @Nullable GoalNear pathGoal;
    private @Nullable BlockPos shulkPos;
    private @Nullable ItemData kitShulkerItem;
    private @Nullable BlockPos avoidSpot;
    /** Shulker boxes carried on entry to {@link State#BREAK_KIT}, {@code -1} when not in that state. Needs its
     *  own field rather than reusing {@code step}, which {@link #go} zeroes on every state change. */
    private int shulkerBaseline = -1;

    private boolean paused;
    private boolean complete;
    private boolean hazardPaused;
    private boolean elytraRefill;   // set by ElytraPilotModule's e-bounce resupply: pull ONLY fresh elytras
    private int elytraRefillTarget;

    private int gearArmorIdx;
    private int cherryPickAttempts;
    private int relocateAttempts;
    private boolean relocateForceKill;
    private double pathBestDist;
    private int pathStuckTicks;

    /** ElytraPilotModule's e-bounce resupply: pull FRESH elytras from the kit until the inventory holds
     *  {@code target}, dumping SPENT ones back into the kit. The worn (armor-slot) elytra is never touched. */
    public void setElytraRefill(boolean b, int target) { elytraRefill = b; elytraRefillTarget = target; }

    @Override
    public boolean enabledSetting() { return AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled; }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, this::onStarting)
        );
    }

    @Override
    public void onEnable() {
        paused = false; complete = false; hazardPaused = false;
        gearArmorIdx = 0;
        cherryPickAttempts = 0;
        relocateAttempts = 0;
        relocateForceKill = false; pathBestDist = Double.MAX_VALUE; pathStuckTicks = 0;
        ownEchest = false; echPos = null; shulkPos = null; pathGoal = null; kitShulkerItem = null; avoidSpot = null;
        shulkerBaseline = -1;
        var c = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (c.selfKillRelocate) {
            go(State.RELOCATE);
            info("Regear: starting - relocation enabled, scanning for an open-sky spot with a reachable echest.");
        } else {
            go(State.ACQUIRE);
            info("Regear: starting - looking for the kit shulker.");
        }
        int carried = countInInv(this::isShulkerBox);
        if (carried > 0) {
            warn("Regear: starting while already carrying {} shulker box(es). PULL_KIT treats a shulker already "
                + "in the inventory as the kit, so Regear may place, empty and break one of yours instead of "
                + "pulling one from the ender chest. Stash unrelated shulkers before gearing up.", carried);
        }
    }

    @Override
    public void onDisable() {
        if (BARITONE.isActive()) BARITONE.stop();
        restoreBreaking();
        state = State.IDLE;
        elytraRefill = false;
        echPos = null; shulkPos = null; pathGoal = null; kitShulkerItem = null; avoidSpot = null;
        shulkerBaseline = -1;
    }

    private void onStarting(ClientBotTick.Starting event) {
        if (state != State.IDLE && !complete) { shulkerBaseline = -1; go(State.ACQUIRE); }
    }

    private void go(State s) { state = s; step = 0; timer = 0; attempts = 0; }

    private void abort(String reason) {
        if (BARITONE.isActive()) BARITONE.stop();
        restoreBreaking();
        paused = true;
        state = State.IDLE;
        elytraRefill = false;
        shulkerBaseline = -1;
        warn("Regear paused: {}. Toggle .aqp regear off/on to retry.", reason);
        inGameAlertActivePlayer("<red>Regear paused: " + reason);
    }

    private void finishOk() {
        restoreBreaking();
        complete = true;
        state = State.IDLE;
        elytraRefill = false;
        info("Regear complete - geared up.");
        inGameAlertActivePlayer("<green>Regear complete");
        if (AquariusPilotPlugin.PLUGIN_CONFIG.regear.disableWhenDone) {
            AquariusPilotPlugin.PLUGIN_CONFIG.regear.enabled = false;
            syncEnabledFromConfig();
        }
    }

    // ---------------------------------------------------------------- tick

    private void onTick(ClientBotTick event) {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
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
        if (hazardPaused) { hazardPaused = false; info("Regear: clear - resuming."); }

        if (timer > 0) { timer--; return; }

        switch (state) {
            case RELOCATE -> tickRelocate();
            case ACQUIRE -> tickAcquire();
            case PLACE_ECHEST -> tickPlaceEchest();
            case PATH_ECHEST -> tickPathEchest();
            case OPEN_ECHEST -> tickOpenEchest();
            case PULL_KIT -> tickPullKit();
            case CLOSE_ECHEST -> tickCloseThen(State.PLACE_KIT);
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
            case DONE -> finishOk();
            default -> { }
        }
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
                    info("Relocate: good spot at {} - open sky + ender chest at {}. Gearing up.", pf, ech);
                    go(State.ACQUIRE);
                    return;
                }
                doSelfKill((!sky ? "boxed-in" : "") + (ech == null ? (sky ? "no echest in range" : " + no echest") : ""));
            }
            default -> { step = 0; timer = cfg.actionDelayTicks; }
        }
    }

    private void doSelfKill(String why) {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (++relocateAttempts > cfg.relocateMaxAttempts) {
            abort("relocate gave up after " + (relocateAttempts - 1) + " self-kills (no open-sky spot with a reachable echest)");
            return;
        }
        warn("Relocate: {} - self-killing to respawn (attempt {}/{}).", why, relocateAttempts, cfg.relocateMaxAttempts);
        inGameAlertActivePlayer("<yellow>Regear relocate: self-kill " + relocateAttempts + "/" + cfg.relocateMaxAttempts + " (" + why + ")");
        sendClientPacketAsync(new ServerboundChatPacket("/kill"));
        step = 1; timer = cfg.relocateKillWaitTicks;
    }

    private void tickAcquire() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
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
        info("Regear: no ender chest carried - walking to the placed one at {}.", found);
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
        switch (step) {
            case 0 -> {
                echPos = selectSpotBeside(avoidSpot);
                if (echPos == null) { abort("no clear spot beside the bot to place the ender chest"); return; }
                if (BARITONE.isActive()) BARITONE.stop();
                place(echPos, echItem);
                timer = cfg.settleTicks; step = 1;
            }
            default -> {
                if (placed(echPos)) { avoidSpot = null; go(State.OPEN_ECHEST); }
                else if (++attempts >= 4) { abort("ender chest placement kept failing"); }
                else { avoidSpot = echPos; step = 0; timer = cfg.actionDelayTicks; }
            }
        }
    }

    private void tickPathEchest() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        double d = echPos == null ? Double.MAX_VALUE : distToBot(echPos);
        if (arrivedAt(pathGoal)) {
            if (BARITONE.isActive()) BARITONE.stop();
            go(State.OPEN_ECHEST);
            return;
        }
        if (d < pathBestDist - 0.5) { pathBestDist = d; pathStuckTicks = 0; }
        else { pathStuckTicks += cfg.actionDelayTicks; }
        if (pathStuckTicks >= cfg.relocateStuckTicks) {
            failOrRelocate("no progress toward the echest (stuck)", true);
        } else if (!BARITONE.isActive() && ++attempts > 3) {
            failOrRelocate("couldn't path to the placed ender chest", true);
        } else {
            timer = cfg.actionDelayTicks;
        }
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
        if (openContainerId() == 0) { go(State.OPEN_ECHEST); return; }
        Container c = openContainer();
        if (c == null) { timer = cfg.actionDelayTicks; return; }
        if (findPlayerWindowSlot(c, this::isShulkerBox) != -1) { go(State.CLOSE_ECHEST); return; }

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
                go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP);
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
        if (!inventoryBusy()) shiftClick(c, src);
        timer = cfg.actionDelayTicks;
    }

    private void tickCloseThen(State next) {
        if (openContainerId() == 0) { go(next); return; }
        if (!inventoryBusy()) closeContainer();
        timer = AquariusPilotPlugin.PLUGIN_CONFIG.regear.actionDelayTicks;
        if (++attempts >= 10) go(next);
    }

    private void tickPlaceKit() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        switch (step) {
            case 0 -> {
                int slot = findInInv(this::isShulkerBox);
                if (slot == -1) { abort("lost the shulker after pulling it"); return; }
                kitShulkerItem = ItemRegistry.REGISTRY.get(CACHE.getPlayerCache().getPlayerInventory().get(slot).getId());
                shulkPos = selectSpotBeside(avoidSpot);
                if (shulkPos == null) { abort("no clear spot to place the kit shulker"); return; }
                place(shulkPos, kitShulkerItem);
                timer = cfg.settleTicks; step = 1;
            }
            default -> {
                if (placed(shulkPos)) { avoidSpot = null; go(State.OPEN_KIT); }
                else if (++attempts >= 4) abort("kit shulker placement kept failing");
                else { avoidSpot = shulkPos; step = 0; timer = cfg.actionDelayTicks; }
            }
        }
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
        if (c == null) { timer = cfg.actionDelayTicks; return; }
        if (elytraRefill) {
            if (inventoryBusy()) { timer = cfg.actionDelayTicks; return; }
            int spent = findPlayerWindowSlot(c, this::isSpentElytra);
            if (spent != -1) { shiftClick(c, spent); timer = cfg.actionDelayTicks; return; }
            if (countInInv(this::isFreshElytra) < elytraRefillTarget) {
                int fresh = findContainerSlot(c, this::isFreshElytra);
                if (fresh != -1 && findEmptyPlayerWindowSlot(c) != -1) { shiftClick(c, fresh); timer = cfg.actionDelayTicks; return; }
            }
            int other = findContainerSlot(c, s -> (FlightGear.isEgap(s) || FlightGear.isTotem(s)) && FlightGear.stillNeeds(s));
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
        // Baseline the carried shulkers on the first tick here, while the kit is still a placed block, so the
        // pickup wait below is looking for *the broken kit* arriving in the inventory rather than for any
        // shulker at all: a shulker the bot happened to already carry used to satisfy the check immediately
        // and the real drop was left on the ground. Reset to -1 on every exit so each cherry-pick round
        // re-baselines instead of inheriting the previous round's count.
        if (shulkerBaseline < 0) shulkerBaseline = countInInv(this::isShulkerBox);
        if (placed(shulkPos)) {
            if (++attempts > 200) { abort("couldn't break the kit shulker"); return; }
            breakAt(shulkPos, true);
            return;
        }
        if (!BARITONE.isActive()) BARITONE.pickup();
        boolean collected = countInInv(this::isShulkerBox) > shulkerBaseline;
        boolean gaveUp = !collected && ++step > 60;
        if (collected || gaveUp) {
            if (BARITONE.isActive()) BARITONE.stop();
            if (gaveUp) {
                warn("Regear: broke the kit shulker at {} but never picked it up (gave up after ~{} ticks). "
                    + "It is most likely still lying on the ground there - out of reach, burnt/despawned, or the "
                    + "chunk hiccuped - and on a cherry-pick round it still holds everything that wasn't taken. "
                    + "Go recover it. Continuing the gear-up with what's already gathered.",
                    shulkPos, 60 * Math.max(1, cfg.actionDelayTicks));
                inGameAlertActivePlayer("<yellow>Regear: broken kit shulker was not picked up - it may still be "
                    + "on the ground with items inside");
            }
            shulkPos = null; attempts = 0; shulkerBaseline = -1;
            go(State.CHERRY_CHECK);
        } else {
            timer = cfg.actionDelayTicks;
        }
    }

    private void tickReturnOpen() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (echPos == null || isAir(echPos)) { go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP); return; }
        switch (step) {
            case 0 -> {
                if (!arrivedAt(new GoalNear(echPos, REACH_RANGE_SQ))) { pathGoal = pathToNear(echPos); timer = cfg.actionDelayTicks; return; }
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
        if (c == null) { timer = cfg.actionDelayTicks; return; }
        int src = findPlayerWindowSlot(c, this::isShulkerBox);
        if (src == -1) { go(State.RETURN_CLOSE); return; }
        if (!inventoryBusy()) shiftClick(c, src);
        timer = cfg.actionDelayTicks;
    }

    private void tickCherryCheck() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        boolean willContinue = cfg.cherryPickFallback && cherryPickAttempts < cfg.cherryPickMaxShulkers && !cherryPickSatisfied();
        if (countInInv(this::isShulkerBox) > 0 && (willContinue || cfg.returnShulker)) { go(State.RETURN_OPEN); return; }
        if (!willContinue) { go(ownEchest ? State.RECOVER_ECHEST : State.GEAR_UP); return; }
        cherryPickAttempts++;
        shulkPos = null; kitShulkerItem = null;
        info("Regear: still short after {} shulker(s) - cherry-picking another from the ender chest ({}/{}).",
            cherryPickAttempts, cherryPickAttempts, cfg.cherryPickMaxShulkers);
        go(State.OPEN_ECHEST);
    }

    private void tickRecoverEchest() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if (echPos == null || isAir(echPos)) { go(State.GEAR_UP); return; }
        int silk = findSilkPick();
        if (silk == -1) { warn("Regear: no silk-touch pickaxe - leaving the ender chest placed."); go(State.GEAR_UP); return; }
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        ItemStack held = inv.get(36 + CACHE.getPlayerCache().getHeldItemSlot());
        if (!isSilkPick(held)) {
            if (!inventoryBusy()) holdItemAt(silk);
            timer = cfg.actionDelayTicks;
            if (++attempts >= 20) { warn("Regear: couldn't equip a silk pick - leaving the ender chest placed."); go(State.GEAR_UP); }
            return;
        }
        if (placed(echPos)) { breakAt(echPos, false); return; }
        if (!BARITONE.isActive()) BARITONE.pickup();
        go(State.GEAR_UP);
    }

    private void tickGearUp() {
        var cfg = AquariusPilotPlugin.PLUGIN_CONFIG.regear;
        if ((cfg.equipArmor || cfg.equipElytra) && gearArmorIdx < ARMOR_EQUIP.length) {
            if (inventoryBusy()) { timer = cfg.actionDelayTicks; return; }
            final int idx = gearArmorIdx;
            final boolean chestElytra = idx == 1 && cfg.equipElytra;
            var worn = CACHE.getPlayerCache().getEquipment(ARMOR_EQUIP[idx]);
            boolean wornIsElytra = worn != Container.EMPTY_STACK && ItemRegistry.REGISTRY.get(worn.getId()) == ItemRegistry.ELYTRA;
            boolean needFill = chestElytra ? !wornIsElytra : (cfg.equipArmor && worn == Container.EMPTY_STACK);
            if (needFill) {
                int piece = chestElytra
                    ? findInInv(s -> { String n = itemName(s); return n != null && n.equals("elytra"); })
                    : findInInv(s -> { String n = itemName(s); return n != null && n.endsWith(ARMOR_SUFFIX[idx]); });
                if (piece != -1) {
                    INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                        .actions(InventoryActionMacros.swapSlots(piece, 5 + idx)).priority(ACTION_PRIORITY).build());
                    gearArmorIdx++; timer = cfg.actionDelayTicks; return;
                }
            }
            gearArmorIdx++; timer = cfg.actionDelayTicks; return;
        }
        if (cfg.offhandTotem) {
            if (inventoryBusy()) { timer = cfg.actionDelayTicks; return; }
            if (CACHE.getPlayerCache().getEquipment(EquipmentSlot.OFF_HAND) == Container.EMPTY_STACK) {
                int totem = findInInv(s -> matchesName(s, "totem_of_undying"));
                if (totem != -1) { moveToOffhand(totem); timer = cfg.actionDelayTicks; }
            }
        }
        go(State.DONE);
    }

    // ---------------------------------------------------------------- predicates / helpers

    private static final EquipmentSlot[] ARMOR_EQUIP =
        {EquipmentSlot.HELMET, EquipmentSlot.CHESTPLATE, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};
    private static final String[] ARMOR_SUFFIX = {"_helmet", "_chestplate", "_leggings", "_boots"};

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
            else if (FlightGear.isEgap(inner)) food = true;
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
            return (FlightGear.isEgap(s) || FlightGear.isTotem(s)) && FlightGear.stillNeeds(s);
        }
        return regearStillNeeds(s);
    }

    private boolean cherryPickSatisfied() {
        if (elytraRefill) return countInInv(this::isFreshElytra) >= elytraRefillTarget
            && FlightGear.egapCountSatisfied() && FlightGear.totemCountSatisfied();
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
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) if (isSilkPick(inv.get(i))) return i;
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
