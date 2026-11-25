package io.github.digitalisp.mapart.store;

/**
 * What the engine was doing, persisted at every phase transition so it can resume after a
 * disconnect, an 8-hour session kick, a restart, or a player taking control.
 * Progress inside a phase is NOT stored — it is re-derived from the world/inventory on resume.
 * CONTRACT (lead-owned).
 */
public class EngineCheckpoint {
    public String jobId;          // null = idle
    public int tileX;
    public int tileZ;
    public String platformId;
    /** {@code io.github.digitalisp.mapart.engine.Phase} name. */
    public String phase = "IDLE";
    public long phaseSince;       // epoch millis
    public int clearAttempts;
    public int repairPasses;
    public int fillAttempts;
    /** True once the locked map for this tile is in the output chest (tile done). */
    public boolean stored;
    /** Phase to return to on resume while {@code phase} is BLOCKED; null otherwise. Older checkpoints encoded it as a "[PHASE] " note prefix. */
    public String blockedFrom;
    public String note = "";
    /** Clock time (epoch millis) of the last server restart the engine saw; 0 = none on record. */
    public long lastServerRestartAt;
}
