package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import io.github.digitalisp.mapart.model.Pos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link StandSpotMemory}: good spots across restarts, failed cells for the session. */
class StandSpotMemoryTest {
    private static final Pos CHEST = new Pos(10, 64, 9);
    private static final Pos OTHER = new Pos(11, 64, 9);
    private static final Pos A = new Pos(13, 64, 11);
    private static final Pos B = new Pos(14, 64, 11);

    @Test
    void aGoodSpotIsKeptAcrossARestart(@TempDir Path dir) {
        Path f = dir.resolve("stand-spots.txt");
        List<String> log = new ArrayList<>();
        StandSpotMemory m = new StandSpotMemory(f, log::add);
        m.opened(CHEST, A);
        m.opened(OTHER, B);
        assertTrue(Files.isRegularFile(f));
        StandSpotMemory again = new StandSpotMemory(f, log::add);
        assertEquals(A, again.good(CHEST));
        assertEquals(B, again.good(OTHER));
        assertTrue(log.isEmpty(), log.toString());
    }

    @Test
    void aFailedCellIsRememberedForTheSessionOnly(@TempDir Path dir) {
        Path f = dir.resolve("stand-spots.txt");
        StandSpotMemory m = new StandSpotMemory(f, s -> { });
        m.missed(CHEST, A);
        assertEquals(Set.of(A), m.failed(CHEST));
        assertEquals(Set.of(), m.failed(OTHER));
        assertEquals(Set.of(), new StandSpotMemory(f, s -> { }).failed(CHEST));
    }

    @Test
    void aCellTheChestOpensFromIsGoodAgainAndAGoodCellThatMissesIsForgotten(@TempDir Path dir) {
        StandSpotMemory m = new StandSpotMemory(dir.resolve("stand-spots.txt"), s -> { });
        m.missed(CHEST, A);
        m.opened(CHEST, A);
        assertEquals(Set.of(), m.failed(CHEST));
        assertEquals(A, m.good(CHEST));
        m.missed(CHEST, A);
        assertNull(m.good(CHEST));
        assertNull(new StandSpotMemory(dir.resolve("stand-spots.txt"), s -> { }).good(CHEST));
    }

    @Test
    void failedCellsPerChestAreBounded() {
        StandSpotMemory m = new StandSpotMemory(null, s -> { });
        for (int i = 0; i < 40; i++) m.missed(CHEST, new Pos(i, 64, 11));
        assertEquals(StandSpotMemory.MAX_FAILED_PER_CONTAINER, m.failed(CHEST).size());
        assertTrue(m.failed(CHEST).contains(new Pos(39, 64, 11)), "the newest kept");
    }

    @Test
    void aDamagedFileIsReadAsFarAsItGoes(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("stand-spots.txt");
        Files.writeString(f, "# comment\n10 64 9 13 64 11\nnot a line\n1 2 3\n11 64 9 x 64 11\n");
        StandSpotMemory m = new StandSpotMemory(f, s -> { });
        assertEquals(A, m.good(CHEST));
        assertNull(m.good(OTHER));
        assertEquals(1, m.goodCount());
    }

    @Test
    void anUnwritableFileLeavesTheMemoryForTheSessionAndWarnsOnce(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "a file where a directory should be");
        List<String> log = new ArrayList<>();
        StandSpotMemory m = new StandSpotMemory(blocker.resolve("stand-spots.txt"), log::add);
        m.opened(CHEST, A);
        m.opened(OTHER, B);
        assertEquals(A, m.good(CHEST));
        assertEquals(1, log.size(), log.toString());
    }
}
