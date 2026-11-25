package io.github.digitalisp.mapart.model;

import java.util.List;
import java.util.SortedMap;

/**
 * Result of parsing a submission (one or more .nbt files and/or .zip archives) into flat map tiles.
 * {@code tiles} contains every coordinate 0..tilesX-1 x 0..tilesZ-1.
 * <p>
 * A colour-suppression map ({@code suppression} non-null, docs/COLOR_SUPPRESSION.md) is one map built in passes: tile
 * (i, 0) is {@code suppression.passes().get(i)}'s art, in build order, and {@code tilesZ} is 1. Null for flat maps.
 * CONTRACT (lead-owned).
 */
public record ParsedSubmission(String baseName, int tilesX, int tilesZ, int dataVersion,
                               SortedMap<TileCoord, TileArt> tiles, List<String> warnings,
                               SuppressionArt suppression) {
    /** A flat-map submission. */
    public ParsedSubmission(String baseName, int tilesX, int tilesZ, int dataVersion,
                            SortedMap<TileCoord, TileArt> tiles, List<String> warnings) {
        this(baseName, tilesX, tilesZ, dataVersion, tiles, warnings, null);
    }

    public boolean isSuppression() {
        return suppression != null;
    }
}
