package io.github.digitalisp.mapart;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Properties;

/**
 * Which build of the plugin runs: version, git commit (short hash, "-dirty" when the working tree had changes to
 * tracked files) and build time, from {@code io/github/digitalisp/mapart/build-info.properties}, which Gradle's {@code buildInfo}
 * task writes into the jar. Logged at load and shown by {@code mapart status} / {@code !map status}. Without the file
 * (tests, an IDE run) the commit and time read "unknown".
 */
public record BuildInfo(String version, String commit, String built) {
    public static final String RESOURCE = "/io/github/digitalisp/mapart/build-info.properties";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
        .withZone(ZoneOffset.UTC);
    private static volatile BuildInfo current;

    /** This plugin's build (read once). */
    public static BuildInfo current() {
        BuildInfo b = current;
        if (b == null) {
            try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
                b = in == null ? of(null) : of(load(in));
            } catch (IOException | RuntimeException e) {
                b = of(null);
            }
            current = b;
        }
        return b;
    }

    private static Properties load(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(in);
        return p;
    }

    /** From the properties ({@code version}, {@code commit}, {@code built}); missing values read "unknown". */
    static BuildInfo of(Properties p) {
        String version = p == null ? null : p.getProperty("version");
        String commit = p == null ? null : p.getProperty("commit");
        String built = p == null ? null : p.getProperty("built");
        return new BuildInfo(blank(version) ? BuildConstants.VERSION : version.strip(),
            blank(commit) ? "unknown" : commit.strip(), blank(built) ? "unknown" : built.strip());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** "0.1.0 (a1b2c3d)", "0.1.0 (a1b2c3d-dirty)". */
    public String label() {
        return version + " (" + commit + ")";
    }

    /** "0.1.0 (a1b2c3d-dirty), built 2026-10-11 03:57 UTC". */
    public String describe() {
        return label() + ", built " + builtText();
    }

    /** Build time as "2026-10-11 03:57 UTC" ("unknown" without the build-info file). */
    public String builtText() {
        try {
            return TIME.format(Instant.parse(built));
        } catch (DateTimeParseException e) {
            return built;
        }
    }
}
