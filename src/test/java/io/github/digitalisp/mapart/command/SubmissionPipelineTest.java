package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.NamedFile;
import io.github.digitalisp.mapart.model.ParsedSubmission;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;
import io.github.digitalisp.mapart.model.TileCoord;
import io.github.digitalisp.mapart.model.ValidationResult;
import io.github.digitalisp.mapart.plan.SubmissionService;
import io.github.digitalisp.mapart.render.ColorResolver;
import io.github.digitalisp.mapart.render.Renderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubmissionPipelineTest {
    static final List<NamedFile> FILES = List.of(new NamedFile("art.nbt", new byte[]{1}));

    Fakes.Store store;
    Fakes.Engine engine;
    FakeSubmissions submissions;
    FakeRenderer renderer;
    SubmissionPipeline pipeline;

    static final class FakeSubmissions implements SubmissionService {
        ParsedSubmission parsed = Fakes.parsed("sizedreisen", 2, 1, 100);
        ValidationResult validation = new ValidationResult(List.of(), List.of("1.21.5 export"),
            Map.of("minecraft:white_carpet", 200), List.of("moss"));
        SubmissionException parseError;
        List<NamedFile> seen;

        @Override
        public ParsedSubmission parse(List<NamedFile> files) throws SubmissionException {
            seen = files;
            if (parseError != null) throw parseError;
            return parsed;
        }

        @Override
        public ValidationResult validate(ParsedSubmission s, BlockCatalog catalog, MapArtConfig.SiteCfg site) {
            return validation;
        }
    }

    static final class FakeRenderer implements Renderer {
        int scale = -1;
        boolean fail;

        @Override
        public byte[] preview(Map<TileCoord, TileArt> tiles, int tilesX, int tilesZ, BlockCatalog catalog,
                              ColorResolver colors, int scale) {
            if (fail) throw new IllegalStateException("boom");
            this.scale = scale;
            return new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        }

        @Override
        public byte[] mapData(byte[] packedColors, ColorResolver colors, int scale) {
            return new byte[0];
        }

        @Override
        public byte[] mapDiff(byte[] actualPacked, byte[] expectedPacked, ColorResolver colors, int scale) {
            return new byte[0];
        }
    }

    @BeforeEach
    void setUp() {
        store = new Fakes.Store();
        engine = new Fakes.Engine();
        submissions = new FakeSubmissions();
        renderer = new FakeRenderer();
        pipeline = new SubmissionPipeline(submissions, new Fakes.Catalog(), new MapArtConfig(), store, renderer,
            (id, b) -> 0xFF000000 | id, () -> engine, NOPLogger.NOP_LOGGER);
    }

    private SubmissionPipeline.Outcome submit(String name) {
        return pipeline.submit(new SubmissionPipeline.Request(FILES, name, "42", "jared2013", "100", "200"));
    }

    @Test
    void acceptedCreatesJobAndRendersPreview() {
        store.stock.update(new Pos(0, 64, 0), Map.of("minecraft:white_carpet", 150), 0, 27, 5);
        store.add(Fakes.job("j0099", "ahead", io.github.digitalisp.mapart.model.JobStatus.QUEUED, 1, 1, Map.of()));
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit("  **Pine** Tree <@1>  "));
        Job j = a.job();
        assertEquals("Pine Tree", j.name);
        assertEquals("42", j.submitterId);
        assertEquals("100", j.discordChannelId);
        assertEquals("200", j.discordMessageId);
        assertEquals(1, store.saves);
        assertEquals(2, a.queuePosition());
        assertEquals(200, a.totalBlocks());
        assertTrue(a.stockScanned());
        assertEquals(List.of(new QueueOps.MaterialRow("minecraft:white_carpet", 200, 150)), a.materials());
        assertEquals(List.of("1.21.5 export"), a.warnings());
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'}, a.previewPng());
        assertEquals(4, renderer.scale); // 2x1 tiles: 256 px * 4 = 1024
        assertEquals(List.of("queueChanged"), engine.calls);
        assertSame(FILES, submissions.seen);
    }

    @Test
    void nameFallsBackToBaseName() {
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class,
            pipeline.submit(new SubmissionPipeline.Request(FILES, null, "terminal", "terminal", null, null)));
        assertEquals("sizedreisen", a.job().name);
        assertNull(a.job().discordChannelId);
        assertEquals(0, store.saves);
        assertFalse(a.stockScanned());
    }

    @Test
    void parseErrorIsRejected() {
        submissions.parseError = new SubmissionService.SubmissionException("Not flat: top blocks at Y 1 and 2");
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit("x"));
        assertEquals(List.of("Not flat: top blocks at Y 1 and 2"), r.reasons());
        assertTrue(store.jobs.isEmpty());
        assertTrue(engine.calls.isEmpty());
    }

    @Test
    void validationErrorsAreRejectedWithWarnings() {
        submissions.validation = new ValidationResult(List.of("minecraft:test_block does not exist in 1.21.4"),
            List.of("note"), Map.of(), List.of());
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit("x"));
        assertEquals(List.of("minecraft:test_block does not exist in 1.21.4"), r.reasons());
        assertEquals(List.of("note"), r.warnings());
        assertTrue(store.jobs.isEmpty());
    }

    @Test
    void noCompatiblePlatformIsRejected() {
        submissions.validation = new ValidationResult(List.of(), List.of(), Map.of(), List.of());
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit("x"));
        assertTrue(r.reasons().get(0).contains("No enabled platform"));
    }

    @Test
    void storeFailureIsRejected() {
        store.failCreate = true;
        var r = assertInstanceOf(SubmissionPipeline.Rejected.class, submit("x"));
        assertTrue(r.reasons().get(0).contains("disk full"));
    }

    @Test
    void previewFailureStillAccepts() {
        renderer.fail = true;
        var a = assertInstanceOf(SubmissionPipeline.Accepted.class, submit("x"));
        assertNull(a.previewPng());
        assertTrue(a.warnings().contains("Preview could not be rendered."));
    }

    @Test
    void previewScaleKeepsLongestEdgeAt1024() {
        assertEquals(8, SubmissionPipeline.previewScale(1, 1));
        assertEquals(4, SubmissionPipeline.previewScale(2, 1));
        assertEquals(2, SubmissionPipeline.previewScale(3, 4));
        assertEquals(1, SubmissionPipeline.previewScale(8, 8));
        assertEquals(1, SubmissionPipeline.previewScale(12, 1));
        assertEquals(8, SubmissionPipeline.previewScale(0, 0));
    }

    @Test
    void readLocalDirectoryFiltersAndSorts(@TempDir Path dir) throws IOException {
        Files.write(dir.resolve("b_1_0.nbt"), new byte[]{2});
        Files.write(dir.resolve("a_0_0.NBT"), new byte[]{1});
        Files.write(dir.resolve("notes.txt"), new byte[]{9});
        Files.createDirectory(dir.resolve("sub.nbt"));
        List<NamedFile> files = SubmissionPipeline.readLocal(dir, 1000);
        assertEquals(List.of("a_0_0.NBT", "b_1_0.nbt"), files.stream().map(NamedFile::name).toList());
        assertArrayEquals(new byte[]{1}, files.get(0).data());

        assertEquals(1, SubmissionPipeline.readLocal(dir.resolve("b_1_0.nbt"), 1000).size());
        assertThrows(IOException.class, () -> SubmissionPipeline.readLocal(dir.resolve("notes.txt"), 1000));
        assertThrows(IOException.class, () -> SubmissionPipeline.readLocal(dir.resolve("missing.nbt"), 1000));
        assertThrows(IOException.class, () -> SubmissionPipeline.readLocal(dir, 1));
        assertThrows(IOException.class, () -> SubmissionPipeline.readLocal(dir.resolve("sub.nbt"), 1000));
    }
}
