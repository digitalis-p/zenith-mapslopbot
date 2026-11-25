package io.github.digitalisp.mapart.engine;

/** Engine constants that are not (yet) in {@code MapArtConfig}. Ticks are client ticks (20/s). */
final class EngineTuning {
    private EngineTuning() { }

    /** A failing step is retried this many times (with backoff) before the engine goes BLOCKED. */
    static final int MAX_TASK_RETRIES = 5;
    static final int BACKOFF_BASE_TICKS = 20;
    static final int BACKOFF_MAX_TICKS = 1200;
    /** Upper bound of decisions per tick (guards against phase ping-pong without any task). */
    static final int MAX_STEPS_PER_TICK = 16;
    /** Generic poll interval (waiting for chunks, the piston clear, toggles). */
    static final int POLL_TICKS = 20;
    static final int IDLE_POLL_TICKS = 200;
    /** Wait after the bot ticks (re)start so chunks and inventory arrive before deciding anything. */
    static final int START_DELAY_TICKS = 40;
    /**
     * Blocks around where a disconnect dropped a stack that the reconnect looks for it in ({@code
     * build.recoverDisconnectDrops}): vanilla throws a dropped stack about 2 blocks ahead of the eyes.
     */
    static final double DROP_RADIUS = 6.0;
    /** How long to wait for chunks after walking somewhere. */
    static final int LOAD_WAIT_TICKS = 600;
    /** Ticks to let a lever/button state reach the chunk cache before reading it again. */
    static final int TOGGLE_SETTLE_TICKS = 20;
    static final int TOGGLE_TRIES = 3;

    /** A material container whose scan failed this often is skipped until a requested scan (session only). */
    static final int SCAN_FAILURES_SKIP = 2;
    /** A shortage re-look skips containers scanned less than this long ago. */
    static final long RESCAN_MIN_AGE_MS = 60_000L;
    /** INSPECT: resume a partial build when at least this share of the non-air blocks match the tile. */
    static final double RESUME_MATCH_RATIO = 0.90;
    /** INSPECT: alert on the 2nd foreign-block finding for the same tile, BLOCKED on the 3rd. */
    static final int FOREIGN_ALERT_AT = 2;
    static final int FOREIGN_BLOCK_AT = 3;
    /** Rounds with no withdrawal and no placement before a forced stock check / BLOCKED. */
    static final int NO_PROGRESS_RESCAN = 2;
    static final int NO_PROGRESS_BLOCK = 4;
    /** NUKE: break rounds before the step counts as failed. */
    static final int NUKE_ROUNDS = 3;

    /** Food is topped up to {@code build.foodToKeep} items when fewer than this are carried. */
    static final int FOOD_MIN = 8;
    /** The engine also eats when health is at or below this (and the hunger bar is not full). */
    static final float EAT_HEALTH = 10f;
    /** After a failed eat, or when out of food, the next try waits this long. */
    static final int EAT_RETRY_TICKS = 600;
    /** After a failed repair of the offhand's item, the next try waits this long. */
    static final int OFFHAND_RETRY_TICKS = 200;
    /** Repairs of the offhand's item failed in a row before the engine stops trying (and alerts). */
    static final int OFFHAND_MAX_FAILURES = 3;
    /**
     * Hunger at or below this: the running task is stopped at its next safe point and the bot eats (below 6 no
     * sprinting and no regeneration, at 0 starving). With no food, or eating failing, it is BLOCKED.
     */
    static final int FOOD_CRITICAL = 6;
    static final int EAT_FAILURES_BLOCK = 3;
    /** After a failed eat at critical hunger or low health, the next try waits only this long. */
    static final int EAT_RETRY_URGENT_TICKS = 60;
    /**
     * Health at or below this (a fall, a mob, starving): the engine stops where it stands, alerts, and eats or waits
     * until health is back to {@link #HEALTH_RESUME}.
     */
    static final float HEALTH_STOP = 8f;
    static final float HEALTH_RESUME = 14f;
    /** Health at or below this while out of food, or still dropping while the bot stands still → BLOCKED. */
    static final float HEALTH_BLOCK = 6f;
    /** Natural regeneration needs the hunger bar at this or more. */
    static final int REGEN_FOOD = 18;
    /** Poll interval while standing still for health. */
    static final int VITALS_POLL_TICKS = 10;
    /** At most one "Low health" alert in this time (every episode is logged). */
    static final long LOW_HEALTH_ALERT_MS = 10 * 60_000L;
    static final double BOOTS_MIN_DURABILITY = 0.95;
    static final int XP_THROW_CHUNK = 8;
    /** Mending gives up after this many trips' worth of bottles (boots without Mending never reach 95 %). */
    static final int MAX_MEND_TRIPS = 3;

