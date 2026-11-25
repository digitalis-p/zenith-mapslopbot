package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Where the bot retreats from mobs it cannot fight: back along its own track ({@link TrackBack#retreatCell}). */
class RetreatCellTest {
    /** A walkway walked from x = 0 to x = 40 at z = 0: newest first. */
    private static List<Pos> walkway() {
        List<Pos> t = new ArrayList<>();
        for (int x = 40; x >= 0; x--) t.add(new Pos(x, 174, 0));
        return t;
    }

    @Test
    void theNewestTrackCellFarEnoughFromThePillagersIsTaken() {
        List<double[]> pillagers = List.of(new double[]{46.5, 0.5}, new double[]{47.5, 2.5});
        Pos to = TrackBack.retreatCell(walkway(), new Pos(40, 174, 0), pillagers);
        assertEquals(new Pos(22, 174, 0), to, "the first cell 24 blocks from the nearest one: the shortest walk back");
    }

    @Test
    void aShortTrackGivesItsFarthestCell() {
        List<Pos> track = walkway().subList(0, 11); // x 40 .. 30
        assertEquals(new Pos(30, 174, 0), TrackBack.retreatCell(track, new Pos(40, 174, 0),
            List.<double[]>of(new double[]{46.5, 0.5})));
    }

    @Test
    void noGainOrNoThreatsNoRetreat() {
        Pos at = new Pos(40, 174, 0);
        assertNull(TrackBack.retreatCell(walkway(), at, List.of()));
        // mobs every 5 blocks along the whole track: no cell of it is 4 blocks further from them
        List<double[]> everywhere = new ArrayList<>();
        for (int x = 0; x <= 40; x += 5) everywhere.add(new double[]{x + 0.5, 0.5});
        assertNull(TrackBack.retreatCell(walkway(), at, everywhere));
    }
}
