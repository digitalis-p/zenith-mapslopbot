package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a restart may reuse of a stored discovery after the material regions changed (build.seedDiscoveryPartial). */
class DiscoverySeedTest {
    // a sorter's front rows and its upper rows (made-up coordinates, tile-like layout)
    private static final RegionCfg FRONT = region(0, 74, -6, 125, 75, -5);
    private static final RegionCfg UPPER = region(0, 77, -6, 7, 79, -6);
    private static final RegionCfg FAR = region(256, 74, -9, 317, 78, -7);
    private static final Pos FRONT_A = new Pos(0, 74, -5);
    private static final Pos FRONT_B = new Pos(2, 75, -6);
    private static final Pos FAR_A = new Pos(256, 76, -8);

    private static RegionCfg region(int x0, int y0, int z0, int x1, int y1, int z1) {
        RegionCfg r = new RegionCfg();
        r.min = new Pos(x0, y0, z0);
        r.max = new Pos(x1, y1, z1);
        return r;
    }

    private static String signature(RegionCfg... regions) {
        StringBuilder sb = new StringBuilder();
        for (RegionCfg r : regions) sb.append(DiscoverySeed.key(r)).append(';');
        return sb.toString();
    }

    @Test
    void anAddedRegionIsTheOnlyOneToDiscover() {
        DiscoverySeed.Result r = DiscoverySeed.of(List.of(FAR_A, FRONT_A, FRONT_B), signature(FAR, FRONT),
            List.of(FAR, FRONT, UPPER));
        assertNotNull(r);
        assertEquals(List.of(FAR_A, FRONT_A, FRONT_B), r.known());
        assertEquals(List.of(UPPER), r.pending());
    }

    @Test
    void aResizedRegionIsDiscoveredAgainTheOthersAreKept() {
        RegionCfg taller = region(0, 74, -6, 125, 77, -5);
        DiscoverySeed.Result r = DiscoverySeed.of(List.of(FAR_A, FRONT_A, FRONT_B), signature(FAR, FRONT),
            List.of(FAR, taller));
        assertNotNull(r);
        assertEquals(List.of(FAR_A), r.known(), "the resized region's containers come from its new discovery");
        assertEquals(List.of(taller), r.pending());
    }

    @Test
    void aRemovedRegionsContainersAreDropped() {
        DiscoverySeed.Result r = DiscoverySeed.of(List.of(FAR_A, FRONT_A, FRONT_B), signature(FAR, FRONT),
            List.of(FRONT, UPPER));
        assertNotNull(r);
        assertEquals(List.of(FRONT_A, FRONT_B), r.known());
        assertEquals(List.of(UPPER), r.pending());
    }

    @Test
    void nothingToReuse() {
        assertNull(DiscoverySeed.of(List.of(FRONT_A), signature(FAR, FRONT), List.of(FAR, FRONT)),
            "the same regions: the plain seed's case");
        assertNull(DiscoverySeed.of(List.of(FRONT_A), signature(FRONT), List.of(UPPER)), "no region the same");
        assertNull(DiscoverySeed.of(null, signature(FRONT), List.of(FRONT, UPPER)), "no stored discovery");
        assertNull(DiscoverySeed.of(List.of(), signature(FRONT), List.of(FRONT, UPPER)));
        assertNull(DiscoverySeed.of(List.of(FRONT_A), null, List.of(FRONT, UPPER)), "no stored signature");
        assertNull(DiscoverySeed.of(List.of(FAR_A), signature(FAR, FRONT), List.of(FRONT, UPPER)),
            "no stored container in a region still the same");
    }

    @Test
    void signatureParts() {
        assertEquals(Set.of(DiscoverySeed.key(FAR), DiscoverySeed.key(FRONT)), DiscoverySeed.parts(signature(FAR, FRONT)));
        assertEquals(Set.of(), DiscoverySeed.parts(null));
        assertEquals(Set.of(), DiscoverySeed.parts(""));
    }
}
