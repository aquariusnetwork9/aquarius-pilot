package com.aquariuspilot.module;

import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.MoveToHotbarSlot;
import com.zenith.feature.inventory.actions.SetHeldItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.goals.GoalNear;
import com.zenith.feature.player.World;
import com.zenith.mc.block.Block;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.item.ItemTags;
import com.zenith.module.api.Module;
import com.zenith.util.math.MathHelper;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INVENTORY;

/**
 * Shared scaffolding for this plugin's field-automation modules ({@link RegearModule}) that drive a
 * place / open / shift-items / close / break / pickup container cycle on 2b2t.
 *
 * <p>Ported from AquariusProxy's {@code com.aquarius.module.impl.AbstractFieldModule}. That class turned out
 * to NOT be a stock ZenithProxy class (contrary to earlier notes) — it's fork-only helper code built
 * entirely on top of stock primitives ({@link Container}, the inventory-action classes, {@link GoalNear},
 * {@link World}, {@link Block}/{@link BlockPos}, {@link ItemData}/{@link ItemRegistry}/{@link ItemTags},
 * {@link Module}, {@link MathHelper}) — all of which DO exist in stock, so it ports here essentially
 * verbatim. One thing was dropped: the ghost-hand no-line-of-sight container interact
 * ({@code openGhost}/{@code ghostUseItemOn}), which depends on AquariusProxy's {@code emitAirPlace} and has
 * no stock equivalent — {@link RegearModule} always uses the normal path-and-raytrace {@link #open}.
 */
public abstract class AbstractFieldModule extends Module {
    protected static final int ACTION_PRIORITY = 3000;
    /** GoalNear range² for "at" a container — within ~3 blocks, vanilla interaction reach. */
    protected static final int REACH_RANGE_SQ = 9;

    // breaking gate -----------------------------------------------------------------------------------
    private boolean breakOverridden = false;
    private boolean breakSaved;

    protected void setBreakingAllowed(boolean allowed) {
        if (!breakOverridden) { breakSaved = CONFIG.client.extra.pathfinder.allowBreak; breakOverridden = true; }
        CONFIG.client.extra.pathfinder.allowBreak = allowed;
    }

    protected void restoreBreaking() {
        if (breakOverridden) { CONFIG.client.extra.pathfinder.allowBreak = breakSaved; breakOverridden = false; }
    }

    // ---------------------------------------------------------------- tick gating helpers

    /** True when the bot can't act yet this tick: dead, or its own chunk isn't loaded (just (re)connected). */
    protected boolean notReady() {
        if (!CACHE.getPlayerCache().isAlive()) return true;
        int cx = (int) Math.floor(CACHE.getPlayerCache().getX()) >> 4;
        int cz = (int) Math.floor(CACHE.getPlayerCache().getZ()) >> 4;
        return !World.isChunkLoadedChunkPos(cx, cz);
    }