    /** Tool mending: most bottles planned for one tool (a netherite tool from the floor to 95 % needs ~137). */
    static final int MEND_MAX_BOTTLES = 160;
    /** Bottles thrown per mend task; the tool is checked again after each. */
    static final int MEND_CHUNK = 32;
    /**
     * Mending during the clear: bottles per mend task once the art layer read clear (the carts' run-on, a few seconds
     * left): the run-on's end waits for at most this many throws (about 0.4 s each).
     */
    static final int CLEAR_RUNON_MEND_CHUNK = 8;
    /** Mends of one tool id in a row that failed or gained nothing before it is treated as unmendable. */
    static final int MEND_TROUBLE_LIMIT = 3;
    /** Wait after a failed mend task before the next try. */
    static final int MEND_RETRY_TICKS = 100;
    /**
     * NUKE: empty slots kept beyond what a fetch needs (XP bottles, a tool), for the drops the walk off the tile picks up
     * (local 10x run: one free slot at the lane end was full of drops at the XP chest).
     */
    static final int NUKE_PICKUP_SLOTS = 4;
    /** After the XP chests came up short, or every tool chest was tried for a kind: wait before trying again. */
    static final int TOOL_RETRY_TICKS = 6000;
    /** Break stops at the tool safety floor in a row without a successful mend or swap before BLOCKED. */
    static final int TOOL_STOPS_BLOCK = 6;

    /** FILL_MAP: re-read the eyeblossom states this often while waiting for the world to flip them (3 s). */
    static final int BLOSSOM_POLL_TICKS = 60;
    /** FILL_MAP waiting for blocks above the art to go: survey interval, and the hold that puts the map in hand. */
    static final int COVER_POLL_TICKS = 60;
    static final int COVER_HOLD_TICKS = 20;
    /** Covered again this soon after the art read clear (someone still busy above): the same wait goes on. */
    static final long COVER_RESUME_MS = 5 * 60_000L;
    /** COUNT: the map counter button still pressed by someone else is waited for this long, polled this often. */
    static final long COUNTER_POP_WAIT_MS = 10_000L;
    static final int COUNTER_POLL_TICKS = 5;
    /** COUNT jam check: the blocks around the counter are compared this long (real time) after its button popped. */
    static final long COUNTER_SETTLE_MS = 1_000L;
    /**
     * COUNT during a jam episode: maps in a row whose counter differs from its last good state with no piston stuck out
     * before that state is taken as the new normal (the owner may have rebuilt something) and the owed counts are pressed.
     */
    static final int COUNTER_ADOPT_VISITS = 3;
    static final int CENTER_RANGE = 2;
    /** WAIT_CLEAR item count: the volume reaches this far below the art layer (floor - 2) and above it (art + 3). */
    static final int COLLECTION_BELOW = 3;
    static final int COLLECTION_ABOVE = 3;
    /** CHECK_MAP: holds of a map without data before the recovery (a fresh map, then BLOCKED asking for a reconnect). */
    static final int MISSING_DATA_HOLDS = 2;
    /** Fresh maps per tile when a map's data never arrives. */
    static final int MAX_FRESH_MAPS = 1;
    static final int RENDER_SCALE = 2;
    static final int NAME_MAX = 50;
    /** Sliding window for the placement rate behind the ETA. */
    static final long RATE_WINDOW_MS = 15 * 60_000L;
    /** Keep-alive: the head turn in degrees, alternately right and left. */
    static final float KEEP_ALIVE_TURN_DEGREES = 15f;
    /** A keep-alive that could not be sent (another module owned the input) is tried again after this long. */
    static final long KEEP_ALIVE_RETRY_MS = 5_000L;

    static final String MAP = "minecraft:map";
    static final String FILLED_MAP = "minecraft:filled_map";
    static final String PANE = "minecraft:glass_pane";
    static final String XP_BOTTLE = "minecraft:experience_bottle";
    static final String ANVIL = "minecraft:anvil";
}
