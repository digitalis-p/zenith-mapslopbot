package io.github.digitalisp.mapart.plan;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link FarLoadFill}: when a load goes far for an item, and the order a fill takes it in (made-up coordinates). */
class FarLoadFillTest {
    private static final Pos NEAR_A = new Pos(4, 64, -5);
    private static final Pos NEAR_B = new Pos(4, 65, -6);
    private static final Pos FAR = new Pos(258, 66, -8);
    private static final Set<Pos> FARS = Set.of(FAR);

    private static boolean goesFar(int want, Map<Pos, Integer> stock, int serve, int held) {
        return FarLoadFill.goesFar(want, List.of(NEAR_A, NEAR_B, FAR), FARS::contains, p -> stock.getOrDefault(p, 0),
            serve, held);
    }

    @Test
    void aLoadGoesFarOnlyWhenTheNearChestsLeaveServedCellsOpen() {
        // live [4,0] 08:27: 1,439 gray wanted, about 700 near: the rest from the moss sorter
        assertTrue(goesFar(1439, Map.of(NEAR_A, 500, NEAR_B, 196, FAR, 5000), 1439, 0));
        // the near chests cover it
        assertFalse(goesFar(1439, Map.of(NEAR_A, 1000, NEAR_B, 600, FAR, 5000), 1439, 0));
        // only the stack round-up is open: it never comes from far storage (FarStorage.farCap)
        assertFalse(goesFar(1472, Map.of(NEAR_A, 1439, FAR, 5000), 1439, 0));
        // held items count: 1,439 served, 800 carried, 700 near
        assertFalse(goesFar(704, Map.of(NEAR_A, 700, FAR, 5000), 1439, 800));
        // nothing in far storage: no far trip either way
        assertFalse(goesFar(1439, Map.of(NEAR_A, 500), 1439, 0));
    }

    @Test
    void theFillTakesFarFirstThenNear() {
        assertEquals(List.of(FAR, NEAR_A, NEAR_B), FarLoadFill.farFirst(List.of(NEAR_A, FAR, NEAR_B), FARS::contains));
        assertEquals(List.of(NEAR_A, NEAR_B), FarLoadFill.farFirst(List.of(NEAR_A, NEAR_B), FARS::contains));
    }
}