    /** True if a non-self player is within {@code range} blocks (used for the optional safety soft-pause). */
    protected boolean playerNearby(double range) {
        double rsq = range * range;
        double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
        long selfId = CACHE.getPlayerCache().getEntityId();
        for (var e : CACHE.getEntityCache().getEntities().values()) {
            if (e == null || !(e instanceof com.zenith.cache.data.entity.EntityPlayer)) continue;
            if (e.getEntityId() == selfId) continue;
            double dx = e.getX() - px, dy = e.getY() - py, dz = e.getZ() - pz;
            if (dx * dx + dy * dy + dz * dz <= rsq) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- container / world primitives

    protected void place(@Nullable BlockPos pos, @Nullable ItemData item) {
        if (pos != null && item != null) BARITONE.placeBlock(pos.x(), pos.y(), pos.z(), item);
    }
    protected void open(@Nullable BlockPos pos) {
        if (pos != null) BARITONE.rightClickBlock(pos.x(), pos.y(), pos.z());
    }
    protected double distToBot(BlockPos p) {
        return MathHelper.distance3d(
            CACHE.getPlayerCache().getX(), CACHE.getPlayerCache().getY(), CACHE.getPlayerCache().getZ(),
            p.x() + 0.5, p.y() + 0.5, p.z() + 0.5);
    }
    protected void breakAt(@Nullable BlockPos pos, boolean autoTool) {
        if (pos != null) BARITONE.breakBlock(pos.x(), pos.y(), pos.z(), autoTool);
    }
    protected void closeContainer() {
        INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(new CloseContainer()).priority(ACTION_PRIORITY).build());
    }
    protected void shiftClick(Container c, int slot) {
        INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .actions(new ShiftClick(c.getContainerId(), slot, ShiftClickItemAction.LEFT_CLICK)).priority(ACTION_PRIORITY).build());
    }
    protected void leftClick(Container c, int slot) {
        INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .actions(new ClickItem(c.getContainerId(), slot, ClickItemAction.LEFT_CLICK)).priority(ACTION_PRIORITY).build());
    }
    protected void holdItemAt(int slot) {
        if (slot >= 36 && slot <= 44) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                .actions(new SetHeldItem(slot - 36)).priority(ACTION_PRIORITY).build());
        } else if (slot >= 9 && slot <= 35) {
            INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                .actions(new MoveToHotbarSlot(slot, MoveToHotbarAction.from(0)), new SetHeldItem(0)).priority(ACTION_PRIORITY).build());
        }
    }
    /** Move the item at player-inventory slot {@code slot} (9-44) into the offhand. */
    protected void moveToOffhand(int slot) {
        INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .actions(new MoveToHotbarSlot(slot, MoveToHotbarAction.OFF_HAND)).priority(ACTION_PRIORITY).build());
    }

    protected boolean placed(@Nullable BlockPos p) { return p != null && !World.getBlock(p.x(), p.y(), p.z()).isAir(); }
    protected boolean isAir(@Nullable BlockPos p) { return p != null && World.getBlock(p.x(), p.y(), p.z()).isAir(); }

    protected int openContainerId() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainerId(); }
    protected @Nullable Container openContainer() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainer(); }
    protected boolean inventoryBusy() { return INVENTORY.hasActiveRequest(); }

    // ---------------------------------------------------------------- slot finders

    protected int findContainerSlot(Container c, Predicate<ItemStack> pred) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        for (int i = 0; i < chestSlots; i++) if (pred.test(c.getItemStack(i))) return i;
        return -1;
    }
    protected int findPlayerWindowSlot(Container c, Predicate<ItemStack> pred) {
        int size = c.getSize();
        for (int i = Math.max(0, size - 36); i < size; i++) if (pred.test(c.getItemStack(i))) return i;
        return -1;
    }
    protected int findEmptyPlayerWindowSlot(Container c) {
        return findPlayerWindowSlot(c, s -> s == Container.EMPTY_STACK);
    }
    /** Among shulkers in the open window matching {@code pred} (peeked via CONTAINER component, no opening),
     *  the one holding the most matching items — fewer, richer pulls beat visiting many half-empty ones. */
    protected int findRichestShulkerSlot(Container c, Predicate<ItemStack> pred) {
        int chestSlots = Math.max(0, c.getSize() - 36);
        int best = -1, bestQty = 0;
        for (int i = 0; i < chestSlots; i++) {
            ItemStack s = c.getItemStack(i);
            if (!isShulkerBox(s)) continue;
            int qty = 0;
            for (ItemStack inner : containerContents(s)) if (pred.test(inner)) qty += inner.getAmount();
            if (qty > bestQty) { bestQty = qty; best = i; }
        }
        return best;
    }
    protected int findInInv(Predicate<ItemStack> pred) {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        for (int i = 9; i <= 44; i++) if (pred.test(inv.get(i))) return i;
        return -1;
    }
    protected int countInInv(Predicate<ItemStack> pred) {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 44; i++) if (pred.test(inv.get(i))) n++;
        return n;
    }
    protected int emptyMainSlots() {
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        int n = 0;
        for (int i = 9; i <= 35; i++) if (inv.get(i) == Container.EMPTY_STACK) n++;
        return n;
    }

    // ---------------------------------------------------------------- item predicates

    protected @Nullable String itemName(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return null;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        return d == null ? null : d.name();
    }
    protected boolean matchesName(@Nullable ItemStack s, String name) {
        String n = itemName(s);
        return n != null && n.equals(name);
    }
    /** Items inside a container item (shulker) via its CONTAINER component — positional, empty list if none. */
    protected List<ItemStack> containerContents(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return List.of();
        List<ItemStack> c = s.getDataComponentsOrEmpty().get(DataComponentTypes.CONTAINER);
        return c == null ? List.of() : c;
    }
    protected @Nullable String customName(@Nullable ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return null;
        var comp = s.getDataComponentsOrEmpty().get(DataComponentTypes.CUSTOM_NAME);
        return comp == null ? null : com.zenith.util.ComponentSerializer.serializePlain(comp);
    }
    protected boolean isShulkerBox(@Nullable ItemStack s) {
        String n = itemName(s);
        return n != null && n.endsWith("shulker_box");
    }
    protected boolean isEnderChestItem(@Nullable ItemStack s) { return matchesName(s, "ender_chest"); }

    // ---------------------------------------------------------------- placement-spot finder

    /**
     * An empty cell beside the bot to place a shulker/echest. Prefers a flat floor with air above (lid opens
     * cleanly); falls back to any open cell with a solid face to attach to (wall/ceiling/floor).
     */
    protected @Nullable BlockPos selectSpotBeside(@Nullable BlockPos avoid) {
        BlockPos pf = BARITONE.getPlayerContext().playerFeet();
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int dy = 0; dy <= 1; dy++) {
            for (int[] d : dirs) {
                BlockPos cand = pf.add(d[0], dy, d[1]);
                if (!placeCellOpen(cand, pf, avoid)) continue;
                Block floor = World.getBlock(cand.x(), cand.y() - 1, cand.z());
                if (floor.isAir() || World.isFluid(floor)) continue;
                if (!World.getBlock(cand.x(), cand.y() + 1, cand.z()).isAir()) continue;
                return cand;
            }
        }
        for (int dy = -1; dy <= 1; dy++) {
            for (int[] d : dirs) {
                BlockPos cand = pf.add(d[0], dy, d[1]);
                if (!placeCellOpen(cand, pf, avoid)) continue;
                if (hasSolidNeighbor(cand)) return cand;
            }
        }
        return null;
    }

    private boolean placeCellOpen(BlockPos cand, BlockPos pf, @Nullable BlockPos avoid) {
        if (cand.equals(pf) || cand.equals(pf.above())) return false;
        if (avoid != null && cand.equals(avoid)) return false;
        if (!World.getBlock(cand.x(), cand.y(), cand.z()).isAir()) return false;
        if (playerBoxIntersects(cand)) return false;
        return !entityOccupies(cand);
    }

    private boolean hasSolidNeighbor(BlockPos c) {
        int[][] faces = {{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] f : faces) {
            Block b = World.getBlock(c.x() + f[0], c.y() + f[1], c.z() + f[2]);
            if (!b.isAir() && !World.isFluid(b)) return true;
        }
        return false;
    }

    private boolean entityOccupies(BlockPos p) {
        for (var e : CACHE.getEntityCache().getEntities().values()) {
            if (e == null) continue;
            if ((int) Math.floor(e.getX()) != p.x() || (int) Math.floor(e.getZ()) != p.z()) continue;
            double feetY = e.getY();
            if (feetY < p.y() + 1 && feetY + 2.0 > p.y()) return true;
        }
        return false;
    }

    private boolean playerBoxIntersects(BlockPos p) {
        double px = CACHE.getPlayerCache().getX(), py = CACHE.getPlayerCache().getY(), pz = CACHE.getPlayerCache().getZ();
        double half = 0.32, height = 1.8;
        boolean xo = p.x() < px + half && p.x() + 1 > px - half;
        boolean zo = p.z() < pz + half && p.z() + 1 > pz - half;
        boolean yo = p.y() < py + height && p.y() + 1 > py;
        return xo && yo && zo;
    }

    // ---------------------------------------------------------------- coordinate pathing

    protected GoalNear pathToNear(BlockPos pos) {
        GoalNear goal = new GoalNear(pos.x(), pos.y(), pos.z(), REACH_RANGE_SQ);
        BARITONE.pathTo(goal);
        return goal;
    }
    protected boolean arrivedAt(@Nullable GoalNear goal) {
        if (goal == null) return true;
        BlockPos feet = BARITONE.getPlayerContext().playerFeet();
        return goal.isInGoal(feet.x(), feet.y(), feet.z());
    }
    protected BlockPos playerFeet() { return BARITONE.getPlayerContext().playerFeet(); }

    // ---------------------------------------------------------------- world block scan

    protected @Nullable BlockPos nearestBlock(int radius, int yLo, int yHi, Predicate<String> nameTest) {
        BlockPos pf = playerFeet();
        BlockPos best = null;
        long bestD = Long.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = pf.x() + dx, z = pf.z() + dz;
                if (dx * dx + dz * dz > radius * radius) continue;
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = yLo; y <= yHi; y++) {
                    Block b = World.getBlock(x, y, z);
                    if (b.isAir() || !nameTest.test(b.name())) continue;
                    long d = (long) dx * dx + (long) dz * dz + (long) (y - pf.y()) * (y - pf.y());
                    if (d < bestD) { bestD = d; best = new BlockPos(x, y, z); }
                }
            }
        }
        return best;
    }
}
