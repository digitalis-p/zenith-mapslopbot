package io.github.digitalisp.mapart.plan;

import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.model.Lane;
import io.github.digitalisp.mapart.model.Placement;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.function.Predicate;

/**
 * Turns a tile into an ordered list of walking lanes (serpentine N→S sweeps across the tile).
 * Pure, deterministic, no I/O. Called again after every restock/repair with a fresh {@code done}
 * predicate, so progress is always derived from the world, never from memory. CONTRACT (lead-owned).
 */
public interface BuildPlanner {
    /**
     * @param done placements already correct in the world (skipped)
     * @return lanes with at least one remaining placement, in execution order
     */
    List<Lane> plan(TileArt art, PlatformGeometry geo, BlockCatalog catalog, int laneHalfWidth,
                    Predicate<Placement> done);
}
