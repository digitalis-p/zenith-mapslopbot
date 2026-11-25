package io.github.digitalisp.mapart.store;

import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.stock.StockIndex;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Durable state under {@code MapArtConfig.dataDir}: jobs (+ their tiles), queue order, engine
 * checkpoint, stock index. All writes are atomic (write temp file, then move). Thread-safe: intake
 * (Discord/JDA threads) and the engine (client tick thread) use it concurrently. Methods returning
 * {@link Job} return the live shared instance; callers mutate it and then call {@link #save(Job)}.
 * Disk errors in mutating methods surface as {@link java.io.UncheckedIOException}.
 * {@link #loadCheckpoint()} returns a copy. {@link #move} clamps the index and never moves terminal jobs.
 * Job ids are never reused. CONTRACT (lead-owned).
 *
 * <p>Layout (implementation detail, documented for operators):
 * <pre>
 * dataDir/queue.json            ordered job ids
 * dataDir/jobs/&lt;id&gt;/job.json     Job
 * dataDir/jobs/&lt;id&gt;/tile_X_Z.json compact TileArt (palette + indices)
 * dataDir/checkpoint.json       EngineCheckpoint
 * dataDir/stock.json            StockIndex
 * </pre>
 */
public interface JobStore {
    /** Creates, persists and enqueues (at the end) a new job; assigns id and tile build order. */
    Job create(ParsedSubmission submission, ValidationResult validation, String displayName,
               String submitterId, String submitterName) throws IOException;

    /** All known jobs in queue order, terminal ones included (most recent last). */
    List<Job> list();

    Optional<Job> get(String id);

    void save(Job job);

    TileArt loadTile(String jobId, TileCoord coord) throws IOException;

    /**
     * Replaces the stored art of one tile of an existing job (re-substitution of a tile with nothing placed). The
     * caller keeps {@link Job#materials} and the tile's state in step and saves the job.
     */
    default void saveTile(String jobId, TileCoord coord, TileArt art) throws IOException {
        throw new UnsupportedOperationException("this job store cannot replace tiles");
    }

    /** Marks CANCELLED (if not terminal). Returns false if unknown or already terminal. */
    boolean cancel(String id);

    /** Moves a non-terminal job to position {@code index} among non-terminal jobs (0 = next). */
    boolean move(String id, int index);

    /** First job in queue order with status ACTIVE, else QUEUED, else WAITING_MATERIALS (re-checked by the engine). */
    Optional<Job> next();

    EngineCheckpoint loadCheckpoint();

    void saveCheckpoint(EngineCheckpoint checkpoint);

    StockIndex stock();

    void saveStock();

    /**
     * A small binary file of the engine's own (the colour-suppression machine guard's reference), by plain name
     * ({@code [a-z0-9._-]+}); null when there is none. The default keeps nothing (tests).
     */
    default byte[] loadBlob(String name) throws IOException {
        return null;
    }

    /** Writes {@link #loadBlob}'s file atomically; null deletes it. The default keeps nothing. */
    default void saveBlob(String name, byte[] data) throws IOException { }
}
