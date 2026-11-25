package io.github.digitalisp.mapart;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The build identity the jar carries (Gradle's buildInfo task), as logged at load and shown by status. */
class BuildInfoTest {
    @Test
    void labelAndDescription() {
        Properties p = new Properties();
        p.setProperty("version", "0.1.0");
        p.setProperty("commit", "a1b2c3d-dirty");
        p.setProperty("built", "2026-10-11T03:57:12Z");
        BuildInfo b = BuildInfo.of(p);
        assertEquals("0.1.0 (a1b2c3d-dirty)", b.label());
        assertEquals("0.1.0 (a1b2c3d-dirty), built 2026-10-11 03:57 UTC", b.describe());
    }

    @Test
    void withoutTheFileTheCommitIsUnknown() {
        BuildInfo b = BuildInfo.of(null);
        assertEquals(BuildConstants.VERSION + " (unknown)", b.label());
        assertEquals(BuildConstants.VERSION + " (unknown), built unknown", b.describe());
        Properties p = new Properties();
        p.setProperty("commit", " ");
        assertEquals("unknown", BuildInfo.of(p).commit());
    }
}
