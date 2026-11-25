package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

/**
 * Thread-safe control surface of the build engine, used by commands (terminal / in-game / Discord).
 * Implementations hand requests to the client tick thread and return immediately, except
 * {@link #emergencyStop}, which acts in the calling thread. CONTRACT (lead-owned).
 */
public interface EngineControl {
    /**
     * Soft pause: persist {@code paused=true} now; cancel the current task and release inputs at the start of the next
     * tick, so the action in flight finishes first (typically within one tick).
     */
    void pause(String reason);

    /**
     * Kill switch, done in the calling thread without waiting for a tick (works while bot ticks are stopped): stop
     * Baritone, cancel the current task, give the suppressed modules back, persist {@code paused=true} with the reason
     * "SAFETY STOP: " + {@code reason} (only {@link #resume()} clears it) and alert once. May wait for a running tick
     * to end (implementations synchronize with the tick).
     */
    void emergencyStop(String reason);

    /** Clear pause and BLOCKED state; re-enter the checkpointed phase. */
    void resume();

    /** Snapshot for status displays. */
    EngineStatus status();

    /** Re-scan all material containers at the next opportunity (between tasks). */
    void requestStockScan();

    /**
     * A job was added/cancelled/reordered, or tiles with nothing placed were re-planned (re-substitution); re-evaluate
     * the queue, and read those tiles again, at the next opportunity.
     */
    void queueChanged();

    /**
     * The map counter button ({@code site.mapCounter}) the engine found in {@code site.mapCounterColumn} during this run,
     * for {@code mapart site}; null until it was found (the first stored map), or when {@code mapCounter.pos} is set.
     */
    default @Nullable Pos mapCounterButton() {
        return null;
    }

    /**
     * The elytra legs' rocket chest as the engine sees it now, for {@code mapart site} ({@code build.elytraLegs}):
     * listed or found by its sign, and the signs in view; "" when not known.
     */
    default String rocketChestReport() {
        return "";
    }

    /**
     * Per item, the most one unfinished map of {@code jobs} needs (whole map, every block): what the chests must hold,
     * since each map's blocks come back after its clear. A color-suppression job counts as the one map it is: the
     * sum of its passes still to verify. Any thread (reads the tile files, keeps them). Null when the
     * engine cannot tell (callers fall back to the jobs' summed materials).
     */
    default java.util.@Nullable Map<String, Integer> largestMapNeeds(java.util.List<io.github.digitalisp.mapart.model.Job> jobs) {
        return null;
    }

    /**
     * @param etaSeconds          seconds until the blocks still to place are placed at the current rate (a
     *                            colour-suppression map: all its passes, plus the ride when the platform rides);
     *                            -1 = not known, or the engine is not printing
     * @param rideStartedAt       colour-suppression ride: when the launch button was pressed (epoch ms) while the bot
     *                            rides; 0 otherwise
     * @param rideExpectedSeconds how long that ride is expected to take from the launch to the brake
     *                            ({@code ride.expectedMinutes}, or the platform's last completed ride); 0 = not riding
     */
    record EngineStatus(boolean enabled, boolean paused, String pauseReason, Phase phase, String jobId,
                        String jobName, int tileX, int tileZ, String platformId, int placed, int total,
                        String note, long phaseSince, long etaSeconds, long rideStartedAt, long rideExpectedSeconds) {
        /** A snapshot without an ETA or a ride. */
        public EngineStatus(boolean enabled, boolean paused, String pauseReason, Phase phase, String jobId,
                            String jobName, int tileX, int tileZ, String platformId, int placed, int total,
                            String note, long phaseSince) {
            this(enabled, paused, pauseReason, phase, jobId, jobName, tileX, tileZ, platformId, placed, total, note,
                phaseSince, -1, 0, 0);
        }
    }
}
