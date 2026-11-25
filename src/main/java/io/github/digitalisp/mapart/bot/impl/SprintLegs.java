package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig;

/**
 * Sprint on Baritone walks ({@code build.sprintRestockLegs}, {@code build.sprintWalks}). Pure: which walks may sprint
 * ({@link #rule}), the rule a
 * walk sprints by ({@link #allowed}) and the watch that keeps clicks away from sprinting ({@link Watch}).
 *
 * <p>Who sprints ({@link Walk}): with {@code build.sprintRestockLegs} the Baritone walks of a restock (the engine marks
 * them, {@code BotActions.restockLegs}): to the drop point, to and between the sorter's stand cells, and back to the
 * start of the next lane. With {@code build.sprintWalks} every plain walk, the bot doing nothing else on the way,
 * unless the engine's phase forbids it (the clear running: {@code BotActions.sprintWalks}). Never: a walk that places
 * or breaks on the way, and a hop down a ledge. {@link Walker} turns Baritone's {@code allowSprint} on through
 * {@link PathingGuard} while {@link #allowed} holds, and off otherwise; every other walk runs with it off.
 * ZenithProxy's Bot sprints only while its input asks for it (Baritone's, never the plugin's own keys), and never at
 * food 6 or below, in water, against a wall, or with the back or sneak key (vanilla's rules, Grim SprintA, SprintG,
 * SprintE, SprintB).
 *
 * <p>Grim, the rest (2.3.73): MultiActionsC/D flag a container click or close while the server thinks the player
 * sprints; PacketOrderF a use, place or dig in a tick with a sprint toggle before it; PacketOrderE a hotbar change in
 * such a tick; SprintC sprinting while eating. The plugin's own packets of a tick leave before the Bot's sprint toggle
 * of that tick, so only a toggle of an earlier tick could matter; the
 * {@link Watch} keeps every click, open, placement, hotbar move and eating {@code build.sprintQuietTicks} ticks away
 * from the last sprinting tick, and a walk does not sprint near its goal or in its first ticks.
 */
public final class SprintLegs {
    private SprintLegs() { }

    /**
     * When a restock leg may sprint: hunger at least {@code minFood}, the feet {@code stopDistance} or more from the
     * goal, the walk at least {@code startTicks} old.
     */
    public record Rule(int minFood, double stopDistance, int startTicks, boolean once, int restartTicks,
                       boolean lateStart) {
        public Rule(int minFood, double stopDistance, int startTicks) {
            this(minFood, stopDistance, startTicks, false, 0);
        }

        public Rule(int minFood, double stopDistance, int startTicks, boolean once) {
            this(minFood, stopDistance, startTicks, once, 0);
        }

        public Rule(int minFood, double stopDistance, int startTicks, boolean once, int restartTicks) {
            this(minFood, stopDistance, startTicks, once, restartTicks, false);
        }

        /** The rule of {@code build}, or null when {@code build.sprintRestockLegs} is off. */
        public static @Nullable Rule of(MapArtConfig.BuildCfg build) {
            if (!build.sprintRestockLegs) return null;
            return from(build);
        }

        /** The rule of {@code build}'s settings, whatever the switches say. */
        static Rule from(MapArtConfig.BuildCfg build) {
            return new Rule(Math.max(7, build.sprintMinFood), Math.max(0, build.sprintStopDistance),
                Math.max(0, build.sprintStartTicks), build.sprintOnce, Math.max(0, build.sprintRestartTicks),
                build.sprintLateStart);
        }
    }

