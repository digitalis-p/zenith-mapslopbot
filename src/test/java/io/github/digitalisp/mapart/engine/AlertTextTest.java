package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Job;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The plain alert texts (owner, 2026-10-29: the live "No progress" alert was "literally incomprehensible"). */
class AlertTextTest {
    private static final String WHITE = "minecraft:white_carpet";

    @Test
    void stacksAreCeilOf64ForCountsOf64AndOver() {
        assertEquals("40", AlertText.stacks(40));
        assertEquals("63", AlertText.stacks(63));
        assertEquals("1 stack", AlertText.stacks(64));
        assertEquals("about 2 stacks", AlertText.stacks(65));
        assertEquals("2 stacks", AlertText.stacks(128));
        assertEquals("about 13 stacks", AlertText.stacks(801));
    }

    @Test
    void materialNamesArePlain() {
        assertEquals("8 experience bottle stay in the inventory", AlertText.plain("8 minecraft:experience_bottle stay in the inventory"));
        assertEquals("Out of white carpet", AlertText.outOf(Map.of(WHITE, 801)));
        assertEquals("Out of white carpet and black wool", AlertText.outOf(Map.of(WHITE, 801, "minecraft:black_wool", 3)));
        assertEquals("Out of white carpet and 2 more materials",
            AlertText.outOf(Map.of(WHITE, 801, "minecraft:black_wool", 3, "minecraft:red_wool", 2)));
    }

    /** The live case of 2026-10-29: the cause in the title, the action in the first sentence, no phase name, no id first. */
    @Test
    void outOfMaterialsLeadsWithWhatToPutWhere() {
        AlertText.Alert a = AlertText.outOfMaterials("Snowy Angel tile 6,1", "soulsand", Map.of(WHITE, 801), Map.of(WHITE, 0),
            Set.of(), 1503, 2304, 10, "");
        assertEquals("Out of white carpet", a.title());
        assertEquals("Snowy Angel tile 6,1 needs 801 more white carpet; none left in near or far storage. Put about 13"
            + " stacks in a chest on the soulsand platform. Everything else on the tile is built (1,503 of 2,304). Nothing"
            + " to build until then.\nThe bot looks in the chests again every 10 min.", a.body());
        assertFalse(a.body().contains("STOCK_CHECK") || a.body().contains("minecraft:"), a.body());
    }

    @Test
    void outOfMaterialsSaysWhenTheBaseNeverHeldTheItemOrHoldsTooFew() {
        assertTrue(AlertText.outOfMaterials("Solo", "moss", Map.of(WHITE, 30), Map.of(), Set.of(WHITE), 10, 40, 10, "")
            .body().startsWith("Solo needs 30 more white carpet; no chest at this base holds it. Put 30 in a chest on the"
                + " moss platform. Everything else on the map is built (10 of 40)."));
        assertTrue(AlertText.outOfMaterials("Solo", "moss", Map.of(WHITE, 30), Map.of(WHITE, 5), Set.of(), 10, 40, 10, "")
            .body().contains("; too few left in storage for a trip."));
        String two = AlertText.outOfMaterials("Solo", "moss", Map.of(WHITE, 130, "minecraft:black_wool", 3), Map.of(),
            Set.of(), 10, 40, 10, "").body();
        assertTrue(two.contains("Put about 3 stacks of white carpet and 3 black wool in a chest"), two);
        assertTrue(two.contains("\nIt needs:\n- white carpet: 130 more needed (about 3 stacks)\n- black wool: 3 more needed"), two);
    }

    @Test
    void whereNamesTheMapThenTheIdAndLanes() {
        Job j = new Job();
        j.id = "j0002";
        j.name = "ellen";
        for (int i = 0; i < 2; i++) {
            Job.TileState t = new Job.TileState();
            t.x = i;
            t.z = 0;
            j.tiles.add(t);
        }
        assertEquals("ellen tile 1,0 (#j0002, lanes 2-3)", AlertText.where(j, j.tiles.get(1), "lanes 2-3"));
        assertEquals("ellen (#j0002)", AlertText.where(j, null, ""));
        assertEquals("", AlertText.where(null, null, ""));
    }
}
