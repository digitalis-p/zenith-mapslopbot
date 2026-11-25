package io.github.digitalisp.mapart.engine;

/**
 * Engine phases for one tile, in normal order. Persisted by name in the EngineCheckpoint.
 * CONTRACT (lead-owned).
 */
public enum Phase {
    IDLE,
    /** Pick next job/tile and a compatible free platform. */
    SELECT,
    /** Walk to platform; decide: clear → STOCK_CHECK; matches this tile partially → resume RESTOCK/BUILD; foreign blocks → NUKE. */
    INSPECT,
    /** Refresh stale stock snapshots; compare remaining needs vs stock; short → job WAITING_MATERIALS + alert. */
    STOCK_CHECK,
    /** Tools, food, boots (soul sand), collection toggle OFF (moss). */
    GEAR,
    /** Deposit unneeded items, withdraw the next load (RestockPlanner). */
    RESTOCK,
    /** Run lanes until the load runs out → RESTOCK, or no lanes remain → VERIFY. */
    BUILD,
    /** Full scan of the tile vs plan; wrong blocks → break, missing → place; repeat ≤ maxRepairPasses. */
    VERIFY,
    /**
     * Fetch empty map + glass pane, stand at centre, create + hold the map. Waits there first, holding the map, while
     * blocks above the art hide it (the platform above in use), then while eyeblossoms show the wrong state.
     */
    FILL_MAP,
    /**
     * Compare map pixels with the expected colours; mismatch → VERIFY then hold again (≤ maxFillAttempts); mismatch
     * with blocks above the art → FILL_MAP waits (the attempt is not counted).
     */
    CHECK_MAP,
    /**
     * Colour suppression with {@code ride.enabled}, after CHECK_MAP: the machine guard's reading against its reference
     * and the stasis pearls from the viewpoints, then a cart on the start rail and the bot in it.
     */
    RIDE_BOARD,
    /**
     * Only with {@code ride.preload}, between the pre-ride checks of RIDE_BOARD and its boarding: the preload lap (into
     * the lap's own cart, its button from the seat, the lap, out again), then back to RIDE_BOARD for the main cart.
     */
    RIDE_PRELOAD,
    /** The muncher ride with the map in hand: the launch button, the push, then watching until the brake. */
    RIDE,
    /** Out of the cart in the booth, the cart broken, the map checked against the finished art; then LOCK. */
    RIDE_END,
    /** Cartography table + glass pane. */
    LOCK,
    /** Top up XP (mends boots first), replace anvil if gone, rename. */
    RENAME,
    /** Put the locked map in the output chest; tile STORED. */
    STORE,
    /**
     * Press the base's map counter button ({@code site.mapCounter}) once for the stored map, then NUKE. Only while it is
     * configured and the tile is not counted yet; never BLOCKED (a failure is one alert). Persisted per tile
     * ({@code TileState.counted}).
     */
    COUNT,
    /**
     * Colour suppression after the stored map: both layers empty and the machine guard clean from the viewpoints
     * (waits up to {@code ride.settleMinutes}); a difference is BLOCKED.
     */
    RIDE_RESET,
    /** Collection toggle ON (moss); break every non-clearable block on the art layer. */
    NUKE,
    /** Press the clear actuator(s); stay at centre if configured. */
    CLEAR,
    /** Wait until art layer is air with no fluid; timeout → retry CLEAR ≤ clearRetries → alert + pause. */
    WAIT_CLEAR,
    /** Collection toggle OFF; mark tile done; next tile or job. */
    FINISH_TILE,
    /** Needs a human; see EngineStatus.note. */
    BLOCKED
}
