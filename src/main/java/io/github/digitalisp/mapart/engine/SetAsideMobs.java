package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.WorldView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Mobs a fight found out of reach, set aside by entity id ({@code build.fightSetAsideUnreachable}).
 * Live 2026-10-24 (j0016 [2,1], soul sand): a wandering trader and its trader llama on a
 * structure 8 blocks above the floor started 17 fights in one 34-minute map (8.3 min). Every fight found them out of
 * reach and set them aside, but the position memory ({@link io.github.digitalisp.mapart.bot.MobMemory}) drops a record after
 * three looks without its mob, and the client's entity cache loses both mobs whenever the bot walks beyond the
 * server's entity tracking range (restocks, far trips, the station); back in range they were new mobs to it, and the
 * next fight began. An entity keeps its id while it is tracked again, so a record here is kept by id, not by position,
 * and stays while the mob is out of sight. It ends:
 * <ul>
 *   <li>when the mob hits the bot ({@link #hit}: the engine's damage attribution); any listed mob's hit also ends every
 *   hostile record (one out of reach may be the one shooting: the set-aside never hides an attacker), while the
 *   passive ones ({@link #PASSIVE}) that did not hit stay;</li>
 *   <li>when it comes within reach ({@link #withinReach}) after it was seen out of reach since it was set aside (a mob
 *   set aside while within reach, behind a fence, would otherwise start a fight on every look);</li>
 *   <li>a creeper ({@link #creeperClose}): when it is fusing or within {@link #CREEPER_CLOSE} of the bot, at once (set
 *   aside within reach or not): a creeper a few blocks from the bot is never hidden;</li>
 *   <li>when its time is up ({@code build.fightSetAsideMinutes}, game time);</li>
 *   <li>never by position: a new entity (a new id: killed and another came, or reloaded with its chunk) is a new mob
 *   and is fought as usual.</li>
 * </ul>
 * A mob without an id ({@code -1}) is never set aside. Pure but for the log lines it hands to {@code log}; client tick
 * thread.
 */
final class SetAsideMobs {
    /** Listed mobs that do not attack on their own: only their own hit ends their record, not another mob's. */
    static final Set<String> PASSIVE = Set.of("wandering_trader", "trader_llama", "llama");
    /** Within reach on the bot's own ground: this close horizontally (feet to feet, as the cooldown's melee range). */
    static final double REACH = BuildEngine.MELEE_RANGE;
    /** ... with its feet at most this far above or below the bot's feet. */
    static final double REACH_DY = 1.5;
    /** Within reach at the floor's height: this close horizontally to the bot ... */
    static final double FLOOR_NEAR = 8.0;
    /** ... with its feet from this far below the art layer (soul sand's top, 0.125 below) ... */
    static final double FLOOR_BELOW = 0.5;
    /** ... to this far above it (standing on placed art, 1.0 above). */
    static final double FLOOR_ABOVE = 1.5;
    /**
     * A creeper this close to the bot (feet to feet, any direction) is never set aside: the fight's hit and run deals
     * with it (CombatTask.CREEPER_CLOSE, the same 4 blocks; it swells within 3).
     */
    static final double CREEPER_CLOSE = 4.0;
    /** One "no fight for it" line per mob at most this often (1 min game time). */
    static final int LOG_TICKS = 1200;
    /** Records kept at most (the oldest go first). */
    static final int MAX_RECORDS = 256;

    /**
     * Where the bot is for one look: the engine tick, the time limit in ticks, the bot's feet, and the art layer's Y of
     * the platform it works on ({@code Double.NaN}: none).
     */
    record Look(long tick, long limitTicks, double x, double y, double z, double artY) {
    }

    private static final class Rec {
        final int id;
        final String type;
        final long since;
        /** Seen out of reach since it was set aside: coming within reach now ends the record. */
        boolean armed;
        long logged = Long.MIN_VALUE / 2;

        Rec(int id, String type, long since) {
            this.id = id;
            this.type = type;
            this.since = since;
        }
    }

    private final Map<Integer, Rec> records = new LinkedHashMap<>();
    private final Consumer<String> log;

    SetAsideMobs(Consumer<String> log) {
        this.log = log;
    }

    /** Sets {@code m} aside from {@code tick} (a mob set aside again starts afresh); one without an id is not. */
    void add(WorldView.Mob m, long tick) {
        if (m.id() < 0) return;
        records.remove(m.id());
        records.put(m.id(), new Rec(m.id(), m.type(), tick));
        Iterator<Integer> it = records.keySet().iterator();
        while (records.size() > MAX_RECORDS && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    void addAll(List<WorldView.Mob> mobs, long tick) {
        for (WorldView.Mob m : mobs) add(m, tick);
    }

    boolean isEmpty() {
        return records.isEmpty();
    }

    int size() {
        return records.size();
    }

    void clear() {
        records.clear();
    }

    /** {@code m} has a record (after the last look; no look here). */
    boolean contains(WorldView.Mob m) {
        return records.containsKey(m.id());
    }

    static boolean passive(String type) {
        return PASSIVE.contains(type);
    }

    /**
     * Within reach of the bot, cheaply: within {@link #REACH} horizontally with its feet within {@link #REACH_DY} of the
     * bot's (on the ground the bot stands on: KillAura hits it from there or a step away), or with its feet at the
     * floor's height (the art layer's Y {@code -} {@link #FLOOR_BELOW} to {@code +} {@link #FLOOR_ABOVE}) within
     * {@link #FLOOR_NEAR} horizontally of the bot (on the platform the bot walks). A mob on a roof above the platform or
     * in a channel below its floor is neither.
     */
    static boolean withinReach(WorldView.Mob m, Look at) {
        double dx = m.x() - at.x(), dz = m.z() - at.z();
        double h2 = dx * dx + dz * dz;
        if (h2 <= REACH * REACH && Math.abs(m.y() - at.y()) <= REACH_DY) return true;
        return !Double.isNaN(at.artY()) && h2 <= FLOOR_NEAR * FLOOR_NEAR
            && m.y() >= at.artY() - FLOOR_BELOW && m.y() <= at.artY() + FLOOR_ABOVE;
    }

    /** {@code m} is a creeper that is fusing or within {@link #CREEPER_CLOSE} of the bot's feet: never hidden. */
    static boolean creeperClose(WorldView.Mob m, Look at) {
        if (!m.type().equals("creeper")) return false;
        return m.fusing() || m.distSq(at.x(), at.y(), at.z()) <= CREEPER_CLOSE * CREEPER_CLOSE;
    }

    /**
     * {@code m} is out of reach now, by the rules a record ends by: neither {@link #withinReach} nor a
     * {@link #creeperClose} creeper (the fight's time-out sets these aside, BuildEngine).
     */
    static boolean outOfReach(WorldView.Mob m, Look at) {
        return !withinReach(m, at) && !creeperClose(m, at);
    }

    /**
     * One look at {@code m}: true while it stays set aside. Its record ends here (one log line) when its time is up or
     * when it came within reach after it was seen out of reach.
     */
    boolean hides(WorldView.Mob m, Look at) {
        Rec r = records.get(m.id());
        if (r == null) return false;
        if (at.tick() - r.since >= at.limitTicks()) {
            records.remove(m.id());
            log.accept(describe(m) + " was set aside as out of reach " + seconds(at.tick() - r.since)
                + " s ago: the time limit is up (build.fightSetAsideMinutes), it counts again");
            return false;
        }
        if (creeperClose(m, at)) {
            records.remove(m.id());
            log.accept(describe(m) + ", set aside as out of reach " + seconds(at.tick() - r.since)
                + " s ago, is " + (m.fusing() ? "fusing" : "within " + (int) CREEPER_CLOSE + " blocks of the bot")
                + ": it counts again");
            return false;
        }
        if (withinReach(m, at)) {
            if (!r.armed) return true; // set aside while within reach (behind a fence): it must leave and come back
            records.remove(m.id());
            log.accept(describe(m) + ", set aside as out of reach " + seconds(at.tick() - r.since)
                + " s ago, came within reach of the bot: it counts again");
            return false;
        }
        r.armed = true;
        return true;
    }

    /**
     * The mobs of {@code mobs} (those the client sees) that are not set aside, one {@link #hides} look each. Records
     * whose time is up go too, seen or not.
     */
    List<WorldView.Mob> without(List<WorldView.Mob> mobs, Look at) {
        if (records.isEmpty()) return mobs;
        List<WorldView.Mob> out = new ArrayList<>(mobs.size());
        for (WorldView.Mob m : mobs) if (!hides(m, at)) out.add(m);
        records.values().removeIf(r -> at.tick() - r.since >= at.limitTicks());
        return out;
    }

    /**
     * A listed mob ({@code attackerId}) hit the bot: its record ends, and so does every hostile one (any type not
     * {@link #PASSIVE}): a hostile mob out of reach may be the one shooting. The passive ones that did not hit stay.
     * Returns a log text of what ended ("" when nothing did).
     */
    String hit(int attackerId) {
        int own = 0, hostile = 0, passiveLeft = 0;
        for (Iterator<Rec> it = records.values().iterator(); it.hasNext(); ) {
            Rec r = it.next();
            if (r.id == attackerId) {
                own++;
                it.remove();
            } else if (!passive(r.type)) {
                hostile++;
                it.remove();
            } else {
                passiveLeft++;
            }
        }
        if (own + hostile == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(own + hostile).append(own + hostile == 1 ? " mob" : " mobs").append(" set aside as out of reach count"
            + " again (").append(own > 0 ? "the attacker" : "").append(own > 0 && hostile > 0 ? " and " : "")
            .append(hostile > 0 ? hostile + " hostile" : "").append(')');
        if (passiveLeft > 0) sb.append("; ").append(passiveLeft).append(" passive one").append(passiveLeft == 1 ? "" : "s")
            .append(" that did not hit stay").append(passiveLeft == 1 ? "s" : "").append(" set aside");
        return sb.toString();
    }

    /**
     * A look found {@code m} set aside in the fight area: true when its "no fight for it" line is due (at most one per
     * {@link #LOG_TICKS} per mob); the line's time is noted.
     */
    boolean skipLogDue(WorldView.Mob m, long tick) {
        Rec r = records.get(m.id());
        if (r == null || tick - r.logged < LOG_TICKS) return false;
        r.logged = tick;
        return true;
    }

    /** Ticks since {@code m} was set aside, or -1. */
    long age(WorldView.Mob m, long tick) {
        Rec r = records.get(m.id());
        return r == null ? -1 : tick - r.since;
    }

    /** "wandering_trader #412 at 12.5,181.0,-3.5" for log lines. */
    static String describe(WorldView.Mob m) {
        return String.format(Locale.ROOT, "%s #%d at %.1f,%.1f,%.1f", m.type(), m.id(), m.x(), m.y(), m.z());
    }

    /** Ticks as whole seconds of game time. */
    static long seconds(long ticks) {
        return Math.max(0, ticks) / 20;
    }
}
