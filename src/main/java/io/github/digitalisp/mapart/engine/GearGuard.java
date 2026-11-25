package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.InventoryView.Armor;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Armour-wear arithmetic for the durability guard (pure, unit-tested): when a
 * worn piece is due for mending, when it stops the running task, when it comes off (the hard floor), and when a carried
 * piece may be worn. Thresholds from {@code build.bootsMendBelowPercent} (feet), {@code build.armorMendBelowPercent}
 * (head, chest, legs), {@code build.armorParkPercent} and {@code build.armorOffPercent}, sanitised. Mending repairs a
 * random damaged Mending item the bot wears or holds, 2 durability per XP point: an XP bottle (7 XP on average) mends
 * about 14 uses spread over the damaged pieces.
 */
final class GearGuard {
    /** Mended: at or above this fraction (as the boots' mend always did). */
    static final double TARGET = EngineTuning.BOOTS_MIN_DURABILITY;
    /** Uses one bottle mends on average ({@link ToolCare#XP_PER_BOTTLE} × {@link ToolCare#REPAIR_PER_XP}). */
    static final double USES_PER_BOTTLE = ToolCare.XP_PER_BOTTLE * ToolCare.REPAIR_PER_XP;

    final int bootsBelowPercent;
    final int armorBelowPercent;
    final int parkPercent;
    final int offPercent;

    GearGuard(int bootsBelowPercent, int armorBelowPercent, int parkPercent, int offPercent) {
        this.offPercent = Math.max(0, Math.min(50, offPercent));
        this.bootsBelowPercent = Math.max(0, Math.min(95, bootsBelowPercent));
        this.armorBelowPercent = Math.max(0, Math.min(95, armorBelowPercent));
        this.parkPercent = Math.max(0, Math.min(95, parkPercent));
    }

    static GearGuard of(MapArtConfig.BuildCfg b) {
        return new GearGuard(b.bootsMendBelowPercent, b.armorMendBelowPercent, b.armorParkPercent, b.armorOffPercent);
    }

    /** The mend threshold of {@code a}'s kind (boots or the other armour), percent; 0 = never mended. */
    int belowPercent(Armor a) {
        return isBoots(a) ? bootsBelowPercent : armorBelowPercent;
    }

    /** Uses left at which a piece is at the hard floor: {@code armorOffPercent} of its maximum, at least 1. */
    int offUses(Armor a) {
        return Math.max(1, (int) Math.ceil(a.max() * offPercent / 100.0));
    }

    /** At or below the hard floor: comes off unless it is mended there and then. */
    boolean atOff(Armor a) {
        return a.remaining() <= offUses(a);
    }

    /** A Mending piece below its mend threshold, or at the hard floor. */
    boolean wantsMend(Armor a) {
        if (!a.mending()) return false;
        return atOff(a) || (long) a.remaining() * 100 < (long) a.max() * belowPercent(a);
    }

    /** A Mending piece due so badly that the running task stops for it: below {@code armorParkPercent}, or at the floor. */
    boolean urgent(Armor a) {
        if (!a.mending()) return false;
        return atOff(a) || (wantsMend(a) && (long) a.remaining() * 100 < (long) a.max() * parkPercent);
    }

    /** Mended (the mend goes on until every damaged Mending piece reads this). */
    static boolean mended(Armor a) {
        return a.fraction() >= TARGET;
    }

    /**
     * A carried piece may be worn: a Mending piece below its mend threshold (or at the hard floor) only with XP bottles
     * carried (it is mended at once, standing still); a piece without Mending only above the hard floor.
     */
    boolean wearable(Armor a, int bottles) {
        if (!a.mending()) return !atOff(a);
        return bottles > 0 || (!atOff(a) && (long) a.remaining() * 100 >= (long) a.max() * belowPercent(a));
    }

    /** Uses the worn Mending pieces below {@link #TARGET} still miss. */
    static int missing(List<Armor> worn) {
        int n = 0;
        for (Armor a : worn) {
            if (!a.worn() || !a.mending()) continue;
            n += Math.max(0, (int) Math.ceil(a.max() * TARGET) - a.remaining());
        }
        return n;
    }

    /** Bottles that bring every worn Mending piece to {@link #TARGET} at the average XP per bottle (0 when mended). */
    static int bottlesToMend(List<Armor> worn) {
        int m = missing(worn);
        return m <= 0 ? 0 : (int) Math.ceil(m / USES_PER_BOTTLE);
    }

    /** Sum of the uses left over the worn Mending pieces (a mend's progress). */
    static int uses(List<Armor> worn) {
        int n = 0;
        for (Armor a : worn) if (a.worn() && a.mending()) n += a.remaining();
        return n;
    }

    /** The worn pieces of {@code all}. */
    static List<Armor> worn(List<Armor> all) {
        List<Armor> l = new ArrayList<>();
        for (Armor a : all) if (a.worn()) l.add(a);
        return l;
    }

    /** The carried (not worn) pieces of {@code all}. */
    static List<Armor> carried(List<Armor> all) {
        List<Armor> l = new ArrayList<>();
        for (Armor a : all) if (!a.worn()) l.add(a);
        return l;
    }

    /** The lowest fraction among the pieces matching {@code p}, or null. */
    static Armor mostWorn(List<Armor> pieces, Predicate<Armor> p) {
        return pieces.stream().filter(p).min(Comparator.comparingDouble(Armor::fraction)).orElse(null);
    }

    /** The worn piece in {@code slot}, or null. */
    static Armor inSlot(List<Armor> all, int slot) {
        for (Armor a : all) if (a.slot() == slot) return a;
        return null;
    }

    static boolean isBoots(Armor a) {
        return a.slot() == Armor.FEET || a.itemId().endsWith("_boots");
    }

    /** "head", "chest", "legs", "feet" for slots 5..8. */
    static String slotName(int slot) {
        return switch (slot) {
            case Armor.HEAD -> "head";
            case Armor.CHEST -> "chest";
            case Armor.LEGS -> "legs";
            case Armor.FEET -> "feet";
            default -> "slot " + slot;
        };
    }

    /** "diamond_boots at 26% (110/429)" (plus " (no Mending)" for a piece without it). */
    static String describe(Armor a) {
        return a.name() + " at " + percent(a) + "% (" + a.remaining() + "/" + a.max() + ")" + (a.mending() ? "" : " (no Mending)");
    }

    static int percent(Armor a) {
        return (int) Math.floor(a.fraction() * 100);
    }

    /** "diamond_boots 26%, diamond_helmet 40%" for log lines. */
    static String list(List<Armor> pieces) {
        List<String> l = new ArrayList<>();
        for (Armor a : pieces) l.add(a.name() + " " + percent(a) + "%");
        return String.join(", ", l);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "GearGuard(boots<%d%%, armor<%d%%, park<%d%%, off<=%d%%)", bootsBelowPercent,
            armorBelowPercent, parkPercent, offPercent);
    }
}
