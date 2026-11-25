package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.InventoryView.Tool;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Tool-wear arithmetic for the engine's "tools never break" rule (pure; unit-tested). The thresholds come from
 * {@code build.mendBelowPercent}, {@code build.mendTargetPercent} and {@code build.toolSafetyFloor}, sanitised.
 */
final class ToolCare {
    /** A bottle o' enchanting drops 3..11 XP (3 + 2 × uniform 0..4), 7 on average. */
    static final double XP_PER_BOTTLE = 7.0;
    /** Mending repairs 2 durability per XP point. */
    static final int REPAIR_PER_XP = 2;

    final int floor;
    final int belowPercent;
    final int targetPercent;

    ToolCare(int floor, int belowPercent, int targetPercent) {
        this.floor = Math.max(0, floor);
        this.belowPercent = Math.max(0, Math.min(100, belowPercent));
        this.targetPercent = Math.max(Math.max(1, this.belowPercent), Math.min(100, targetPercent));
    }

    static ToolCare of(MapArtConfig.BuildCfg b) {
        return new ToolCare(b.toolSafetyFloor, b.mendBelowPercent, b.mendTargetPercent);
    }

    /** At or below the safety floor: no block may be broken with it. */
    boolean atFloor(Tool t) {
        return t.remaining() <= floor;
    }

    /** A Mending tool at or below the mend threshold (or the floor). */
    boolean wantsMend(Tool t) {
        return t.mending() && (atFloor(t) || (long) t.remaining() * 100 <= (long) t.max() * belowPercent);
    }

    /** Uses left at which a Mending tool is due for mending ({@link #wantsMend}: at or below them, or the floor). */
    int mendBelowUses(Tool t) {
        return Math.max(floor, (int) ((long) t.max() * belowPercent / 100));
    }

    /** Uses left that count as mended. */
    int targetUses(Tool t) {
        return (int) Math.ceil(t.max() * targetPercent / 100.0);
    }

    double targetFraction() {
        return targetPercent / 100.0;
    }

    boolean mended(Tool t) {
        return t.remaining() >= targetUses(t);
    }

    /** Bottles that bring {@code t} to the target at the average XP per bottle: ≥ 1 while below it, ≤ {@code cap}. */
    int bottlesToMend(Tool t, int cap) {
        int missing = targetUses(t) - t.remaining();
        if (missing <= 0) return 0;
        int n = (int) Math.ceil(missing / (XP_PER_BOTTLE * REPAIR_PER_XP));
        return Math.max(1, Math.min(Math.max(1, cap), n));
    }

    /** True if another tool of {@code t}'s kind is above the floor. */
    boolean hasSpare(List<Tool> tools, Tool t) {
        for (Tool o : tools) if (o != t && t.kind() != null && t.kind().equals(o.kind()) && !atFloor(o)) return true;
        return false;
    }

    /** The lowest fraction among the tools matching {@code p}, or null. */
    static Tool mostWorn(List<Tool> tools, Predicate<Tool> p) {
        return tools.stream().filter(p).min(Comparator.comparingDouble(Tool::fraction)).orElse(null);
    }

    /** Sum of uses left over the Mending tools with id {@code itemId} (progress check of a mend). */
    static int mendingUses(List<Tool> tools, String itemId) {
        int n = 0;
        for (Tool t : tools) if (t.mending() && t.itemId().equals(itemId)) n += t.remaining();
        return n;
    }

    /** "diamond_pickaxe at 12/1561" (plus " (no Mending)" for a tool without it). */
    static String describe(Tool t) {
        String id = t.itemId().substring(t.itemId().indexOf(':') + 1);
        return id + " at " + t.remaining() + "/" + t.max() + (t.mending() ? "" : " (no Mending)");
    }
}
