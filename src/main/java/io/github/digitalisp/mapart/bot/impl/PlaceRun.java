package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Places one block (a replacement anvil at {@code site.anvil}) against a neighbouring block's face. Pure decisions,
 * tick by tick; {@link PlaceBlockTask} runs it on ZenithProxy.
 *
 * <p>The support is a full-cube neighbour of the cell. Only its face touching the cell is clicked, and only from where
 * that face's centre is in reach and in sight ({@link Approach}: exact-cell stand spot, look on one tick and click on
 * the next). Plain neighbours are tried first. If none can be used, a neighbour that does something on a right-click
 * (a crafting table, a chest, another anvil: {@link #interactive}) is used with a sneak-click, which places instead
 * of opening it; the eye is then 1.27 above the feet. That is the anvil tower's case: the anvil sits on a crafting table
 * one above the walkway, with anvils and chests around it. The bot never stands where its box would block the cell,
 * and never on a platform's art layer ({@link #standCells}). The placement is confirmed from the world; up to
 * {@link #MAX_ATTEMPTS} clicks.
 */
public final class PlaceRun<S extends PlacePipeline.Rotation> {
    static final int MAX_ATTEMPTS = 3;
    /** Neighbours tried as the support: below first (the block stands on it), then the sides, then above. */
    static final int[][] SUPPORT_DIRS = {{0, -1, 0}, {0, 0, -1}, {0, 0, 1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}};
    private static final Set<String> INTERACTIVE_WORDS = Set.of(
        "table", "anvil", "loom", "stonecutter", "grindstone", "note_block", "jukebox", "beacon", "bell",
        "furnace", "smoker", "chest", "barrel", "shulker", "hopper", "dispenser", "dropper", "crafter", "bed",
        "door", "gate", "button", "lever", "repeater", "comparator", "daylight", "respawn_anchor", "lodestone",
        "composter", "cauldron", "lectern", "enchanting");

    /** The game for one placement; the approach's {@link Approach.Io} plus what a placement needs. */
    public interface Io<S> extends Approach.Io<S> {
        /** A full collision cube, not air, not replaceable: something to place against. */
        boolean solidSupport(Pos p);

        /** A right-click on it does something (a menu, a toggle): {@link #interactive} or a block entity. */
        boolean interactive(Pos p);

        /** Not air and not replaceable: a placement there fails. */
        boolean occupied(Pos p);

        int count(String item);

        /** The item is in the main hand; otherwise starts putting it there (no click while moving) and returns false. */
        boolean hold(String item);

        /**
         * Aims the approach at these support faces only (face codes {@link Aim#DOWN} ..), sneaking while it gets ready
         * and clicks if {@code sneak}. The bot's box stays out of {@code keepClear}: no stand cell whose box would
         * cover it ({@link StandSpots#covers}) is allowed, and no click while the box touches it.
         */
        void aimAt(Map<Pos, Integer> faces, boolean sneak, Pos keepClear);
    }

    private final Pos pos;
    private final String item;
    private final double reach;
    private final int confirmTicks;
    private final Io<S> io;
    private @Nullable Approach<S> approach;
    private boolean brake = true;
    private boolean sneaking;
    private boolean triedSneak;
    private long sentAt = -1;
    private int attempts;
    private long holdSince = -1;
    private String failure = "";

    public PlaceRun(Pos pos, String item, double reach, int confirmTicks, Io<S> io) {
        this.pos = pos;
        this.item = ItemIds.ns(item);
        this.reach = reach;
        this.confirmTicks = Math.max(10, confirmTicks);
        this.io = io;
    }

    /**
     * Where the bot may stand to place: anywhere off the platforms' art layers ({@link StandSpots#offArtLayers}). Not
     * the clear-sweep rule of the presses: the placement runs in RENAME, with the flying machines parked and no clear
     * running, and on the moss site the anvil tower and the whole supply walkway lie in the sweep north of the tile
     * (gate B2: "none of the 3 floor cells within reach can be walked to", BLOCKED in RENAME).
     */
    public static StandSpots.CellRule standCells(List<PlatformGeometry> platforms) {
        return StandSpots.offArtLayers(platforms);
    }

    /** Ids whose right-click does something; a support of one of them needs a sneak-click. */
    public static boolean interactive(BlockSpec b) {
        String id = ItemIds.path(b.id());
        for (String w : INTERACTIVE_WORDS) if (id.contains(w)) return true;
        return false;
    }

    public String failure() {
        return failure;
    }

    public @Nullable Approach<S> approach() {
        return approach;
    }

    /** True once the bot sneaks to click (the support does something on a plain right-click). */
    public boolean sneaking() {
        return sneaking;
    }

    public String progress() {
        return sentAt >= 0 ? " (clicked, waiting for the block)" : approach == null ? "" : approach.progress();
    }

    public TaskStatus tick(long now) {
        BlockSpec cur = io.block(pos);
        if (cur.id().equals(item)) return TaskStatus.SUCCESS;
        if (sentAt >= 0) {
            io.holdStill();
            if (now - sentAt < confirmTicks) return TaskStatus.RUNNING;
            sentAt = -1;
            if (++attempts >= MAX_ATTEMPTS) return fail("placement at " + pos + " not confirmed after " + attempts + " clicks");
            if (approach != null) approach.retry();
        }
        if (io.occupied(pos)) return fail(pos + " is occupied by " + cur);
        if (io.count(item) <= 0) return fail("no " + item + " in the inventory");
        if (!io.hold(item)) {
            io.holdStill();
            if (holdSince < 0) holdSince = now;
            if (now - holdSince > 100) return fail("could not put " + item + " in the hand");
            return TaskStatus.RUNNING;
        }
        holdSince = -1;
        if (approach == null && !start(false)) {
            if (!start(true)) return fail("no full block next to " + pos + " to place against");
        }
        TaskStatus s = approach.tick(now);
        if (s == TaskStatus.FAILED) {
            String why = approach.failure();
            if (!sneaking && !triedSneak && start(true)) {
                io.log("Placing " + item + " at " + pos + ": no plain support face usable (" + why
                    + "); sneak-clicking a block that has a right-click action");
                return TaskStatus.RUNNING;
            }
            return fail("cannot place at " + pos + ": " + why);
        }
        if (s == TaskStatus.SUCCESS) sentAt = now;
        return TaskStatus.RUNNING;
    }

    /** Starts an approach to the support faces: plain supports, or (sneak) the ones with a right-click action. */
    private boolean start(boolean sneak) {
        if (sneak) triedSneak = true;
        Map<Pos, Integer> faces = new LinkedHashMap<>();
        for (int[] d : SUPPORT_DIRS) {
            Pos sp = pos.offset(d[0], d[1], d[2]);
            if (!io.solidSupport(sp) || io.interactive(sp) != sneak) continue;
            faces.put(sp, faceTowards(d));
        }
        if (faces.isEmpty()) return false;
        io.aimAt(faces, sneak, pos);
        sneaking = sneak;
        List<Pos> targets = new ArrayList<>(faces.keySet());
        approach = new Approach<>(ItemIds.path(item) + " placement at " + pos, targets, reach, null, io).brake(brake);
        return true;
    }

    /** {@link Approach#brake}: centring with the bot's speed (default on). Before the first tick. */
    public PlaceRun<S> brake(boolean on) {
        this.brake = on;
        return this;
    }

    /** Face code of the support face that touches the cell: the support lies at {@code d} from the cell. */
    static int faceTowards(int[] d) {
        if (d[1] < 0) return Aim.UP;
        if (d[1] > 0) return Aim.DOWN;
        if (d[2] < 0) return Aim.SOUTH;
        if (d[2] > 0) return Aim.NORTH;
        if (d[0] > 0) return Aim.WEST;
        return Aim.EAST;
    }

    private TaskStatus fail(String why) {
        failure = why;
        return TaskStatus.FAILED;
    }
}
