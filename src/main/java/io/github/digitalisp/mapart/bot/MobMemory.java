package io.github.digitalisp.mapart.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * Mobs remembered by where they are (the entity cache gives no ids here; they drift, a pillager in a water channel
 * too): a mob within {@link #MATCH} blocks of a record is that mob, and the record follows it. Used for mobs a fight
 * could not reach (ignored until the bot is hurt again). A record that no mob matched in
 * more than {@link #MAX_MISSES} {@link #without} calls in a row is dropped: that mob is gone (killed, despawned, out of
 * the area), and a new mob near its old spot must not be hidden by it. Pure; client tick thread.
 */
public final class MobMemory {
    /** A mob this close to a record (each axis) is the remembered one. */
    public static final double MATCH = 3.0;
    /** {@link #without} calls in a row a record may go unmatched before it is dropped. */
    public static final int MAX_MISSES = 2;

    private static final class Rec {
        double x, y, z;
        int misses;
        boolean seen;

        Rec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    private final List<Rec> records = new ArrayList<>();

    public void add(WorldView.Mob m) {
        if (!matches(m)) records.add(new Rec(m.x(), m.y(), m.z()));
    }

    public void addAll(List<WorldView.Mob> mobs) {
        for (WorldView.Mob m : mobs) add(m);
    }

    public boolean isEmpty() {
        return records.isEmpty();
    }

    public int size() {
        return records.size();
    }

    public void clear() {
        records.clear();
    }

    /** {@code m} is a remembered mob; its record moves to where it is now. */
    public boolean matches(WorldView.Mob m) {
        for (Rec r : records) {
            if (Math.abs(r.x - m.x()) <= MATCH && Math.abs(r.y - m.y()) <= MATCH && Math.abs(r.z - m.z()) <= MATCH) {
                r.x = m.x();
                r.y = m.y();
                r.z = m.z();
                r.seen = true;
                return true;
            }
        }
        return false;
    }

    /**
     * The mobs of {@code mobs} (all the mobs there are now, in the area) that are not remembered. Records none of them
     * matched count a miss, and go after more than {@link #MAX_MISSES} in a row.
     */
    public List<WorldView.Mob> without(List<WorldView.Mob> mobs) {
        if (records.isEmpty()) return mobs;
        for (Rec r : records) r.seen = false;
        List<WorldView.Mob> out = new ArrayList<>(mobs.size());
        for (WorldView.Mob m : mobs) if (!matches(m)) out.add(m);
        records.removeIf(r -> {
            if (r.seen) {
                r.misses = 0;
                return false;
            }
            return ++r.misses > MAX_MISSES;
        });
        return out;
    }
}
