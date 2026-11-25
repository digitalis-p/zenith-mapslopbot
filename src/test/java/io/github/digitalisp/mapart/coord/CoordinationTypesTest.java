package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.command.Fakes;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.TileStatus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The seam's records and their JSON shapes. */
class CoordinationTypesTest {

    private static TileArt art() {
        TileArt a = new TileArt();
        for (int x = 0; x < 128; x++) a.set(x, 0, BlockSpec.of("minecraft:white_carpet"));
        for (int x = 0; x < 64; x++) a.set(x, 5, BlockSpec.of("minecraft:red_carpet"));
        return a;
    }

    @Test
    void tileKeys() {
        assertEquals("j0002/3,1", Unit.tileKey("j0002", 3, 1));
        assertEquals(new TileCoord(3, 1), Unit.tileOf("j0002/3,1"));
        assertEquals("j0002", Unit.mapOf("j0002/3,1"));
        assertEquals("a/b", Unit.mapOf("a/b/0,2"), "the last slash splits");
        assertNull(Unit.tileOf("platform:moss"));
        assertNull(Unit.tileOf("j0002/3"));
        assertNull(Unit.tileOf("j0002/a,b"));
        assertNull(Unit.tileOf("/3,1"));
        assertNull(Unit.mapOf("chest:1,2,3"));
    }

    @Test
    void unitResumableAndSameGrant() {
        Unit first = new Unit("j1/0,0", "j1", 7, 1, null, null);
        assertFalse(first.resumable());
        assertTrue(new Unit("j1/0,0", "j1", 8, 2, null, null).resumable());
        TileProgress started = new TileProgress(10, 100, "BUILD", "moss", false, false, false, false, 0, 0, 0, 5L, null, "");
        assertTrue(new Unit("j1/0,0", "j1", 8, 1, null, started).resumable());
        assertTrue(first.sameGrant(new Unit("j1/0,0", "j1", 7, 3, null, started)));
        assertFalse(first.sameGrant(new Unit("j1/0,0", "j1", 8, 1, null, null)), "another fence");
        assertFalse(first.sameGrant(null));
    }

    @Test
    void specRoundTripsWithItsArt() throws IOException {
        Job j = Fakes.job("j0042", "Cats", JobStatus.QUEUED, 2, 1, Map.of());
        j.platformIds = new java.util.ArrayList<>(List.of("soul", "moss"));
        Job.TileState t = j.tiles.get(1);
        TileArt art = art();
        TileUnitSpec spec = TileUnitSpec.of(j, t, art, "moss", new io.github.digitalisp.mapart.model.PlatformGeometry(1000, 2000, 64));
        assertEquals("j0042/1,0", spec.key());
        assertEquals(192, spec.total());
        assertEquals(Map.of("minecraft:white_carpet", 128, "minecraft:red_carpet", 64), spec.materials());
        assertEquals(new TileUnitSpec.Box(1000, 2000, 1127, 2127), spec.box());

        JsonObject o = spec.toJson();
        // prompt 2's unit spec: {map, tile [i, j], platform, dim, x1, z1, x2, z2} (the list job's "nearest" reads the box)
        assertEquals("j0042", o.get("map").getAsString());
        assertEquals(1, o.getAsJsonArray("tile").get(0).getAsInt());
        assertEquals(0, o.getAsJsonArray("tile").get(1).getAsInt());
        assertEquals("moss", o.get("platform").getAsString());
        assertEquals("overworld", o.get("dim").getAsString());
        assertEquals(1000, o.get("x1").getAsInt());
        assertEquals(2127, o.get("z2").getAsInt());
        String json = o.toString();
        TileUnitSpec back = TileUnitSpec.fromJson(JsonParser.parseString(json));
        assertEquals(spec.map(), back.map());
        assertEquals(spec.name(), back.name());
        assertEquals(1, back.x());
        assertEquals(0, back.z());
        assertEquals(2, back.tilesX());
        assertEquals("moss", back.platform());
        assertEquals(spec.box(), back.box());
        assertEquals(spec.materials(), back.materials());
        assertEquals(spec.artSha256(), back.artSha256());
        assertNotNull(back.art());
        assertEquals(art.nonEmptyCount(), back.art().nonEmptyCount());
        assertEquals(BlockSpec.of("minecraft:red_carpet"), back.art().get(10, 5));
        assertNull(back.art().get(100, 5));
    }

