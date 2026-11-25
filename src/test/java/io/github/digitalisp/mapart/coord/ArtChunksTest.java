package io.github.digitalisp.mapart.coord;

import com.google.gson.JsonElement;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.TileArt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Art by reference: a tile's art in kv parts, checked against its spec's SHA-256. */
class ArtChunksTest {
    private static final String[] COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink",
        "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    static TileArt noise(long seed) {
        Random r = new Random(seed);
        TileArt a = new TileArt();
        for (int z = 0; z < 128; z++)
            for (int x = 0; x < 128; x++) a.set(x, z, BlockSpec.of("minecraft:" + COLOURS[r.nextInt(16)] + "_carpet"));
        return a;
    }

    private static List<JsonElement> values(List<String> parts) {
        List<JsonElement> v = new ArrayList<>();
        for (String p : parts) v.add(ArtChunks.value(p));
        return v;
    }

    @Test
    void aNoisyTileSplitsIntoPartsAndComesBackTheSame() throws IOException {
        TileArt a = noise(7);
        String sha = TileUnitSpec.sha256(a);
        List<String> parts = ArtChunks.parts(a);
        assertTrue(parts.size() > 1 && parts.size() <= ArtChunks.MAX_PARTS, "parts: " + parts.size());
        for (String p : parts) assertTrue(p.length() <= ArtChunks.PART_CHARS);
        TileArt back = ArtChunks.assemble(values(parts), sha);
        assertEquals(TileUnitSpec.artJson(a), TileUnitSpec.artJson(back));
        assertEquals("art/" + sha + "/0", ArtChunks.key(sha.toUpperCase(), 0));
    }

    @Test
    void aWrongMissingOrForeignPartIsRefused() {
        TileArt a = noise(8);
        String sha = TileUnitSpec.sha256(a);
        List<JsonElement> v = values(ArtChunks.parts(a));
        List<JsonElement> missing = new ArrayList<>(v);
        missing.set(0, null);
        assertThrows(IOException.class, () -> ArtChunks.assemble(missing, sha));
        List<JsonElement> foreign = values(ArtChunks.parts(noise(9)));
        assertThrows(IOException.class, () -> ArtChunks.assemble(foreign, sha), "another tile's parts");
        List<JsonElement> shuffled = new ArrayList<>(v);
        java.util.Collections.reverse(shuffled);
        assertThrows(IOException.class, () -> ArtChunks.assemble(shuffled, sha));
    }
}