    /**
     * What a walk of the plugin is, for {@link #rule}. {@code plain}: the bot does nothing else on the way (no
     * placement, break, click, container, eating or hotbar move until it arrives; what comes after waits for the quiet
     * window). {@code restockLeg}: a restock leg when the engine is in a restock.
     */
    public enum Walk {
        /** To a lane's start, or back to it after a park (the first one after a restock is a restock leg). */
        LANE_START(true, true),
        /** To a lane's stand spot, before its clicks. */
        STAND_SPOT(true, false),
        /** Along a lane's break-out route, between the walls it breaks standing. */
        LANE_BREAK_OUT(true, false),
        /** To a container's stand spot: scans, withdrawals, deposits, the cartography table, the anvil, the tool chest. */
        CONTAINER(true, true),
        /** To the drop point. */
        DROP_POINT(true, true),
        /** A break task's walks between break positions (the nuke's transit, approach and detours; it never breaks then). */
        BREAK_TRANSIT(true, false),
        /** To a button or lever: the clear, the map counter, the carts. */
        BUTTON(true, false),
        /** To a single placement (the anvil). */
        PLACE(true, false),
        /** To the map station (creating or holding the map). */
        MAP_STATION(true, false),
        /** To the cart rail (colour suppression). */
        CART(true, false),
        /** {@code walkTo}: between platforms and the storage, off the art layer, retreats, the clear stand. */
        WALK(true, false),
        /** A setback or harm escape to open floor. */
        ESCAPE(true, false),
        /** A fight's chase (ZenithProxy's KillAura hits whatever comes into reach, as before). */
        CHASE(true, false),
        /**
         * A fight's retreat from a creeper ({@code combat.creeperHitAndRun}): sprints on its own, whatever
         * {@code build.sprintWalks} says, at once and to the goal's last block (the fuse is 30 ticks); food as for any sprint.
         */
        RETREAT(true, false),
        /** A hop down a ledge: sprinting would carry the bot past its landing. Never. */
        HOP_DOWN(false, false),
        /** The lane's own walk while it places (its own keys, no Baritone). Never. */
        LANE_WALK(false, false),
        /** The nuke's sweep while it breaks (its own keys, no Baritone). Never. */
        NUKE_SWEEP(false, false);

        private final boolean plain;
        private final boolean restockLeg;

        Walk(boolean plain, boolean restockLeg) {
            this.plain = plain;
            this.restockLeg = restockLeg;
        }

        public boolean plain() {
            return plain;
        }

        public boolean restockLeg() {
            return restockLeg;
        }
    }

    /**
     * The sprint rule of a walk ({@link #allowed}), or null: it never sprints. {@code restock}: the engine is in a
     * restock ({@code BotActions.restockLegs}); {@code phaseAllows}: the engine's phase lets plain walks sprint
     * ({@code BotActions.sprintWalks}: not while the clear runs). A restock leg sprints with
     * {@code build.sprintRestockLegs}; every plain walk with {@code build.sprintWalks} while the phase allows it.
     */
    public static @Nullable Rule rule(Walk walk, MapArtConfig.BuildCfg build, boolean restock, boolean phaseAllows) {
        if (!walk.plain()) return null;
        if (walk == Walk.RETREAT) return new Rule(Math.max(7, build.sprintMinFood), 0, 0);
        if (restock && walk.restockLeg() && build.sprintRestockLegs) return Rule.from(build);
        // lane to lane: set back at the lane start about every 13th time when sprinted (live 2026-10-23)
        if (walk == Walk.LANE_START && !restock && !build.sprintWalksLaneStart) return null;
        if (build.sprintWalks && phaseAllows) return Rule.from(build);
        return null;
    }

    /** Anything sprints at all ({@code build.sprintRestockLegs} or {@code build.sprintWalks}): the sync runs. */
    public static boolean any(MapArtConfig.BuildCfg build) {
        return build.sprintRestockLegs || build.sprintWalks;
    }

    /**
     * An airplace may go out while Baritone walks (the in-reach greedy, {@code LaneRun.Io.clickWhileWalking}): the walk
     * is ours, no container is open, and the bot has not sprinted for the quiet window ({@code build.sprintQuietTicks},
     * {@link Watch#quiet}), like every other click.
     */
    public static boolean clickWhileWalking(boolean walking, boolean containerOpen, Watch watch, boolean sprintingNow) {
        return walking && !containerOpen && watch.quiet(sprintingNow);
    }

    /**
     * Whether a restock walk may sprint this tick. {@code ticksIntoWalk}: ticks since the walk (its goal list) began;
     * {@code food}: the hunger bar; {@code distanceLeft}: horizontal blocks from the feet to the goal (NaN: unknown, no
     * sprint).
     */
    public static boolean allowed(@Nullable Rule rule, long ticksIntoWalk, int food, double distanceLeft) {
        if (rule == null) return false;
        if (food < rule.minFood() || food <= 6) return false;
        if (ticksIntoWalk < rule.startTicks()) return false;
        return !Double.isNaN(distanceLeft) && distanceLeft >= rule.stopDistance();
    }

    /**
     * Horizontal distance from the feet (x, z) to a {@link GoalNearXZ}'s nearest goal cell centre: the distance to its
     * centre cell's centre less its range, at least 0.
     */
    public static double distanceLeft(GoalNearXZ g, double x, double z) {
        return Math.max(0, Math.hypot(g.x() + 0.5 - x, g.z() + 0.5 - z) - g.range());
    }

