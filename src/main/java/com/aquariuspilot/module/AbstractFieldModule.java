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
import static com.zenith.Globals.BOT;
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
    /**
     * Priority for the last-resort "get this window shut" close, used only after a normal-priority close has
     * repeatedly lost the {@link com.zenith.feature.inventory.InventoryManager} arbitration. Deliberately
     * above AutoTotem (13000), the highest-priority stock inventory module: while a window is open
     * {@code Bot#tick} drops every movement and interaction input, so nothing else can make progress either,
     * and a close request moves no items.
     */
    protected static final int FORCE_CLOSE_PRIORITY = 14000;
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
    /** {@link #closeContainer()} at {@link #FORCE_CLOSE_PRIORITY} — for when the normal one keeps losing. */
    protected void closeContainerForced() {
        INVENTORY.submit(InventoryActionRequest.builder().owner(this).actions(new CloseContainer()).priority(FORCE_CLOSE_PRIORITY).build());
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
    /**
     * Swap player-inventory slot {@code slot} (9-44) into hotbar slot 0 (index 36). Used to force an item
     * into the position {@code InventoryUtil#searchPlayerInventory} reaches first among the hotbar, so a
     * by-item-id re-search (which is what Baritone's place does) cannot pick a different stack of the same
     * type. The item previously in slot 36 ends up in {@code slot}.
     */
    protected void moveToHotbarZero(int slot) {
        if (slot < 9 || slot > 44 || slot == 36) return;
        INVENTORY.submit(InventoryActionRequest.builder().owner(this)
            .actions(new MoveToHotbarSlot(slot, MoveToHotbarAction.from(0))).priority(ACTION_PRIORITY).build());
    }

    /**
     * True when {@code p}'s chunk is loaded, i.e. the block there is actually <i>known</i>.
     *
     * <p>{@link World#getBlock} resolves an unloaded chunk section to block state 0 — air. Without this gate
     * "the chunk unloaded" and "the block is gone" are indistinguishable, which is how a death mid-cycle used
     * to read as "the shulker broke fine" from across the world. {@link #placed} and {@link #isAir} are both
     * false for an unknown position, so callers have to handle "unknown" explicitly rather than defaulting to
     * either answer.
     */
    protected boolean chunkKnown(@Nullable BlockPos p) {
        return p != null && World.isChunkLoadedBlockPos(p.x(), p.z());
    }
    protected boolean placed(@Nullable BlockPos p) { return chunkKnown(p) && !World.getBlock(p.x(), p.y(), p.z()).isAir(); }
    protected boolean isAir(@Nullable BlockPos p) { return chunkKnown(p) && World.getBlock(p.x(), p.y(), p.z()).isAir(); }

    /** Standing on the ground and not gliding — the precondition for placing, breaking and pathing. */
    protected boolean onSolidGround() { return BOT.isOnGround() && !BOT.isFallFlying(); }

    protected static int openContainerId() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainerId(); }
    protected static @Nullable Container openContainer() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainer(); }
    protected boolean inventoryBusy() { return INVENTORY.hasActiveRequest(); }

    // ---------------------------------------------------------------- player-inventory view

    // The player portion of an open container window is its last 36 slots (main inventory then hotbar), so it
    // lines up one-for-one with player-inventory slots 9-44. Container-window helpers (findPlayerWindowSlot,
    // shiftClick) speak window indices; findInInv/countInInv speak inventory slots.

    protected static int playerWindowBase(Container c) { return Math.max(0, c.getSize() - 36); }
    /** Container-window index -> player-inventory slot (9-44). */
    protected static int windowSlotToInvSlot(Container c, int windowSlot) { return 9 + (windowSlot - playerWindowBase(c)); }
    /** Player-inventory slot (9-44) -> container-window index. */
    protected static int invSlotToWindowSlot(Container c, int invSlot) { return playerWindowBase(c) + (invSlot - 9); }

    /**
     * A window is only usable once its contents have arrived. {@code InventoryCache#openContainer} creates the
     * container with size 0 on {@code ClientboundOpenScreenPacket} and only sizes it when
     * {@code ClientboundContainerSetContentPacket} follows, so a tick landing in between would iterate zero
     * slots and conclude the chest was empty — and {@link #invSlotToWindowSlot} would map into chest slots.
     */
    protected static boolean windowReady(@Nullable Container c) { return c != null && c.getSize() >= 36; }

    /** The open, fully-synced container window, or {@code null} when only the player's own inventory is open. */
    protected static @Nullable Container playerWindow() {
        if (openContainerId() == 0) return null;
        Container c = openContainer();
        return windowReady(c) ? c : null;
    }

    /**
     * The live contents of player-inventory slot {@code invSlot}.
     *
     * <p><b>Not</b> simply {@code getPlayerInventory().get(slot)}: while a container window is open the server
     * reports every menu-driven slot change against the <i>window</i> id (and {@code ShiftClick} sends empty
     * predicted changed-slots, so the server response is the only source of truth). ZenithProxy's
     * {@code InventoryCache} keeps windows in separate {@code Container} objects and only back-populates
     * container 0 from the window's player portion when the window closes ({@code InventoryCache#popContainer}).
     * Reading container 0 mid-window therefore never sees our own pulls or deposits. Slots outside the
     * window's player portion (equipment 5-8, offhand 45) are not part of the window and always come from
     * container 0 — which is correct, since container work never changes them.
     *
     * <p>Static so {@link FlightGear} — a utility class, and therefore unable to inherit it — can share the
     * one implementation rather than carry a second copy of the same reasoning.
     */
    protected static ItemStack playerSlot(int invSlot) {
        Container w = playerWindow();
        if (w != null && invSlot >= 9 && invSlot <= 44) return w.getItemStack(invSlotToWindowSlot(w, invSlot));
        List<ItemStack> inv = CACHE.getPlayerCache().getPlayerInventory();
        return invSlot >= 0 && invSlot < inv.size() ? inv.get(invSlot) : Container.EMPTY_STACK;
    }

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
    // All three read through playerSlot(), so they stay correct while a container window is open.
    protected int findInInv(Predicate<ItemStack> pred) {
        for (int i = 9; i <= 44; i++) if (pred.test(playerSlot(i))) return i;
        return -1;
    }
    protected int countInInv(Predicate<ItemStack> pred) {
        int n = 0;
        for (int i = 9; i <= 44; i++) if (pred.test(playerSlot(i))) n++;
        return n;
    }
    /** Total item <i>count</i>, not slot count — the only sound way to tell whether one of a stackable item
     *  (ender chests stack to 64) left or entered the inventory. */
    protected int countItemsInInv(Predicate<ItemStack> pred) {
        int n = 0;
        for (int i = 9; i <= 44; i++) {
            ItemStack s = playerSlot(i);
            if (s != Container.EMPTY_STACK && pred.test(s)) n += s.getAmount();
        }
        return n;
    }
    protected int emptyMainSlots() {
        int n = 0;
        for (int i = 9; i <= 35; i++) if (playerSlot(i) == Container.EMPTY_STACK) n++;
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

    /**
     * Look for {@code blockName} in the 3x3x3 around a cell we just tried to place into, within interaction
     * reach of the bot. A place whose block-change packet arrives a tick late, or that Baritone resolved
     * against a different face, is far more likely than a place that silently did nothing — and re-placing
     * on top of it orphans a real container (with the kit still inside it, on a cherry-pick round). Adopting
     * the block that is actually there beats abandoning it.
     */
    protected @Nullable BlockPos adoptPlacedNearby(@Nullable BlockPos attempted, String blockName) {
        if (attempted == null) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = new BlockPos(attempted.x() + dx, attempted.y() + dy, attempted.z() + dz);
                    if (!chunkKnown(p)) continue;
                    if (!blockName.equals(World.getBlock(p.x(), p.y(), p.z()).name())) continue;
                    double d = distToBot(p);
                    if (d > 5.5) continue; // out of placement reach, so it can't be the one we just placed
                    if (d < bestD) { bestD = d; best = p; }
                }
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- position wording

    /**
     * Describe a position <b>relative to the bot</b> ("3 blocks east of the bot and 1 block up").
     *
     * <p>This plugin never writes absolute world coordinates to the log, to Discord or to in-game chat, so
     * every operator-facing message about a placed chest, a dropped shulker or a relocation spot goes through
     * here. The description is still precise enough to walk to.
     */
    protected String describeRelative(@Nullable BlockPos p) {
        if (p == null) return "an unknown spot";
        BlockPos f = playerFeet();
        int dx = p.x() - f.x(), dy = p.y() - f.y(), dz = p.z() - f.z();
        long horiz = Math.round(Math.sqrt((double) dx * dx + (double) dz * dz));
        if (horiz == 0 && dy == 0) return "right where the bot is standing";
        if (horiz == 0) return Math.abs(dy) + (Math.abs(dy) == 1 ? " block " : " blocks ") + (dy > 0 ? "above" : "below") + " the bot";
        StringBuilder sb = new StringBuilder()
            .append(horiz).append(horiz == 1 ? " block " : " blocks ").append(compassDir(dx, dz)).append(" of the bot");
        if (dy != 0) {
            sb.append(" and ").append(Math.abs(dy)).append(Math.abs(dy) == 1 ? " block " : " blocks ").append(dy > 0 ? "up" : "down");
        }
        return sb.toString();
    }

    private static String compassDir(int dx, int dz) {
        int ax = Math.abs(dx), az = Math.abs(dz);
        String ns = az == 0 || ax > 2 * az ? "" : (dz < 0 ? "north" : "south");
        String ew = ax == 0 || az > 2 * ax ? "" : (dx > 0 ? "east" : "west");
        if (ns.isEmpty() && ew.isEmpty()) return "away";
        return ns + ew;
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
