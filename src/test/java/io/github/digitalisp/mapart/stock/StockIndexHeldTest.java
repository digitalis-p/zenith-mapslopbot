package io.github.digitalisp.mapart.stock;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** {@link StockIndex.Snapshot#held}: the items a container held in earlier snapshots. */
class StockIndexHeldTest {
    private static final Pos P = new Pos(1, 2, 3);

    @Test
    void anEmptiedContainerRemembersWhatItHeld() {
        StockIndex st = new StockIndex();
        st.update(P, Map.of("minecraft:white_carpet", 64), 26, 27, 1);
        assertEquals(Set.of(), st.containers.get(P.key()).held, "nothing earlier");
        st.update(P, Map.of(), 27, 27, 2);
        assertEquals(Set.of("minecraft:white_carpet"), st.containers.get(P.key()).held);
        st.update(P, Map.of("minecraft:black_carpet", 1), 26, 27, 3);
        assertEquals(Set.of("minecraft:white_carpet"), st.containers.get(P.key()).held, "carried over");
        st.update(P, Map.of(), 27, 27, 4);
        assertEquals(Set.of("minecraft:black_carpet", "minecraft:white_carpet"), st.containers.get(P.key()).held);
    }

    @Test
    void stockFilesWithoutItStillLoad() {
        StockIndex st = new Gson().fromJson("{\"containers\":{\"1,2,3\":{\"items\":{\"minecraft:white_carpet\":5},"
            + "\"freeSlots\":1,\"totalSlots\":27,\"scannedAt\":7}}}", StockIndex.class);
        st.update(P, Map.of(), 27, 27, 8);
        assertEquals(Set.of("minecraft:white_carpet"), st.containers.get(P.key()).held);
    }
}
