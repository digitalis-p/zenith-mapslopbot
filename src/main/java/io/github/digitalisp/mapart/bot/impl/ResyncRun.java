package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The re-sync around a spot the server kept setting the bot back to. Pure: {@link ResyncTask} feeds it ZenithProxy, the
 * tests a fake world.
 * <ol>
 *   <li>Tick 1: the bot stopped, its movement state resynced from the cache and ZenithProxy's open block predictions
 *       ended ({@link Port#resyncLocal}), as the setback escape does.</li>
 *   <li>Then, for each column within {@link #RADIUS} of the spot, nearest first: its walking surface (the first block
 *       from the spot's feet level down, at most {@link #DEPTH} below) is looked at (a real rotation whose ray hits
 *       its top face) and, on the next tick, with that rotation still held, clicked: an off-hand use-item-on on its
 *       top face. Vanilla's {@code handleUseItemOn} answers every click it handles with a block update for the
 *       clicked block and one for the block above it, whether or not anything changed; with an empty or totem
 *       off-hand nothing changes (an off-hand click never runs a block's own use action: no button, door or chest).
 *       The look first keeps Grim's RotationPlace quiet (it checks the rotations before and after a click). No click
 *       at all when the off-hand holds something else.</li>
 *   <li>{@link #SETTLE_TICKS} ticks held still for the answers, then SUCCESS: {@link #summary()} says how many were
 *       answered and which cached blocks the answers changed (a ghost block, if there was one).</li>
 * </ol>
 * It never moves, places, breaks or touches the inventory.
 */
public final class ResyncRun {
    /** Columns around the spot re-requested in each direction (a 5x5 square). */
    public static final int RADIUS = 2;
    /** Levels below the spot's feet level searched for a column's walking surface. */
    public static final int DEPTH = 2;
    /** Ticks held still after the last click, for the server's answers. */
    public static final int SETTLE_TICKS = 10;
    /** Ticks a look may wait to be executed (another module owns the input) before its block is skipped. */
    public static final int LOOK_WAIT_TICKS = 5;

    /** What the re-sync needs from ZenithProxy. */
    public interface Port {
        /** Baritone stopped, the bot's movement state resynced from the cache, open block predictions ended. */
        void resyncLocal();

        /** Keeps the input owned and the bot still, rotation unchanged. */
        void holdStill();

        /** Keeps the input owned and the bot still, looking at ({@code yaw}, {@code pitch}). */
        void look(float yaw, float pitch);

        /** A container menu is open. */
        boolean containerOpen();

        /** Why the off-hand must not click (it holds something that could act on a block), or null: empty or a totem. */
        @Nullable String offhandProblem();

        /** The cached block at {@code p} is air. */
        boolean air(Pos p);

        /** Why {@code clicked} is not clicked (not loaded, a block entity, protected), or null. */
        @Nullable String skip(Pos clicked);

        /** {yaw, pitch} whose ray from the eye hits the top face of {@code clicked} within reach, or null. */
        float @Nullable [] aim(Pos clicked);

        /** This tick's look request is pending and the previous tick's was executed: the server has that rotation. */
        boolean lookExecuted();

        /**
         * The off-hand use-item-on on the top face of {@code clicked}, at the point the rotation in effect hits; false
         * (nothing sent) if that ray no longer hits the face.
         */
        boolean click(Pos clicked);

        /** The cached block at {@code p}, as text. */
        String block(Pos p);

        /** A mark of the block updates received so far. */
        long mark();

        /** A block update for {@code p} arrived after {@code mark} was taken. */
        boolean updatedSince(Pos p, long mark);
    }

    private final Pos at;
    private final List<Pos> columns;
    private int ticks;
    private int next;
    private int settled;
    private @Nullable String offhand;
    private long mark;
    /** The block looked at, waiting for its click; null between blocks. */
    private @Nullable Pos target;
    private float[] rot = new float[2];
    private int lookWait;
    private final List<Pos> clicked = new ArrayList<>();
    private final Map<Pos, String> before = new LinkedHashMap<>();
    private final List<String> skipped = new ArrayList<>();
    private @Nullable String failure;
    private String summary = "";

    /** A re-sync around the feet cell {@code at}. */
    public ResyncRun(Pos at) {
        this.at = at;
        this.columns = columns(at);
    }

    /** The columns within {@link #RADIUS} of {@code at} (at its level), nearest first. */
    static List<Pos> columns(Pos at) {
        List<Pos> out = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++) out.add(new Pos(at.x() + dx, at.y(), at.z() + dz));
        out.sort(Comparator.comparingInt((Pos p) -> (p.x() - at.x()) * (p.x() - at.x()) + (p.z() - at.z()) * (p.z() - at.z()))
            .thenComparingInt(Pos::x).thenComparingInt(Pos::z));
        return List.copyOf(out);
    }

    /** One tick: RUNNING, SUCCESS once settled ({@link #summary}), FAILED ({@link #failure}) with a menu open. */
    public TaskStatus tick(Port p) {
        ticks++;
        if (ticks == 1) {
            p.holdStill();
            p.resyncLocal();
            offhand = p.offhandProblem();
            mark = p.mark();
            return TaskStatus.RUNNING;
        }
        if (p.containerOpen()) {
            p.holdStill();
            failure = "a container menu is open";
            return TaskStatus.FAILED;
        }
        if (target != null) return clickTick(p);
        if (offhand == null && aimNext(p)) return TaskStatus.RUNNING;
        p.holdStill();
        if (settled++ < SETTLE_TICKS) return TaskStatus.RUNNING;
        summary = summarize(p);
        return TaskStatus.SUCCESS;
    }

    /** Looks at the next column's surface that can be clicked; false when none is left. */
    private boolean aimNext(Port p) {
        while (next < columns.size()) {
            Pos column = columns.get(next++);
            Pos c = surface(p, column);
            if (c == null) {
                skipped.add(column.key() + " (no block within " + DEPTH + " below)");
                continue;
            }
            String why = p.skip(c);
            float[] r = why == null ? p.aim(c) : null;
            if (why == null && r == null) why = "top face hidden or out of reach";
            if (why != null) {
                skipped.add(c.key() + " (" + why + ")");
                continue;
            }
            target = c;
            rot = r;
            lookWait = 0;
            p.look(r[0], r[1]);
            return true;
        }
        return false;
    }

    /** The tick after the look: click with it held, or wait for it to be executed. */
    private TaskStatus clickTick(Port p) {
        Pos c = target;
        p.look(rot[0], rot[1]); // held through the click: the movement packet after it carries the same rotation
        if (!p.lookExecuted()) {
            if (++lookWait <= LOOK_WAIT_TICKS) return TaskStatus.RUNNING;
            skipped.add(c.key() + " (the look was not executed)");
            target = null;
            return TaskStatus.RUNNING;
        }
        before.putIfAbsent(c, p.block(c));
        before.putIfAbsent(c.above(), p.block(c.above()));
        if (p.click(c)) clicked.add(c);
        else skipped.add(c.key() + " (the look no longer hits it)");
        target = null;
        return TaskStatus.RUNNING;
    }

    /** The first non-air block of {@code column} from its level down to {@link #DEPTH} below, or null. */
    private static @Nullable Pos surface(Port p, Pos column) {
        for (int d = 0; d <= DEPTH; d++) {
            Pos c = column.offset(0, -d, 0);
            if (!p.air(c)) return c;
        }
        return null;
    }

    private String summarize(Port p) {
        StringBuilder s = new StringBuilder("resync at ").append(at.key()).append(": ");
        if (offhand != null) {
            return s.append("no block re-requested (").append(offhand)
                .append("); movement state and block predictions resynced only").toString();
        }
        int answered = 0;
        for (Pos c : clicked) if (p.updatedSince(c, mark) || p.updatedSince(c.above(), mark)) answered++;
        List<String> changed = new ArrayList<>();
        for (var e : before.entrySet()) {
            String now = p.block(e.getKey());
            if (!now.equals(e.getValue())) changed.add(e.getKey().key() + " " + e.getValue() + " -> " + now);
        }
        s.append("re-requested ").append(clicked.size()).append(clicked.size() == 1 ? " block" : " blocks")
            .append(" and the cells above them (").append(answered).append(" answered)");
        if (!skipped.isEmpty())
            s.append(", ").append(skipped.size()).append(" skipped (first: ").append(skipped.getFirst()).append(')');
        if (changed.isEmpty()) s.append("; nothing changed: the cached blocks matched the server's");
        else s.append("; changed: ").append(String.join("; ", changed));
        return s.toString();
    }

    /** The columns, nearest first. */
    public List<Pos> columns() {
        return columns;
    }

    /** The blocks clicked so far. */
    public List<Pos> clicked() {
        return List.copyOf(clicked);
    }

    public @Nullable String failure() {
        return failure;
    }

    /** What the re-sync did and found (after SUCCESS). */
    public String summary() {
        return summary;
    }
}
