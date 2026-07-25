package com.aquariuspilot.module;

import com.aquariuspilot.AquariusPilotPlugin;
import com.zenith.cache.data.inventory.Container;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.entity.EquipmentSlot;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;

import static com.zenith.Globals.CACHE;

/**
 * Flight-readiness checklist + deficit logic, shared by {@link ElytraPilotModule} (pre-flight gating) and
 * {@link RegearModule} (selective "refill only what's missing"). Ported from AquariusProxy's
 * {@code com.aquarius.module.impl.FlightGear}, trimmed to this plugin's flat (non-profile) config.
 */
public final class FlightGear {
    private FlightGear() {}

    private static com.aquariuspilot.config.AquariusPilotConfig.ElytraPilot cfg() {
        return AquariusPilotPlugin.PLUGIN_CONFIG.elytraPilot;
    }

    // ---------------------------------------------------------------- item predicates

    public static boolean isElytra(ItemStack s)   { return is(s, "elytra"); }
    public static boolean isTotem(ItemStack s)    { return is(s, "totem_of_undying"); }
    public static boolean isFirework(ItemStack s) { return is(s, "firework_rocket"); }
    public static boolean isEgap(ItemStack s)     { return is(s, "enchanted_golden_apple"); }
    public static boolean isEchest(ItemStack s)   { return is(s, "ender_chest"); }
    public static boolean isPickaxe(ItemStack s)  { String n = name(s); return n != null && n.endsWith("pickaxe"); }
    public static boolean isWeapon(ItemStack s)   { String n = name(s); return n != null && (n.endsWith("sword") || n.endsWith("_axe")); }
    public static boolean isOtherArmor(ItemStack s) {
        String n = name(s);
        return n != null && (n.endsWith("_helmet") || n.endsWith("_leggings") || n.endsWith("_boots"));
    }

    private static boolean is(ItemStack s, String name) {
        String n = name(s);
        return n != null && n.equals(name);
    }
    private static String name(ItemStack s) {
        if (s == null || s == Container.EMPTY_STACK) return null;
        ItemData d = ItemRegistry.REGISTRY.get(s.getId());
        return d == null ? null : d.name();
    }

    // ---------------------------------------------------------------- counts (worn + offhand + inventory)

    private static List<ItemStack> inv() { return CACHE.getPlayerCache().getPlayerInventory(); }
    private static ItemStack worn(EquipmentSlot slot) { return CACHE.getPlayerCache().getEquipment(slot); }

    public static boolean elytraWorn() { return isElytra(worn(EquipmentSlot.CHESTPLATE)); }
    public static int elytraCount() { return (elytraWorn() ? 1 : 0) + countItems(FlightGear::isElytra, false); }

