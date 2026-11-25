package io.github.digitalisp.mapart.discord;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.discord.AttachmentFilter.Candidate;
import io.github.digitalisp.mapart.discord.AttachmentFilter.Selection;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentFilterTest {
    @Test
    void picksNbtAndZipAndIgnoresTheRest() {
        Selection s = AttachmentFilter.select(List.of(
            new Candidate(0, "screenshot.png", 500_000),
            new Candidate(1, "art_0_0.NBT", 40_000),
            new Candidate(2, "tiles.zip", 900_000)), 25 * 1024 * 1024);
        assertEquals(List.of(1, 2), s.accepted().stream().map(Candidate::index).toList());
        assertEquals(List.of("screenshot.png"), s.ignored());
        assertTrue(s.errors().isEmpty());
        assertTrue(s.isSubmission());
    }

    @Test
    void messageWithoutMapFilesIsNotASubmission() {
        Selection s = AttachmentFilter.select(List.of(new Candidate(0, "cat.jpg", 10)), 100);
        assertFalse(s.isSubmission());
        assertEquals(List.of("cat.jpg"), s.ignored());
        assertFalse(AttachmentFilter.select(List.of(), 100).isSubmission());
    }

    @Test
    void oversizedFileFailsFastWithoutDownloading() {
        Selection s = AttachmentFilter.select(List.of(
            new Candidate(0, "small.nbt", 10),
            new Candidate(1, "huge.zip", 30L * 1024 * 1024)), 25 * 1024 * 1024);
        assertTrue(s.accepted().isEmpty());
        assertEquals(List.of("`huge.zip` is 30.0 MiB; the limit is 25.0 MiB."), s.errors());
        assertTrue(s.isSubmission());
    }

    @Test
    void totalSizeIsLimitedToo() {
        Selection s = AttachmentFilter.select(List.of(
            new Candidate(0, "a.nbt", 600), new Candidate(1, "b.nbt", 600)), 1000);
        assertTrue(s.accepted().isEmpty());
        assertEquals(1, s.errors().size());
        assertTrue(s.errors().get(0).startsWith("The files total 1.2 KiB"), s.errors().get(0));
    }

    @Test
    void tooManyFiles() {
        List<Candidate> c = new ArrayList<>();
        for (int i = 0; i < 11; i++) c.add(new Candidate(i, "t_" + i + "_0.nbt", 1));
        Selection s = AttachmentFilter.select(c, 1000);
        assertTrue(s.accepted().isEmpty());
        assertTrue(s.errors().get(0).startsWith("Too many files (11)"));
    }

    @Test
    void boundedReadStopsAtTheLimit() throws IOException {
        assertArrayEquals(new byte[]{1, 2, 3}, AttachmentFilter.readBounded(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        assertThrows(IOException.class, () -> AttachmentFilter.readBounded(new ByteArrayInputStream(new byte[4]), 3));
        assertEquals(0, AttachmentFilter.readBounded(new ByteArrayInputStream(new byte[0]), 3).length);
    }

    @Test
    void sizes() {
        assertEquals("512 B", AttachmentFilter.size(512));
        assertEquals("1.5 KiB", AttachmentFilter.size(1536));
        assertEquals("25.0 MiB", AttachmentFilter.size(25L * 1024 * 1024));
    }
}
