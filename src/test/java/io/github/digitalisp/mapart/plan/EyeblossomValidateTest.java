package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.catalog.ZenithBlockCatalog;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;

import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All eyeblossoms flip together with the time of day, so a map wanting open and closed ones can never match: such
 * an upload is rejected per map. One kind per map is fine, even both kinds on different maps of one job.
 */
class EyeblossomValidateTest {
    private static final String OPEN = "minecraft:open_eyeblossom";
    private static final String CLOSED = "minecraft:closed_eyeblossom";

    private static TileArt tile(String... rows) {
        TileArt t = new TileArt();
        for (int z = 0; z < rows.length; z++)
            for (int x = 0; x < 128; x++) t.set(x, z, BlockSpec.of(rows[z]));
        return t;
    }

    private static ValidationResult validate(TileArt... tiles) {
        TreeMap<TileCoord, TileArt> m = new TreeMap<>();
        for (int i = 0; i < tiles.length; i++) m.put(new TileCoord(i, 0), tiles[i]);
        ParsedSubmission sub = new ParsedSubmission("t", tiles.length, 1, DefaultSubmissionService.DATA_VERSION_1_21_4, m, List.of());
        return new DefaultSubmissionService().validate(sub, new ZenithBlockCatalog(), SameColourSwapMossTest.mossSite());
    }

    @Test
    void aMapWithOpenAndClosedEyeblossomsIsRejected() {
        ValidationResult v = validate(tile(OPEN, "minecraft:white_carpet"), tile(OPEN, CLOSED), tile(CLOSED));
        assertEquals(1, v.errors().size(), v.errors().toString());
        String e = v.errors().getFirst();
        assertTrue(e.startsWith("Map 1,0 uses both open and closed eyeblossoms."), e);
        assertTrue(e.contains("one map cannot show both"), e);
        assertTrue(e.contains("orange_carpet"), e);
    }

    @Test
    void oneKindPerMapIsAccepted() {
        ValidationResult v = validate(tile(OPEN, "minecraft:white_carpet"), tile(CLOSED, CLOSED));
        assertTrue(v.ok(), v.errors().toString());
        assertEquals(List.of("moss"), v.platformIds());
        assertEquals(128 * 3, v.materials().get(OPEN), "both kinds are one material: open_eyeblossom");
    }
}