    public static int armorPiecesAnywhere() {
        int n = 0;
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HELMET, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS})
            if (worn(s) != Container.EMPTY_STACK) n++;
        n += countItems(FlightGear::isOtherArmor, false);
        return n;
    }

    // ---------------------------------------------------------------- armor slots (helmet / leggings / boots)

    private static final EquipmentSlot[] ARMOR_SLOTS =
        {EquipmentSlot.HELMET, EquipmentSlot.LEGGINGS, EquipmentSlot.BOOTS};
    private static final String[] ARMOR_SUFFIX = {"_helmet", "_leggings", "_boots"};

    /** Which of {@link #ARMOR_SLOTS} this piece belongs in, or -1 if it is not helmet/leggings/boots. */
    private static int armorSlotIndex(ItemStack s) {
        String n = name(s);
        if (n == null) return -1;
        for (int i = 0; i < ARMOR_SUFFIX.length; i++) if (n.endsWith(ARMOR_SUFFIX[i])) return i;
        return -1;
    }

    /** Is that slot already accounted for - worn, or a piece for it already sitting in the inventory? */
    private static boolean slotCovered(int idx) {
        if (worn(ARMOR_SLOTS[idx]) != Container.EMPTY_STACK) return true;
        final String suffix = ARMOR_SUFFIX[idx];
        return countItems(s -> { String n = name(s); return n != null && n.endsWith(suffix); }, false) > 0;
    }

    private static boolean allArmorSlotsCovered() {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) if (!slotCovered(i)) return false;
        return true;
    }

    public static boolean offhandTotem() { return isTotem(worn(EquipmentSlot.OFF_HAND)); }
    public static int totemCount()       { return countItems(FlightGear::isTotem, true); }
    public static int fireworkCount()    { return countItems(FlightGear::isFirework, false); }
    public static int egapCount()        { return countItems(FlightGear::isEgap, false); }
    public static int echestCount()      { return countItems(FlightGear::isEchest, false); }
    public static boolean hasPickaxe()   { return countItems(FlightGear::isPickaxe, false) > 0; }
    public static boolean hasWeapon()    { return countItems(FlightGear::isWeapon, false) > 0; }

    public static boolean egapCountSatisfied()  { return egapCount() >= cfg().preflightMinEgaps; }
    public static boolean totemCountSatisfied() { return totemCount() >= cfg().preflightMinTotems; }

    private static int countItems(java.util.function.Predicate<ItemStack> pred, boolean includeOffhand) {
        int n = 0;
        List<ItemStack> inv = inv();
        for (int i = 9; i <= 44; i++) {
            ItemStack s = inv.get(i);
            if (s != Container.EMPTY_STACK && pred.test(s)) n += s.getAmount();
        }
        if (includeOffhand) {
            ItemStack off = worn(EquipmentSlot.OFF_HAND);
            if (off != Container.EMPTY_STACK && pred.test(off)) n += off.getAmount();
        }
        return n;
    }

    // ---------------------------------------------------------------- gating + reporting

    public static boolean anyDeficit() {
        var c = cfg();
        return anySupplyDeficit() || (c.preflightOffhandTotem && !offhandTotem());
    }

    /**
     * {@link #anyDeficit()} minus the offhand-totem clause. That one clause is an <i>equip</i> state, not a
     * supply shortfall: no amount of pulling items out of shulkers can satisfy it (Regear's GEAR_UP phase
     * moves a totem to the offhand at the very end of the cycle). Including it in Regear's cherry-pick
     * satisfaction test would keep the loop opening shulkers until it hit its cap on every single run.
     */
    public static boolean anySupplyDeficit() {
        var c = cfg();
        return elytraCount() < Math.max(1, c.preflightMinElytras)
            || armorPiecesAnywhere() < c.preflightMinArmor
            || totemCount() < c.preflightMinTotems
            || fireworkCount() < c.preflightMinFireworks
            || egapCount() < c.preflightMinEgaps
            || (c.preflightRequirePickaxe && !hasPickaxe())
            || echestCount() < c.preflightMinEchests;
    }

    public static boolean ready() {
        var c = cfg();
        return elytraWorn()
            && elytraCount() >= Math.max(1, c.preflightMinElytras)
            && armorPiecesAnywhere() >= c.preflightMinArmor
            && totemCount() >= c.preflightMinTotems
            && (!c.preflightOffhandTotem || offhandTotem())
            && fireworkCount() >= c.preflightMinFireworks
            && egapCount() >= c.preflightMinEgaps
            && (!c.preflightRequirePickaxe || hasPickaxe())
            && echestCount() >= c.preflightMinEchests;
    }

    public static String report() {
        var c = cfg();
        StringBuilder b = new StringBuilder("Pre-flight check:\n");
        mark(b, "elytra+armor", elytraWorn() && elytraCount() >= Math.max(1, c.preflightMinElytras) && armorPiecesAnywhere() >= c.preflightMinArmor,
            (elytraWorn() ? elytraCount() + "/" + c.preflightMinElytras + " elytra" : "NO elytra worn")
                + " + " + armorPiecesAnywhere() + "/" + c.preflightMinArmor + " armor");
        mark(b, "totems", totemCount() >= c.preflightMinTotems && (!c.preflightOffhandTotem || offhandTotem()),
            totemCount() + "/" + c.preflightMinTotems + (offhandTotem() ? ", offhand ok" : ", offhand EMPTY"));
        mark(b, "fireworks", fireworkCount() >= c.preflightMinFireworks, fireworkCount() + "/" + c.preflightMinFireworks);
        mark(b, "egaps", egapCount() >= c.preflightMinEgaps, egapCount() + "/" + c.preflightMinEgaps);
        mark(b, "pickaxe", !c.preflightRequirePickaxe || hasPickaxe(), hasPickaxe() ? "ok" : "missing");
        mark(b, "echests", echestCount() >= c.preflightMinEchests, echestCount() + "/" + c.preflightMinEchests);
        return b.toString().stripTrailing();
    }

    private static void mark(StringBuilder b, String label, boolean ok, String detail) {
        b.append(ok ? "  [OK] " : "  [X]  ").append(label).append(": ").append(detail).append('\n');
    }

    /** Would pulling this kit item help meet a still-unmet "have" deficit? Drives Regear's cherry-pick fallback. */
    public static boolean stillNeeds(ItemStack candidate) {
        var c = cfg();
        if (isElytra(candidate))     return elytraCount() < Math.max(1, c.preflightMinElytras);
        if (isOtherArmor(candidate)) return armorStillNeeded(candidate);
        if (isTotem(candidate))      return totemCount() < c.preflightMinTotems;
        if (isFirework(candidate))   return fireworkCount() < c.preflightMinFireworks;
        if (isEgap(candidate))       return egapCount() < c.preflightMinEgaps;
        if (isPickaxe(candidate))    return c.preflightRequirePickaxe && !hasPickaxe();
        if (isWeapon(candidate))     return c.preflightWantWeapon && !hasWeapon();
        if (isEchest(candidate))     return echestCount() < c.preflightMinEchests;
        return false;
    }

    /**
     * Slot-aware armour want, for {@link #stillNeeds}. The pre-flight gate itself stays a plain count
     * ({@link #armorPiecesAnywhere()} vs {@code preflightMinArmor}), but asking that count whether a given
     * <i>piece</i> is wanted makes every piece read as wanted while the total is short - so with
     * {@code regear.singleItemShulkers} storage Regear would empty the helmet shulker of two or three helmets
     * before it ever reached the leggings and boots shulkers. Instead: cover each bare slot once first, and
     * only once the bot is fully clothed does a spare piece count toward a {@code preflightMinArmor} that has
     * been deliberately set above three.
     */
    private static boolean armorStillNeeded(ItemStack candidate) {
        if (armorPiecesAnywhere() >= cfg().preflightMinArmor) return false;   // already satisfied
        int idx = armorSlotIndex(candidate);
        if (idx == -1) return false;
        if (!slotCovered(idx)) return true;                                   // fill this bare slot
        return allArmorSlotsCovered();                                        // fully clothed: stock spares
    }
}
