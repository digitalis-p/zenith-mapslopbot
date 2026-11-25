package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.config.MapArtConfig.RegionCfg;
import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The part of a stored discovery a restart can still use after the material regions changed
 * ({@code build.seedDiscoveryPartial}). A stored discovery remembers the
 * regions it searched as a signature, one {@code min>max;} part per region. A region of the site's storage whose part
 * is in it was searched exactly so: the stored containers in it are taken. Every other storage region (new, moved,
 * resized) is pending: only those are discovered (loaded and read) at the next STOCK_CHECK, not every region again
 * (live 2026-10-13: a full discovery walked twice 188 blocks to the far sorter, 61 s). Pure; unit-tested.
 */
final class DiscoverySeed {
    private DiscoverySeed() { }

    /** {@code known}: the stored containers in unchanged regions; {@code pending}: the storage regions to discover. */
    record Result(List<Pos> known, List<RegionCfg> pending) { }

    /** A region's part of a signature ({@code min>max}, without the separator). */
    static String key(RegionCfg r) {
        return r.min.key() + '>' + r.max.key();
    }

    /** The parts of a stored signature ({@code "a>b;c>d;"}); empty for null. */
    static Set<String> parts(@Nullable String signature) {
        Set<String> out = new LinkedHashSet<>();
        if (signature == null) return out;
        for (String s : signature.split(";")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    /**
     * What a restart may take from a stored discovery ({@code stored}, found in the regions of {@code storedSignature})
     * when the regions are now {@code storage} (the site's material regions; the colour-suppression ones are discovered
     * by their own jobs, as with an unchanged signature). Null when there is nothing to reuse: no stored discovery, no
     * storage region left unchanged, or none pending (an unchanged signature is the plain seed's case).
     */
    static @Nullable Result of(@Nullable List<Pos> stored, @Nullable String storedSignature, List<RegionCfg> storage) {
        if (stored == null || stored.isEmpty()) return null;
        Set<String> searched = parts(storedSignature);
        if (searched.isEmpty()) return null;
        List<RegionCfg> same = new ArrayList<>();
        List<RegionCfg> pending = new ArrayList<>();
        for (RegionCfg r : storage) {
            if (r == null || r.min == null || r.max == null) continue;
            (searched.contains(key(r)) ? same : pending).add(r);
        }
        if (same.isEmpty() || pending.isEmpty()) return null;
        List<Pos> known = new ArrayList<>();
        for (Pos p : stored) {
            for (RegionCfg r : same) {
                if (r.contains(p)) {
                    known.add(p);
                    break;
                }
            }
        }
        if (known.isEmpty()) return null;
        return new Result(known, pending);
    }
}
