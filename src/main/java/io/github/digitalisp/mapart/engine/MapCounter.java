package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig.ActuatorCfg;
import io.github.digitalisp.mapart.config.MapArtConfig.SiteCfg;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The base's map counter ({@code site.mapCounter}): a button the engine presses once for every stored map, in COUNT.
 * Where it is: {@code mapCounter.pos}, or, when only its column is known
 * ({@code site.mapCounterColumn}), the one button found in that column of the loaded world, from {@link #SCAN_BELOW}
 * below to {@link #SCAN_ABOVE} above the first output chest's y (the supply walkway; the button sits by the supply
 * chests). Pure.
 */
public final class MapCounter {
    /** A column is scanned from this far below the reference y (the first output chest) ... */
    public static final int SCAN_BELOW = 6;
    /** ... to this far above it. */
    public static final int SCAN_ABOVE = 6;

    private MapCounter() { }

    /** The counter is configured ({@code site.mapCounter} set); {@link #configProblem} may still keep it from working. */
    public static boolean enabled(@Nullable SiteCfg site) {
        return site != null && site.mapCounter != null;
    }

    /**
     * What keeps a configured counter from ever being pressed, or null: a mode other than BUTTON (null or blank count as
     * BUTTON), or neither {@code pos} nor {@code mapCounterColumn}. Null when the counter is off.
     */
    public static @Nullable String configProblem(SiteCfg site) {
        ActuatorCfg c = site.mapCounter;
        if (c == null) return null;
        if (c.mode != null && !c.mode.isBlank() && !"BUTTON".equalsIgnoreCase(c.mode.strip()))
            return "site.mapCounter has mode " + c.mode + ": only BUTTON is supported";
        if (c.pos == null && site.mapCounterColumn == null)
            return "site.mapCounter has no pos and site.mapCounterColumn is not set";
        return null;
    }

    /**
     * {min, max} y scanned in {@code mapCounterColumn}: {@link #SCAN_BELOW} below to {@link #SCAN_ABOVE} above the first
     * output chest's y. Null without an output chest (the engine then scans around the bot's feet).
     */
    public static int @Nullable [] scanRange(SiteCfg site) {
        List<Pos> out = site.storage.outputChests;
        if (out == null || out.isEmpty() || out.getFirst() == null) return null;
        return scanRange(out.getFirst().y());
    }

    /** {min, max} y scanned around the reference y {@code refY}. */
    public static int[] scanRange(int refY) {
        return new int[]{refY - SCAN_BELOW, refY + SCAN_ABOVE};
    }

    /** A button of any wood or stone kind: the block id ends in {@code _button}. */
    public static boolean isButton(@Nullable BlockSpec b) {
        return b != null && b.id().endsWith("_button");
    }

    /** The button is pressed ({@code powered=true}): a wooden one pops after 30 ticks, a stone one after 20. */
    public static boolean pressed(@Nullable BlockSpec b) {
        return b != null && "true".equals(b.props().get("powered"));
    }

    /**
     * The buttons in the column {@code x}, {@code z} from {@code minY} to {@code maxY} (inclusive), bottom up, and how
     * many cells of that range are not loaded (they are never guessed).
     */
    public record Scan(List<Pos> buttons, int unloaded) {
        /** The one button, or null when there is none or several. */
        public @Nullable Pos single() {
            return unloaded == 0 && buttons.size() == 1 ? buttons.getFirst() : null;
        }
    }

    public static Scan scan(WorldView w, int x, int z, int minY, int maxY) {
        List<Pos> buttons = new ArrayList<>();
        int unloaded = 0;
        for (int y = minY; y <= maxY; y++) {
            Pos p = new Pos(x, y, z);
            if (!w.isLoaded(p)) {
                unloaded++;
                continue;
            }
            if (isButton(w.block(p))) buttons.add(p);
        }
        return new Scan(List.copyOf(buttons), unloaded);
    }

    /**
     * Largest {@code site.mapCounterCheckRadius} used (a 17-block cube, 4,913 blocks read per press). The owner's counter
     * needs 5 or less: its display's digit pistons, 6 and more blocks from the button, move with every count.
     */
    public static final int MAX_CHECK_RADIUS = 8;

    /** {@code site.mapCounterLoginDelaySeconds} in milliseconds, never negative. */
    public static long loginDelayMillis(SiteCfg site) {
        return Math.max(0, site.mapCounterLoginDelaySeconds) * 1000L;
    }

    /** {@code site.mapCounterCheckRadius} clamped to 0 .. {@link #MAX_CHECK_RADIUS}; 0 = no jam check. */
    public static int checkRadius(SiteCfg site) {
        return Math.clamp(site.mapCounterCheckRadius, 0, MAX_CHECK_RADIUS);
    }

    /**
     * Block properties that follow the counter's signal or its count, not its mechanism: the owner's counter display (lamps,
     * wire, repeaters, targets) changes them with every count, and hoppers, droppers and the button pulse them. The jam
     * check ignores them ({@link #normalize}).
     */
    public static final Set<String> VOLATILE_PROPS = Set.of("power", "powered", "lit", "triggered", "enabled", "locked");
    private static final Set<String> ANVILS = Set.of("minecraft:anvil", "minecraft:chipped_anvil",
        "minecraft:damaged_anvil");

    /**
     * The block as the jam check compares it: without {@link #VOLATILE_PROPS}, and any anvil as {@code minecraft:anvil}
     * (the bot's own anvil tower stands by the counter and wears with use). Ids, air, {@code extended}, {@code facing}
     * and every other property are kept.
     */
    public static BlockSpec normalize(BlockSpec b) {
        String id = ANVILS.contains(b.id()) ? "minecraft:anvil" : b.id();
        boolean strip = false;
        for (String k : b.props().keySet()) strip |= VOLATILE_PROPS.contains(k);
        if (!strip) return id.equals(b.id()) ? b : new BlockSpec(id, b.props());
        TreeMap<String, String> props = new TreeMap<>(b.props());
        props.keySet().removeAll(VOLATILE_PROPS);
        return new BlockSpec(id, props);
    }

    /** {@link #normalize} of every cell, in the same order. */
    public static Map<Pos, BlockSpec> normalize(Map<Pos, BlockSpec> cells) {
        Map<Pos, BlockSpec> out = new LinkedHashMap<>();
        cells.forEach((p, b) -> out.put(p, normalize(b)));
        return out;
    }

    /**
     * A piston that is stuck out: a {@code piston} or {@code sticky_piston} with {@code extended=true}, a
     * {@code piston_head}, or a {@code moving_piston}. The owner's counter has none within 5 blocks of its button at rest; its
     * display's digit pistons (6 and more blocks away) do stand extended at rest.
     */
    public static boolean stuck(BlockSpec b) {
        String id = b.id();
        return ((id.equals("minecraft:piston") || id.equals("minecraft:sticky_piston"))
            && "true".equals(b.props().get("extended")))
            || id.equals("minecraft:piston_head") || id.equals("minecraft:moving_piston");
    }

    /** The cells of {@code cells} that hold a {@link #stuck} piston, in order. */
    public static List<Pos> stuck(Map<Pos, BlockSpec> cells) {
        List<Pos> out = new ArrayList<>();
        cells.forEach((p, b) -> {
            if (stuck(b)) out.add(p);
        });
        return out;
    }

    /**
     * The loaded blocks of the cube {@code radius} around {@code centre} (unloaded ones are left out), in x, y, z
     * order: the counter's state before a press.
     */
    public static Map<Pos, BlockSpec> snapshot(WorldView w, Pos centre, int radius) {
        Map<Pos, BlockSpec> out = new LinkedHashMap<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Pos p = centre.offset(dx, dy, dz);
                    if (w.isLoaded(p)) out.put(p, w.block(p));
                }
            }
        }
        return out;
    }

    /** The cells of {@code like} that are loaded now, with their blocks now, in {@code like}'s order. */
    public static Map<Pos, BlockSpec> snapshot(WorldView w, Map<Pos, BlockSpec> like) {
        Map<Pos, BlockSpec> out = new LinkedHashMap<>();
        for (Pos p : like.keySet()) if (w.isLoaded(p)) out.put(p, w.block(p));
        return out;
    }

    /**
     * The cells in both {@code reference} and {@code now} whose {@link #normalize}d blocks differ, in
     * {@code reference}'s order (a cell missing from either, e.g. not loaded, is skipped).
     */
    public static List<Pos> differs(Map<Pos, BlockSpec> reference, Map<Pos, BlockSpec> now) {
        List<Pos> out = new ArrayList<>();
        reference.forEach((p, b) -> {
            BlockSpec n = now.get(p);
            if (n != null && !normalize(n).equals(normalize(b))) out.add(p);
        });
        return out;
    }

    /** As {@link #differs}, but every property counts: what a press changed at all (for the log). */
    public static List<Pos> differsRaw(Map<Pos, BlockSpec> reference, Map<Pos, BlockSpec> now) {
        List<Pos> out = new ArrayList<>();
        reference.forEach((p, b) -> {
            BlockSpec n = now.get(p);
            if (n != null && !n.equals(b)) out.add(p);
        });
        return out;
    }

    /**
     * Buttons in the 8 columns around {@code x}, {@code z} over the same y range, at most {@code max}: a hint for the
     * alert when the column holds none (a column given one block off).
     */
    public static List<Pos> around(WorldView w, int x, int z, int minY, int maxY, int max) {
        List<Pos> out = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                for (Pos p : scan(w, x + dx, z + dz, minY, maxY).buttons()) {
                    if (out.size() >= max) return out;
                    out.add(p);
                }
            }
        }
        return out;
    }
}
