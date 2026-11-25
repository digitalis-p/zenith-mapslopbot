package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What answers a click, packet side: block updates ({@link BlockEchoes}) and exact acks ({@link BlockAcks}). */
class BlockAnswersTest {
    @AfterEach
    void reset() {
        BlockEchoes.reset();
        BlockAcks.reset();
    }

    @Test
    void blockUpdateAfterTheMarkAnswersTheClick() {
        BlockEchoes.record(1, 64, 2); // an older update of the cell
        long mark = BlockEchoes.mark();
        assertFalse(BlockEchoes.since(new Pos(1, 64, 2), mark), "before the click");
        BlockEchoes.record(1, 63, 2); // the clicked support
        BlockEchoes.record(1, 64, 2); // the cell next to its top face
        assertTrue(BlockEchoes.since(new Pos(1, 64, 2), mark));
        assertFalse(BlockEchoes.since(new Pos(1, 64, 3), mark), "another cell");
        BlockEchoes.reset();
        assertFalse(BlockEchoes.since(new Pos(1, 64, 2), mark), "forgotten on a new connection");
        assertTrue(BlockEchoes.mark() >= mark + 2, "marks never go back");
    }

    @Test
    void oldestPositionsAreForgottenFirst() {
        long mark = BlockEchoes.mark();
        BlockEchoes.record(0, 64, 0);
        for (int i = 1; i < BlockEchoes.KEPT; i++) BlockEchoes.record(i, 64, 0);
        BlockEchoes.record(0, 64, 0); // updated again: newest
        BlockEchoes.record(0, 64, -1);
        assertTrue(BlockEchoes.since(new Pos(0, 64, 0), mark));
        assertFalse(BlockEchoes.since(new Pos(1, 64, 0), mark), "the oldest one went");
        assertTrue(BlockEchoes.since(new Pos(2, 64, 0), mark));
    }

    @Test
    void exactAcksAreKeptApartFromTheLatest() {
        BlockAcks.record(7);
        BlockAcks.record(9); // covers 8, which was never acked itself
        assertEquals(9, BlockAcks.latest());
        assertTrue(BlockAcks.ackedExactly(7));
        assertFalse(BlockAcks.ackedExactly(8));
        assertTrue(BlockAcks.ackedExactly(9));
        BlockAcks.reset();
        assertEquals(-1, BlockAcks.latest());
        assertFalse(BlockAcks.ackedExactly(9), "a new connection's counter");
    }

    @Test
    void exactAcksKeepTheLastOnes() {
        for (int i = 0; i <= BlockAcks.KEPT; i++) BlockAcks.record(i);
        assertFalse(BlockAcks.ackedExactly(0));
        assertTrue(BlockAcks.ackedExactly(1));
        assertTrue(BlockAcks.ackedExactly(BlockAcks.KEPT));
    }
}
