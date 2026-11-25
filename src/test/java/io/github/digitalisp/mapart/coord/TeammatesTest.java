package io.github.digitalisp.mapart.coord;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.config.MapArtConfig;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who is a teammate: nobody by default; the config's static list, read live; UUIDs exact, names in any case.
 */
class TeammatesTest {
    private static final UUID MATE = UUID.fromString("0f3c2a1e-6b7d-4c8e-9f10-112233445566");
    private static final UUID OTHER = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID STRANGER = UUID.fromString("deadbeef-0000-4000-8000-000000000001");

    @Test
    void nobodyIsATeammateByDefault() {
        Teammates t = new Teammates();
        assertTrue(t.isEmpty());
        assertFalse(t.isTeammate(MATE, "Mate"));
        assertFalse(t.isTeammate(null, "Mate"));
        assertFalse(t.isTeammate(MATE, null));
        assertFalse(t.isTeammate(null, null));
        assertEquals("no teammates", t.summary());
        assertTrue(new MapArtConfig().teammates.isEmpty(), "the config list is empty by default");
        assertSame(Teammates.shared(), Teammates.shared(), "one process-wide instance");
    }

    @Test
    void aListMatchesByUuidOrByNameInAnyCase() {
        Teammates t = new Teammates();
        List<String> list = List.of(MATE.toString(), "  Builder_Two ", OTHER.toString().replace("-", ""));
        t.configured(() -> list);
        assertFalse(t.isEmpty());
        assertTrue(t.isTeammate(MATE, null), "by UUID");
        assertTrue(t.isTeammate(MATE, "renamed"), "the UUID decides whatever the name");
        assertTrue(t.isTeammate(OTHER, null), "a UUID written without dashes");
        assertTrue(t.isTeammate(null, "builder_two"), "by name, any case");
        assertTrue(t.isTeammate(STRANGER, "BUILDER_TWO"), "a listed name, whatever the UUID");
        assertFalse(t.isTeammate(STRANGER, "stranger"));
        assertFalse(t.isTeammate(STRANGER, null));
        assertFalse(t.isTeammate(null, "builder"), "names match whole");
        assertEquals("3 teammates (3 from config)", t.summary());
    }

    @Test
    void entriesThatAreNoUuidAreNames() {
        assertEquals(MATE, Teammates.uuid(MATE.toString()));
        assertEquals(MATE, Teammates.uuid(MATE.toString().replace("-", "").toUpperCase()));
        assertNull(Teammates.uuid("Notch"));
        assertNull(Teammates.uuid("0f3c2a1e-6b7d-4c8e-9f10-11223344556z"));
        assertNull(Teammates.uuid("0f3c2a1e6b7d4c8e9f1011223344556")); // 31 digits
        Teammates t = new Teammates();
        List<String> list = java.util.Arrays.asList("", "  ", null, "Notch");
        t.configured(() -> list);
        assertTrue(t.isTeammate(null, "notch"));
        assertEquals("1 teammate (1 from config)", t.summary(), "blank and null entries are skipped");
    }

    @Test
    void theConfigListIsReadLive() {
        MapArtConfig cfg = new MapArtConfig();
        Teammates t = new Teammates();
        t.configured(() -> cfg.teammates);
        assertTrue(t.isEmpty(), "empty config list: nobody");
        cfg.teammates.add("Builder_Two");
        assertTrue(t.isTeammate(null, "BUILDER_TWO"), "a change is seen at once");
        assertFalse(t.isEmpty());
        cfg.teammates.add(STRANGER.toString());
        assertTrue(t.isTeammate(STRANGER, null));
        assertEquals("2 teammates (2 from config)", t.summary());
        cfg.teammates.clear();
        assertTrue(t.isEmpty());
        cfg.teammates = null; // a mapart.json with "teammates": null
        assertTrue(t.isEmpty());
        t.configured(null);
        assertTrue(t.isEmpty());
        t.configured(() -> {
            throw new IllegalStateException("config being replaced");
        });
        assertTrue(t.isEmpty(), "a failing feed counts as its last list");
    }
}