    /**
     * Ticks since the bot last sprinted, counted on every client tick ({@link #tick}, before the engine and its task):
     * {@link #quiet} is what a click, open, placement, hotbar move or eating asks first. One per session.
     */
    /**
     * {@link MapArtConfig.BuildCfg#sprintOnce}: one sprint per walk, started from rest. {@link #allow} each tick of the
     * walk with what the rule allows, the bot's sprint state and the feet block; {@link #reset} when a walk begins.
     */
    public static final class Once {
        private boolean started;
        private boolean ended;
        private boolean late;
        private boolean restarted;
        private boolean lateStarted;
        private long endedAt;
        private long startBlock;
        private boolean fresh = true;

        public void reset() {
            started = ended = late = restarted = lateStarted = false;
            fresh = true;
        }

        /** {@code wanted}: the rule allows sprint now; {@code sprinting}: the bot sprints (its last tick). */
        public boolean allow(boolean wanted, boolean sprinting, int blockX, int blockZ) {
            return allow(wanted, sprinting, blockX, blockZ, 0, 0);
        }

        /**
         * {@link #allow(boolean, boolean, int, int)} with {@code build.sprintRestartTicks} ({@code restartTicks}, 0 =
         * never): a sprint that Baritone ended (a turn, a step, a path segment's end) may start again once the bot has
         * not sprinted for that many ticks ({@code tick}: any steadily rising tick count), so the server's answer to the
         * stop and the attribute updates of the blocks since have arrived. The restart is not "late" for the first block.
         */
        public boolean allow(boolean wanted, boolean sprinting, int blockX, int blockZ, long tick, int restartTicks) {
            return allow(wanted, sprinting, blockX, blockZ, tick, restartTicks, false);
        }

        /**
         * {@link #allow(boolean, boolean, int, int, long, int)} with {@code build.sprintLateStart} ({@code lateStart},
         * only with {@code restartTicks} above 0): a walk whose feet left their first block before its sprint started
         * is "late" as before, but counts as if a sprint had ended there, so the restart rule lets it start once the
         * bot has not sprinted for {@code restartTicks} ticks, as it lets an ended sprint start again.
         */
        public boolean allow(boolean wanted, boolean sprinting, int blockX, int blockZ, long tick, int restartTicks,
                             boolean lateStart) {
            long block = ((long) blockX << 32) ^ (blockZ & 0xffffffffL);
            if (fresh) {
                startBlock = block;
                fresh = false;
            }
            if (sprinting) started = true;
            else if (started && !ended) {
                ended = true;
                endedAt = tick;
            }
            if (!started && !restarted && block != startBlock && !late) {
                late = true;
                if (lateStart && restartTicks > 0) {
                    lateStarted = true;
                    started = true;
                    ended = true;
                    endedAt = tick;
                }
            }
            if (ended && !sprinting && restartTicks > 0 && tick - endedAt >= restartTicks) {
                ended = false;
                started = false;
                restarted = true;
            }
            return wanted && !ended && (!late || (lateStarted && restarted));
        }

        public boolean ended() {
            return ended;
        }

        public boolean late() {
            return late;
        }

        /** {@link #late} and started after all ({@code build.sprintLateStart}): the restart rule let it. */
        public boolean lateStarted() {
            return lateStarted && restarted;
        }
    }

    public static final class Watch {
        /** The session's watch: ticked by the module on every ClientBotTick. */
        public static final Watch SESSION = new Watch();

        private long tick;
        private long lastSprint = Long.MIN_VALUE / 2;
        private int quietTicks = 5;
        private long sprintTicks;

        /** One client tick; {@code sprinting}: the Bot's sprint state now (what its last tick sent). */
        public synchronized void tick(boolean sprinting) {
            tick++;
            if (sprinting) {
                lastSprint = tick;
                sprintTicks++;
            }
        }

        /** The quiet window {@link #quiet()} asks for ({@code build.sprintQuietTicks}). */
        public synchronized void quietTicks(int ticks) {
            this.quietTicks = Math.max(0, ticks);
        }

        /**
         * Nothing sprinted for the quiet window: the last tick that saw the bot sprinting is at least
         * {@code build.sprintQuietTicks} ticks back, and {@code sprintingNow} is false.
         */
        public synchronized boolean quiet(boolean sprintingNow) {
            return !sprintingNow && tick - lastSprint >= quietTicks;
        }

        /** {@link #quiet(boolean)} with the given window. */
        public synchronized boolean quiet(int ticks, boolean sprintingNow) {
            return !sprintingNow && tick - lastSprint >= ticks;
        }

        /** Client ticks seen since the start (steadily rising). */
        public synchronized long ticks() {
            return tick;
        }

        /** Ticks seen sprinting since the start (status and tests). */
        public synchronized long sprintTicks() {
            return sprintTicks;
        }
    }
}
