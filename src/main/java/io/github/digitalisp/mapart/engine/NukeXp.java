package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.InventoryView;
import io.github.digitalisp.mapart.bot.InventoryView.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * XP for the nuke (pure, unit-tested). A 9,879-block map wears a netherite
 * pickaxe (2,031 uses) by about 2,270 uses with Unbreaking III, so it is mended inside the NUKE. The bot takes the
 * bottles for that at STORE, where it stands by the XP chest with free slots, and mends at a lane end in the NUKE
 * without a walk: a fetch in the NUKE would first have to get rid of the picked-up drops that fill the inventory.
 *
 * <p>The wear is measured per tool id over the NUKE's break tasks (uses lost per block broken, any tool of that id
 * carried); before {@link #MIN_BLOCKS} blocks are measured a pickaxe counts {@link #DEFAULT_PICKAXE_WEAR} and other
 * tools nothing.
 */
final class NukeXp {
    /** Uses per broken block of a pickaxe before a nuke was measured: Unbreaking III costs a use in 4 breaks. */
    static final double DEFAULT_PICKAXE_WEAR = 0.25;
    /** Broken blocks a tool id's measurement needs before it replaces the default. */
    static final int MIN_BLOCKS = 200;
    /** Share added to the estimate: the XP per bottle varies (3 to 11), damaged Mending boots take some of it. */
    static final double MARGIN = 0.15;
    /** At most three stacks are carried into a nuke. */
    static final int MAX_BOTTLES = 192;
    /**
     * Mending during the clear: share of a tool's maximum that must be left above the safety floor at the end of the
     * nuke by the wear bound before its lane-end mend may wait for the clear ({@link #lastsTheNuke}).
     */
    static final double DEFER_RESERVE = 0.10;

    /** Tool id → {uses lost, blocks broken while it was carried}. */
    private final Map<String, long[]> seen = new HashMap<>();

    /**
     * One break task: the uses left per tool id ({@link #usesById}) before and after it, and the blocks it broke. A tool
     * id with more uses afterwards (mended, swapped) or gone is not measured.
     */
    void observe(Map<String, Integer> before, Map<String, Integer> after, int broken) {
        if (broken <= 0) return;
        before.forEach((id, b) -> {
            Integer a = after.get(id);
            if (a == null || b == null || a > b) return;
            long[] s = seen.computeIfAbsent(id, k -> new long[2]);
            s[0] += b - a;
            s[1] += broken;
        });
    }

    /** Uses per broken block of tool {@code id}: measured, else {@link #DEFAULT_PICKAXE_WEAR} for a pickaxe, else 0. */
    double wearPerBlock(String id) {
        long[] s = seen.get(id);
        if (s != null && s[1] >= MIN_BLOCKS) return (double) s[0] / s[1];
        return "pickaxe".equals(InventoryView.toolKind(id)) ? DEFAULT_PICKAXE_WEAR : 0;
    }

    /**
     * XP bottles for a nuke of {@code blocks} blocks: per Mending tool id, the expected wear less the uses its tools have
     * above their mend threshold ({@link ToolCare#mendBelowUses}), so that they leave the nuke at about that threshold,
     * plus {@link #MARGIN}, at {@link ToolCare#XP_PER_BOTTLE} XP and {@link ToolCare#REPAIR_PER_XP} per point; at most
     * {@link #MAX_BOTTLES}. Tools without Mending need none (they are swapped at the floor).
     */
    int bottles(int blocks, List<Tool> tools, ToolCare care) {
        return bottles(blocks, tools, care, false);
    }

    /**
     * As {@link #bottles(int, List, ToolCare)}; {@code toTarget} (mending during the clear): enough to bring the tools
     * back to their mend target ({@link ToolCare#targetUses}) once the nuke is over, so the expected wear less the uses
     * above the target, plus what they already lack below it.
     */
    int bottles(int blocks, List<Tool> tools, ToolCare care, boolean toTarget) {
        if (blocks <= 0) return 0;
        Map<String, long[]> pool = new TreeMap<>(); // id → {uses left, uses at the mend threshold or target}
        for (Tool t : tools) {
            if (!t.mending() || t.max() <= 0) continue;
            long[] p = pool.computeIfAbsent(t.itemId(), k -> new long[2]);
            p[0] += t.remaining();
            p[1] += toTarget ? care.targetUses(t) : care.mendBelowUses(t);
        }
        double uses = 0;
        for (var e : pool.entrySet()) {
            double wear = blocks * wearPerBlock(e.getKey());
            double spare = e.getValue()[0] - e.getValue()[1];
            uses += Math.max(0, wear - (toTarget ? spare : Math.max(0, spare)));
        }
        if (uses <= 0) return 0;
        int n = (int) Math.ceil(uses * (1 + MARGIN) / (ToolCare.XP_PER_BOTTLE * ToolCare.REPAIR_PER_XP));
        return Math.min(MAX_BOTTLES, n);
    }

    /**
     * Uses per broken block of tool {@code id} from what was measured so far plus the running break task's own sample
     * ({@code liveUses} lost over {@code liveBlocks} broken while it was carried); NaN while fewer than
     * {@link #MIN_BLOCKS} blocks are measured in all. No default: the deferral below never goes by a guess.
     */
    double measuredWear(String id, long liveUses, long liveBlocks) {
        long[] s = seen.get(id);
        long uses = Math.max(0, liveUses) + (s == null ? 0 : s[0]);
        long blocks = Math.max(0, liveBlocks) + (s == null ? 0 : s[1]);
        return blocks >= MIN_BLOCKS ? (double) uses / blocks : Double.NaN;
    }

    /**
     * Mending during the clear: the Mending tool {@code t} finishes {@code blocksLeft} more nuke blocks at
     * {@code wearPerBlock} (measured: {@link #measuredWear}) with at least the safety floor plus {@link #DEFER_RESERVE}
     * of its maximum left, by an upper bound of the wear: the mean plus {@link #MARGIN} plus three standard deviations
     * (breaks wear a tool at random: Unbreaking III spends a use in 1 break of 4; the spread of n such breaks is at most
     * the square root of the mean). Then its lane-end mend waits for WAIT_CLEAR. False when unmeasured (NaN).
     */
    static boolean lastsTheNuke(Tool t, int blocksLeft, double wearPerBlock, ToolCare care) {
        if (Double.isNaN(wearPerBlock) || t.max() <= 0) return false;
        return t.remaining() - wearBound(blocksLeft, wearPerBlock) >= care.floor + Math.ceil(t.max() * DEFER_RESERVE);
    }

    /** Upper bound of the uses {@code blocks} breaks cost at {@code wearPerBlock} ({@link #lastsTheNuke}). */
    static double wearBound(int blocks, double wearPerBlock) {
        double mean = Math.max(0, blocks) * Math.max(0, wearPerBlock);
        return mean * (1 + MARGIN) + 3 * Math.sqrt(Math.max(1, mean));
    }

    /** "netherite_pickaxe 0.23/block (measured)" for each Mending tool carried, for the log line. */
    String describe(List<Tool> tools) {
        Map<String, String> out = new TreeMap<>();
        for (Tool t : tools) {
            if (!t.mending()) continue;
            long[] s = seen.get(t.itemId());
            boolean measured = s != null && s[1] >= MIN_BLOCKS;
            out.put(t.itemId(), String.format(java.util.Locale.ROOT, "%s %.2f/block%s",
                t.itemId().substring(t.itemId().indexOf(':') + 1), wearPerBlock(t.itemId()),
                measured ? " (measured)" : ""));
        }
        return out.isEmpty() ? "no Mending tool" : String.join(", ", out.values());
    }

    /** Uses left per tool id, summed over the stacks of that id ({@link #observe}'s snapshots). */
    static Map<String, Integer> usesById(List<Tool> tools) {
        Map<String, Integer> m = new TreeMap<>();
        for (Tool t : tools) if (t.max() > 0) m.merge(t.itemId(), t.remaining(), Integer::sum);
        return m;
    }
}
