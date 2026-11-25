package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.TileArt;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carry-over specs: a tail names the tile built next on its platform
 * ({@link TileUnitSpec.Next}), inline or by reference.
 */
class CarryOverSpecTest {
    private static final PlatformGeometry SOUL = new PlatformGeometry(3000, 2000, 64);

    private static TileArt art(String block, int rows) {
        TileArt a = new TileArt();
        for (int z = 0; z < rows; z++) for (int x = 0; x < 128; x++) a.set(x, z, BlockSpec.of(block));
        return a;
    }

    private static TileUnitSpec tile(String map, int i, TileArt art) {
        Job job = Fakes.job(map, "Map " + map, JobStatus.QUEUED, 3, 1, Map.of());
        return TileUnitSpec.of(job, job.tiles.get(i), art, "soul", SOUL);
    }

    @Test
    void aTailsNextTileRoundTripsInlineAndByReference() throws IOException {
        TileArt a = art("minecraft:lapis_block", 9), b = art("minecraft:lapis_block", 12);
        TileUnitSpec next = tile("j0400", 1, b);
        TileUnitSpec tail = tile("j0400", 0, a).asTail().withNext(TileUnitSpec.Next.of(next));
        JsonObject json = tail.toJson();
        assertEquals("j0400", json.getAsJsonObject("next").get("map").getAsString());
        TileUnitSpec back = TileUnitSpec.fromJson(json);
        assertEquals("j0400/1,0", back.next().tileKey());
        assertEquals(b.nonEmptyCount(), back.next().art().nonEmptyCount());
        assertEquals(next.artSha256(), back.next().artSha256());

        TileUnitSpec ref = tail.withNext(tail.next().byReference(1));
        JsonObject refJson = ref.toJson().getAsJsonObject("next");
        assertNull(refJson.get("art"));
        assertEquals(1, refJson.getAsJsonObject("artRef").get("parts").getAsInt());
        TileUnitSpec.Next r = TileUnitSpec.fromJson(ref.toJson()).next();
        assertNull(r.art());
        assertEquals(1, r.artParts());
    }

    @Test
    void aNextTileThatDoesNotReadIsLeftOutNeverFailingTheTail() throws IOException {
        TileArt a = art("minecraft:lapis_block", 9);
        TileUnitSpec tail = tile("j0401", 0, a).asTail().withNext(TileUnitSpec.Next.of(tile("j0401", 1, a)));
        JsonObject json = tail.toJson();
        json.getAsJsonObject("next").addProperty("artSha256", "0".repeat(64)); // not the art's
        TileUnitSpec back = TileUnitSpec.fromJson(json);
        assertNull(back.next(), "an art that differs from its SHA-256 is no next tile");
        assertTrue(back.tail());
        json.getAsJsonObject("next").remove("tile");
        assertNull(TileUnitSpec.fromJson(json).next());
        // a band never carries one, even if the JSON says so
        JsonObject band = tile("j0401", 0, a).withBand(new TileUnitSpec.Band(0, 1, 0, 9, 0, 127)).toJson();
        band.add("next", tail.toJson().getAsJsonObject("next"));
        assertNull(TileUnitSpec.fromJson(band).next());
    }
}
