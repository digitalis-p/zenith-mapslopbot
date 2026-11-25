package io.github.digitalisp.mapart.model;

/**
 * Build counters of one tile for its "tile summary" log line at "Map done" (tools/metrics/map_metrics.py reads it).
 * Persisted with the job ({@link Job.TileState#metrics}), so a restart or reconnect keeps counting; only what happened
 * after the last job save before a crash is lost. Mutated on the engine's tick thread only.
 */
public class TileMetrics {
    /** BUILD: cells a lane run turned from air into the planned block (cut-short runs included, re-runs never twice). */
    public int placed;
    /** The same in VERIFY's repair lanes. */
    public int repaired;
    /** Of {@link #placed} + {@link #repaired}: placed while the lane run stood at a stand spot (the rest walking). */
    public int fromStandSpots;
    /** Lane runs started (BUILD and VERIFY), the ones cut short by a stop, back-off or restart included. */
    public int laneRuns;
    /** RESTOCK rounds that ended in BUILD. */
    public int restocks;
    /** Of {@link #restocks}: rounds that withdrew items (a load). */
    public int loads;
    /** Items those loads withdrew. */
    public long withdrawn;
    /** Small server setbacks the teleport guard judged while this tile was the engine's. */
    public int setbacks;
    /**
     * True when counting began with the tile (nothing of it was placed yet); false when it began mid-tile (a plugin
     * build from before these counters resumed it): the counts then miss what came before.
     */
    public boolean fromStart;
}
