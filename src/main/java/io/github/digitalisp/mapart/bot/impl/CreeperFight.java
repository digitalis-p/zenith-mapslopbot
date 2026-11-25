package io.github.digitalisp.mapart.bot.impl;

/**
 * The hit and run state machine against one creeper ({@link CombatTask}; pure,
 * unit-tested). Vanilla's SwellGoal: a creeper whose target is within 3 blocks starts to swell (its fuse counts up one a
 * tick, explosion at 30) and keeps swelling until the target is more than 7 blocks away or out of sight, then the swell
 * counts down one a tick; a swelling creeper stands still. So: approach, one hit with a full attack, at once away to
 * more than {@link #safe} blocks, stand there {@code waitTicks}, again.
 *
 * <p>Each tick {@link #step} gets the distance (feet to feet), the creeper's own fuse flag when the entity data has it,
 * whether a hit with full strength is possible now (weapon held) and whether a hit was seen (the creeper's health
 * fell). The machine keeps its own estimate of the fuse ({@link #fuse}) from the same rules, so it also works when the
 * entity data shows nothing. Answers what to do this tick:
 * <ul>
 *   <li>{@code APPROACH}: walk up to the creeper (the attack stays off);</li>
 *   <li>{@code STRIKE}: stand still, the attack on: the creeper is within {@link #REACH}. After a hit is seen, or
 *       {@link #STRIKE_MAX} ticks (KillAura rotates, then attacks; the hit is assumed), it is {@code RETREAT};</li>
 *   <li>{@code RETREAT}: walk away from it, the attack off, until more than {@link #safe} blocks away;</li>
 *   <li>{@code WAIT}: stand, the attack off: the wait after a retreat, or no full attack ready yet. A creeper that
 *       walks into reach meanwhile is struck at once when a full attack is ready.</li>
 * </ul>
 * Never next to a creeper without a full attack: within {@link #REACH} and not ready, or within {@link #WATCH} with a
 * fuse running and not ready, it is {@code RETREAT} at once; so is a fuse that is {@link #FUSE_BUDGET} ticks on within
 * {@link #WATCH} (a hit and the walk out take about 17 ticks, the explosion comes at 30).
 */
final class CreeperFight {
    enum Action { APPROACH, STRIKE, RETREAT, WAIT }

    /** The bot hits from here (KillAura reaches about 3), and a creeper this close starts to swell. */
    static final double REACH = 3.0;
    /** Within this, a creeper whose fuse runs is dangerous to stand next to without a hit ready. */
    static final double WATCH = 4.0;
    /** Vanilla stops the swell beyond 7 blocks; the retreat goes to {@link #safe}, by default above that. */
    static final double STOP = 7.0;
    /** Ticks a strike waits for the hit to be seen before it is assumed (rotation, attack, the health update). */
    static final int STRIKE_MAX = 6;
    /** Vanilla's fuse: the explosion at this swell. */
    static final int FUSE = 30;
    /** A fuse further on than this is no moment to hit: it needs about 17 ticks to hit and get out. */
    static final int FUSE_BUDGET = 12;

    private final double safe;
    private final int waitTicks;
    private Action state = Action.APPROACH;
    private long lastHit = -1000;
    private long waitUntil;
    private int strikeTicks;
    private int fuse;
    private boolean swelling;
    private int hits;

    CreeperFight(double safeBlocks, int waitTicks) {
        this.safe = Math.max(STOP + 0.1, safeBlocks);
        this.waitTicks = Math.max(0, waitTicks);
    }

    /** The distance the retreat goes to. */
    double safe() {
        return safe;
    }

    /** Hits landed (seen or assumed) so far. */
    int hits() {
        return hits;
    }

    /** The estimated fuse (ticks of swell). */
    int fuse() {
        return fuse;
    }

    Action state() {
        return state;
    }

    /**
     * One tick: {@code dist} feet to feet, {@code fusing} the creeper's own swell flag (entity data), {@code holding} a
     * weapon (or the bare hand) is in the main hand and may attack, {@code cooldown} the weapon's ticks to full
     * strength ({@link Weapons#cooldownTicks}), {@code hit} the creeper's health fell since the last tick.
     */
    Action step(long now, double dist, boolean fusing, boolean holding, int cooldown, boolean hit) {
        // the creeper's swell direction: on within 3 blocks, off beyond 7, else as before; its own flag counts too
        if (dist < REACH) swelling = true;
        else if (dist > STOP) swelling = false;
        boolean on = swelling || fusing;
        fuse = on ? Math.min(FUSE, fuse + 1) : Math.max(0, fuse - 1);
        if (hit && state == Action.STRIKE) {
            lastHit = now;
            hits++;
            return state = Action.RETREAT;
        }
        boolean ready = holding && now - lastHit >= cooldown;
        if (state == Action.RETREAT) {
            if (dist <= safe) return Action.RETREAT;
            waitUntil = now + waitTicks;
            return state = Action.WAIT;
        }
        boolean close = dist <= REACH;
        boolean unsafe = !ready || fuse > FUSE_BUDGET;
        if (close && unsafe || dist <= WATCH && on && unsafe) return state = Action.RETREAT;
        if (close) {
            if (state != Action.STRIKE) strikeTicks = 0;
            state = Action.STRIKE;
            if (++strikeTicks > STRIKE_MAX) {
                lastHit = now;
                hits++;
                return state = Action.RETREAT;
            }
            return Action.STRIKE;
        }
        if (state == Action.STRIKE) state = Action.APPROACH; // it stepped out of reach
        if (state == Action.WAIT && now >= waitUntil) state = Action.APPROACH;
        if (state == Action.WAIT || !ready) return Action.WAIT;
        return state = Action.APPROACH;
    }
}
