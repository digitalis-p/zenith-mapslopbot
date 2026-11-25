package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lane bands: the band and tail specs and keys. Synthetic coordinates only.
 */
class LaneBandsTest {
    private static final PlatformGeometry SOUL = new PlatformGeometry(3000, 2000, 64);

    /** Columns cycle three carpets in rows 0..rows-1. */
    private static TileArt art(int rows) {
        TileArt a = new TileArt();
        String[] ids = {"minecraft:white_carpet", "minecraft:red_carpet", "minecraft:black_carpet"};
        for (int z = 0; z < rows; z++) for (int x = 0; x < 128; x++) a.set(x, z, BlockSpec.of(ids[x % 3]));
        return a;
    }

    // ---- spec and keys ----

    @Test
    void bandAndTailSpecsRoundTripWithTheirKeys() throws IOException {
        Job j = Fakes.job("j0050", "Bands", JobStatus.QUEUED, 2, 1, Map.of());
        TileArt art = art(10);
        TileUnitSpec tile = TileUnitSpec.of(j, j.tiles.get(1), art, "soul", SOUL);
        TileUnitSpec.Band b = new TileUnitSpec.Band(2, 3, 18, 35, 0, 127);
        TileUnitSpec band = tile.withBand(b);
        assertEquals("j0050/1,0/lanes:2-3", band.key());
        assertEquals("j0050/1,0", band.tileKey());
        assertEquals(18 * 10, band.total(), "the band's own cells");
        assertEquals(Map.of("minecraft:white_carpet", 60, "minecraft:red_carpet", 60, "minecraft:black_carpet", 60),
            band.materials());
        JsonObject o = band.toJson();
        JsonObject bj = o.getAsJsonObject("band");
        assertEquals("[2,3]", bj.get("lanes").toString());
        assertEquals("{\"x\":[18,35],\"z\":[0,127]}", bj.get("cells").toString());
        assertFalse(o.has("tail"));
        TileUnitSpec back = TileUnitSpec.fromJson(JsonParser.parseString(o.toString()));
        assertEquals(b, back.band());
        assertFalse(back.tail());
        assertEquals(band.key(), back.key());
        assertEquals(180, back.total());
        assertNotNull(back.art(), "the art stays the whole tile's");
        assertEquals(art.nonEmptyCount(), back.art().nonEmptyCount());

        TileUnitSpec tail = tile.asTail();
        assertEquals("j0050/1,0/tail", tail.key());
        assertEquals(tile.total(), tail.total());
        JsonObject t = tail.toJson();
        assertTrue(t.get("tail").getAsBoolean());
        assertFalse(t.has("band"));
        TileUnitSpec tback = TileUnitSpec.fromJson(t);
        assertTrue(tback.tail());
        assertNull(tback.band());
        assertEquals("j0050/1,0/tail", tback.key());
        // by reference keeps the kind
        assertEquals(b, band.byReference(3).band());
        assertTrue(tail.byReference(3).tail());
        assertEquals("j0050/1,0", tile.key(), "a whole tile's key as before");
        assertFalse(tile.toJson().has("band") || tile.toJson().has("tail"), "a whole tile's JSON as before");

        JsonObject both = band.toJson();
        both.addProperty("tail", true);
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(both));
        JsonObject outside = band.toJson();
        outside.getAsJsonObject("band").getAsJsonObject("cells").add("x", JsonParser.parseString("[120,128]"));
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(outside));
        JsonObject odd = band.toJson();
        odd.getAsJsonObject("band").add("lanes", JsonParser.parseString("[3]"));
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(odd));
    }

    @Test
    void unitKeysOfBandsAndTails() {
        assertEquals("j0016/3,1/lanes:0-1", Unit.bandKey("j0016", 3, 1, 0, 1));
        assertEquals("j0016/3,1/tail", Unit.tailKey("j0016", 3, 1));
        assertEquals("j0016/3,1", Unit.tileKeyOf("j0016/3,1/lanes:14-14"));
        assertEquals("j0016/3,1", Unit.tileKeyOf("j0016/3,1/tail"));
        assertEquals("j0016/3,1", Unit.tileKeyOf("j0016/3,1"));
        assertEquals(new TileCoord(3, 1), Unit.tileOf("j0016/3,1/lanes:4-5"));
        assertEquals(new TileCoord(3, 1), Unit.tileOf("j0016/3,1/tail"));
        assertEquals("j0016", Unit.mapOf("j0016/3,1/tail"));
        assertEquals("a/b", Unit.mapOf("a/b/0,2/lanes:0-1"));
        assertNull(Unit.tileOf("platform:soul"));
        assertTrue(Unit.bandKey("j0016", 99, 99, 14, 14).length() <= 200, "a coordinator key (MAX_KEY 200)");

        Job j = Fakes.job("j0051", "K", JobStatus.QUEUED, 1, 1, Map.of());
        TileUnitSpec tile = TileUnitSpec.of(j, j.tiles.getFirst(), art(2), "soul", SOUL);
        Unit band = new Unit("j0051/0,0/lanes:0-1", "j0051", 3, 1, tile.withBand(new TileUnitSpec.Band(0, 1, 0, 17, 0, 127)), null);
        Unit tail = new Unit("j0051/0,0/tail", "j0051", 4, 1, tile.asTail(), null);
        Unit whole = new Unit("j0051/0,0", "j0051", 5, 1, tile, null);
        assertTrue(band.band() && !band.tail());
        assertTrue(tail.tail() && !tail.band());
        assertFalse(whole.band() || whole.tail());
        assertTrue(band.resumable(), "a band: the tile's other bands are other bots' work");
        assertTrue(tail.resumable(), "a tail: the bands were built first");
        assertFalse(whole.resumable(), "a first grant of a whole tile, as before");
    }

    @Test
    void claimKeysOfTheNewSpots() {
        assertEquals("station:counter", Claim.COUNTER);
        assertEquals("sorter:soul", Claim.sorter("soul"));
        assertEquals("drop:1,2,3", Claim.drop(new io.github.digitalisp.mapart.model.Pos(1, 2, 3)));
    }
}
