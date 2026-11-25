package io.github.digitalisp.mapart.bot;

import io.github.digitalisp.mapart.model.Placement;

/** Callbacks from lane execution (client tick thread). CONTRACT (lead-owned). */
public interface PlacementListener {
    /** Server confirmed the target block state at the position. */
    void placed(Placement placement);

    /** Gave up on this placement for now (missing item, unreachable, rolled back). It will be retried by repair. */
    void skipped(Placement placement, String reason);

    /**
     * {@link #placed} for a block placed while the lane run stood at a stand spot instead of walking the lane (the
     * engine's tile counters tell the two apart). Default: {@link #placed}.
     */
    default void placedFromStandSpot(Placement placement) {
        placed(placement);
    }
}