    @Test
    void specRefusesATamperedArtAndAMissingTile() {
        Job j = Fakes.job("j0043", "Dogs", JobStatus.QUEUED, 1, 1, Map.of());
        JsonObject o = TileUnitSpec.of(j, j.tiles.getFirst(), art()).toJson();
        o.addProperty("artSha256", "00".repeat(32));
        IOException e = assertThrows(IOException.class, () -> TileUnitSpec.fromJson(o));
        assertTrue(e.getMessage().contains("artSha256"), e.getMessage());

        JsonObject noTile = TileUnitSpec.of(j, j.tiles.getFirst(), art()).toJson();
        noTile.remove("tile");
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(noTile));
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(JsonParser.parseString("[1]")));
    }

    /** A full tile: {@code colours} carpet colours, in runs ({@code noisy} = a fresh colour every cell). */
    private static TileArt full(int colours, boolean noisy) {
        String[] ids = new String[colours];
        for (int i = 0; i < colours; i++) ids[i] = "minecraft:c" + i + "_carpet";
        java.util.Random r = new java.util.Random(7);
        TileArt a = new TileArt();
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++) a.set(x, z, BlockSpec.of(ids[noisy ? r.nextInt(colours) : (x / 8 + z / 16) % colours]));
        return a;
    }

    @Test
    void specArtIsDeflatedAndFitsTheLimits() throws IOException {
        Job j = Fakes.job("j0045", "Big", JobStatus.QUEUED, 1, 1, Map.of());
        int smooth = TileUnitSpec.of(j, j.tiles.getFirst(), full(12, false)).toJson().toString().length();
        int noisy = TileUnitSpec.of(j, j.tiles.getFirst(), full(61, true)).toJson().toString().length();
        System.out.println("tile unit spec JSON: smooth 12 colors " + smooth + " B, noise of 61 colors " + noisy + " B");
        assertTrue(smooth < 16 * 1024, "a smooth full tile: " + smooth);
        assertTrue(noisy < 128 * 1024, "the worst case still fits a unit spec: " + noisy);
        assertTrue(noisy < 44 * 1024, "deflate never loses to the plain cells: " + noisy);
        // noise round-trips exactly
        TileArt art = full(61, true);
        TileUnitSpec back = TileUnitSpec.fromJson(JsonParser.parseString(TileUnitSpec.of(j, j.tiles.getFirst(), art).toJson().toString()));
        for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) assertEquals(art.get(x, z), back.art().get(x, z));
    }

    @Test
    void specReadsPlainCellsAndRefusesBadEncodings() throws IOException {
        Job j = Fakes.job("j0046", "Enc", JobStatus.QUEUED, 1, 1, Map.of());
        TileArt art = art();
        JsonObject o = TileUnitSpec.of(j, j.tiles.getFirst(), art).toJson();
        JsonObject a = o.getAsJsonObject("art");
        // TileCodec's plain base64 (no "enc") is read as is
        a.remove("enc");
        a.addProperty("cells", io.github.digitalisp.mapart.store.TileCodec.encode(art).cells);
        assertEquals(art.nonEmptyCount(), TileUnitSpec.fromJson(o).art().nonEmptyCount());
        a.addProperty("enc", "zstd");
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(o));
        // deflate data that inflates past one tile is refused
        a.addProperty("enc", "deflate");
        byte[] tooBig = new byte[128 * 128 * 2 + 10];
        a.addProperty("cells", TileUnitSpec.deflate(java.util.Base64.getEncoder().encodeToString(tooBig)));
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(o));
        a.addProperty("cells", "not base64 !");
        assertThrows(IOException.class, () -> TileUnitSpec.fromJson(o));
    }

    @Test
    void specMakesAOneTileLocalJob() {
        Job j = Fakes.job("j0044", "Owls", JobStatus.ACTIVE, 3, 2, Map.of());
        Job.TileState t = j.tiles.get(4); // 1,1
        TileUnitSpec spec = TileUnitSpec.of(j, t, art());
        Job local = spec.toJob();
        assertEquals("j0044", local.id);
        assertEquals("Owls", local.name);
        assertEquals(JobStatus.QUEUED, local.status);
        assertEquals(3, local.tilesX);
        assertEquals(2, local.tilesZ);
        assertEquals(1, local.tiles.size());
        assertEquals(1, local.tiles.getFirst().x);
        assertEquals(1, local.tiles.getFirst().z);
        assertEquals(TileStatus.PENDING, local.tiles.getFirst().status);
        assertEquals(192, local.tiles.getFirst().total);
        assertEquals(spec.materials(), local.materials);
    }

    @Test
    void progressRoundTrips() {
        TileProgress p = new TileProgress(1234, 16384, "BUILD", "soul", true, true, true, false, 2, 1, 3,
            1_760_000_000_000L, "Cats 1,0", "restocking");
        TileProgress back = TileProgress.fromJson(JsonParser.parseString(p.toJson().toString()));
        assertEquals(p, back);
        assertEquals(TileProgress.VERSION, p.toJson().get("v").getAsInt());
        assertTrue(p.started());
        assertFalse(new TileProgress(0, 10, "INSPECT", null, false, false, false, false, 0, 0, 0, 0, null, "").started());
        assertNull(TileProgress.fromJson(null));
        // lenient: unknown fields ignored, missing ones default
        TileProgress sparse = TileProgress.fromJson(JsonParser.parseString("{\"placed\":5,\"extra\":1}"));
        assertNotNull(sparse);
        assertEquals(5, sparse.placed());
        assertFalse(sparse.stored());
        assertNull(sparse.mapId());
        assertNull(sparse.mapPng());
        assertFalse(p.toJson().has("mapId") || p.toJson().has("mapPng"), "none without a stored map");
    }

    /** The stored map's id and render (the coordinator's "Map done" post): the fields, a round trip, kept by the withs. */
    @Test
    void theStoredMapsIdAndRenderRoundTrip() {
        TileProgress p = new TileProgress(16384, 16384, "FINISH_TILE", "soul", true, true, false, false, 0, 0, 0, 1L,
            "Cats 1,0", "", 0, null, 766, null).withMapPng("iVBORw0K");
        JsonObject o = p.toJson();
        assertEquals(766, o.get("mapId").getAsInt());
        assertEquals("iVBORw0K", o.get("mapPng").getAsString());
        assertEquals(p, TileProgress.fromJson(JsonParser.parseString(o.toString())));
        assertEquals(766, p.withMapsHolder("PhiPhi").mapId());
        assertEquals("iVBORw0K", p.withoutMaps().mapPng());
        TileProgress zero = TileProgress.fromJson(JsonParser.parseString("{\"mapId\":0}"));
        assertNotNull(zero);
        assertEquals(0, zero.mapId(), "map id 0 is an id");
    }

    @Test
    void stockEntryRoundTripsUnderItsKey() {
        Pos c = new Pos(-100, 61, 100);
        StockEntry e = new StockEntry(c, Map.of("minecraft:white_carpet", 1728), 0, 27, 42L);
        assertEquals("stock/-100,61,100", StockEntry.key(c));
        assertEquals(c, StockEntry.containerOf(StockEntry.key(c)));
        assertNull(StockEntry.containerOf("tile:0,0"));
        StockEntry back = StockEntry.fromJson(c, JsonParser.parseString(e.toJson().toString()));
        assertEquals(e, back);
        assertNull(StockEntry.fromJson(c, JsonParser.parseString("7")));
    }

    @Test
    void pendingCompletesOnce() {
        Pending.Settable<String> p = new Pending.Settable<>();
        assertFalse(p.done());
        p.complete("a");
        p.fail("late");
        assertTrue(p.done());
        assertEquals("a", p.value());
        assertNull(p.error());
        Pending<Integer> m = Pending.map(p, String::length);
        assertEquals(1, m.value());
        Pending<Integer> f = Pending.map(Pending.<String>failed("cas_failed"), String::length);
        assertTrue(f.done());
        assertEquals("cas_failed", f.error());
    }
}
