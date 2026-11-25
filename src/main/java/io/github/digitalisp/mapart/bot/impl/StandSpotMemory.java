package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Where containers can be used from, keyed by the container's canonical position:
 * <ul>
 *   <li><b>good</b>: the feet cell each container was last opened from, tried first next time; kept in a file across
 *       restarts when one is given ({@code dataDir/stand-spots.txt}, one line {@code "cx cy cz fx fy fz"} per
 *       container, at most {@link #MAX_GOOD} of them);</li>
 *   <li><b>failed</b>: cells from which no face centre of a container was in sight after all, though the stand-spot
 *       search said one would be (live 2b2t: the same chest and cell failed up to 10 times in an hour): not tried for
 *       that container again this session. A cell the container opens from later is good again.</li>
 * </ul>
 * Client tick thread only. A file that cannot be read or written is ignored (logged once): the memory then lasts
 * the session.
 */
final class StandSpotMemory {
    /** Containers whose good spot the file keeps (the most recently used ones). */
    static final int MAX_GOOD = 4096;
    /** Failed cells kept per container (more: the oldest go). */
    static final int MAX_FAILED_PER_CONTAINER = 16;

    private final @Nullable Path file;
    private final Consumer<String> log;
    /** Insertion order = last use: the oldest go first when the file would grow beyond {@link #MAX_GOOD}. */
    private final LinkedHashMap<Pos, Pos> good = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Pos, Set<Pos>> failed = new HashMap<>();
    private boolean warned;

    /** @param file where good spots are kept across restarts, or null (this session only) */
    StandSpotMemory(@Nullable Path file, Consumer<String> log) {
        this.file = file;
        this.log = log;
        load();
    }

    /** The cell the container was last opened from, or null. */
    @Nullable Pos good(Pos container) {
        return good.get(container);
    }

    /** Cells that did not see the container after all this session (empty: none). */
    Set<Pos> failed(Pos container) {
        Set<Pos> f = failed.get(container);
        return f == null ? Set.of() : Set.copyOf(f);
    }

    /** The container was opened from {@code feet}: tried first next time, and no longer counted as failed. */
    void opened(Pos container, Pos feet) {
        Set<Pos> f = failed.get(container);
        if (f != null && f.remove(feet) && f.isEmpty()) failed.remove(container);
        Pos before = good.put(container, feet);
        if (!feet.equals(before)) save();
    }

    /** No face centre of the container was in sight from {@code feet} after all. */
    void missed(Pos container, Pos feet) {
        Set<Pos> f = failed.computeIfAbsent(container, k -> new java.util.LinkedHashSet<>());
        if (f.add(feet) && f.size() > MAX_FAILED_PER_CONTAINER) f.remove(f.iterator().next());
        if (feet.equals(good.get(container))) {
            good.remove(container);
            save();
        }
    }

    /** Containers with a good spot (tests). */
    int goodCount() {
        return good.size();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) continue;
                String[] p = t.split("\\s+");
                if (p.length != 6) continue;
                try {
                    Pos c = new Pos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
                    Pos f = new Pos(Integer.parseInt(p[3]), Integer.parseInt(p[4]), Integer.parseInt(p[5]));
                    good.put(c, f);
                } catch (NumberFormatException e) {
                    // a damaged line: skipped
                }
            }
            while (good.size() > MAX_GOOD) good.remove(good.keySet().iterator().next());
        } catch (IOException | RuntimeException e) {
            warnOnce("stand spots not read from " + file + ": " + e);
        }
    }

    private void save() {
        if (file == null) return;
        while (good.size() > MAX_GOOD) good.remove(good.keySet().iterator().next());
        List<String> lines = new ArrayList<>(good.size() + 1);
        lines.add("# container x y z, then the feet cell it was last opened from (map-art plugin; safe to delete)");
        for (Map.Entry<Pos, Pos> e : new ArrayList<>(good.entrySet())) {
            Pos c = e.getKey();
            Pos f = e.getValue();
            lines.add(c.x() + " " + c.y() + " " + c.z() + " " + f.x() + " " + f.y() + " " + f.z());
        }
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            warnOnce("stand spots not saved to " + file + ": " + e);
        }
    }

    private void warnOnce(String what) {
        if (warned) return;
        warned = true;
        log.accept(what);
    }

    /** For tests: the failed cells of every container. */
    Map<Pos, Set<Pos>> failedView() {
        Map<Pos, Set<Pos>> out = new HashMap<>();
        failed.forEach((k, v) -> out.put(k, new HashSet<>(v)));
        return out;
    }
}
