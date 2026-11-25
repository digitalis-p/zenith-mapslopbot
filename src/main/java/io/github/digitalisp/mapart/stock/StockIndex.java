package io.github.digitalisp.mapart.stock;

import io.github.digitalisp.mapart.model.Pos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Last observed contents of every material container. Updated whenever the bot opens a container
 * (scan, withdraw, deposit). Persisted by the JobStore (Gson). Thread-safe via synchronized methods.
 * CONTRACT (lead-owned).
 */
public class StockIndex {
    /** Pos.key() → snapshot. */
    public Map<String, Snapshot> containers = new TreeMap<>();
    /**
     * Item ids ever seen in a material container, kept after the container is emptied. The base has no
     * The plugin reads no chest labels, so "the base has a chest for this item" means "a container once held it".
     * Absent in stock.json written before it existed; current snapshots count as well (see {@link #everHeld}).
     */
    public Set<String> seen = new TreeSet<>();
    /**
     * The material containers the last complete discovery found (Pos.key()), and the material regions it searched
     * ({@link #regionSignature}); null in stock.json written before they existed. A restart takes them as the discovery
     * while the regions are unchanged (build.seedDiscoveryFromStock), instead of walking to every region again.
     */
    public List<String> discovered;
    /** The colour-suppression palette last read from the index row (item ids); null before the first read. */
    public List<String> csPalette;
    public String discoveredRegions;
    /**
     * Near storage first ({@code build.nearScanPersist}): when the last full near sweep ran, per platform
     * ({@link #platformKey}), epoch ms. Null in stock.json written before it existed.
     */
    public Map<String, Long> nearSweptAt;
    /** The same for the last targeted near look per platform ({@link #platformKey}) and item id. Null before it existed. */
    public Map<String, Map<String, Long>> nearLookedAt;

    /**
     * Which containers the queries below see (not persisted): the current job's storage. A colour-suppression job sees
     * only the CS material chests, any other job everything but them (docs/COLOR_SUPPRESSION.md). Null = all.
     */
    private transient volatile Predicate<Pos> scope;

    public void setScope(Predicate<Pos> scope) {
        this.scope = scope;
    }

    private boolean visible(String key) {
        Predicate<Pos> sc = scope;
        return sc == null || sc.test(Pos.parseKey(key));
    }

    public static class Snapshot {
        public Map<String, Integer> items = new LinkedHashMap<>();
        public int freeSlots;
        public int totalSlots;
        public long scannedAt;
        /**
         * Items an earlier snapshot of this container held (a sorter chest the bot emptied is still that item's
         * chest: the near look before a far trip reads it again). Null in stock.json written before it existed.
         */
        public Set<String> held = new TreeSet<>();
    }

    public synchronized void update(Pos pos, Map<String, Integer> items, int freeSlots, int totalSlots, long now) {
        Snapshot s = new Snapshot();
        s.items = new LinkedHashMap<>(items);
        s.freeSlots = freeSlots;
        s.totalSlots = totalSlots;
        s.scannedAt = now;
        Snapshot old = containers.get(pos.key());
        if (old != null) {
            if (old.held != null) s.held.addAll(old.held);
            old.items.forEach((k, v) -> {
                if (v != null && v > 0) s.held.add(k);
            });
        }
        containers.put(pos.key(), s);
        if (seen == null) seen = new TreeSet<>();
        items.forEach((k, v) -> {
            if (v != null && v > 0) seen.add(k);
        });
    }

    /** A complete discovery's result, to be seeded after a restart ({@link #discovered}). */
    public synchronized void setDiscovered(Collection<Pos> found, String regionSignature) {
        List<String> keys = new ArrayList<>(found.size());
        for (Pos p : found) keys.add(p.key());
        discovered = keys;
        discoveredRegions = regionSignature;
    }

    /** The discovered containers if they were found in regions with this signature, else null. */
    public synchronized List<Pos> discoveredFor(String regionSignature) {
        if (discovered == null || discovered.isEmpty() || !regionSignature.equals(discoveredRegions)) return null;
        List<Pos> out = new ArrayList<>(discovered.size());
        for (String k : discovered) out.add(Pos.parseKey(k));
        return out;
    }

    /** The key of a platform in {@link #nearSweptAt} and {@link #nearLookedAt}: "minX,minZ,artY". */
    public static String platformKey(int minX, int minZ, int artY) {
        return minX + "," + minZ + "," + artY;
    }

    /** A full near sweep of {@code platform} at {@code at}. */
    public synchronized void nearSwept(String platform, long at) {
        if (nearSweptAt == null) nearSweptAt = new TreeMap<>();
        nearSweptAt.put(platform, at);
    }

    /** A targeted near look of {@code platform} for {@code items} at {@code at}. */
    public synchronized void nearLooked(String platform, Collection<String> items, long at) {
        if (items.isEmpty()) return;
        if (nearLookedAt == null) nearLookedAt = new TreeMap<>();
        Map<String, Long> m = nearLookedAt.computeIfAbsent(platform, k -> new TreeMap<>());
        for (String id : items) m.put(id, at);
    }

    /** The last full near sweep of {@code platform}, or null. */
    public synchronized Long nearSweptAt(String platform) {
        return nearSweptAt == null ? null : nearSweptAt.get(platform);
    }

    /** The last targeted near look of {@code platform} per item (a copy; empty when none). */
    public synchronized Map<String, Long> nearLookedAt(String platform) {
        Map<String, Long> m = nearLookedAt == null ? null : nearLookedAt.get(platform);
        return m == null ? Map.of() : new TreeMap<>(m);
    }

    public synchronized int total(String itemId) {
        int n = 0;
        for (var e : containers.entrySet()) if (visible(e.getKey())) n += e.getValue().items.getOrDefault(itemId, 0);
        return n;
    }

    public synchronized Map<String, Integer> totals() {
        Map<String, Integer> m = new TreeMap<>();
        for (var e : containers.entrySet()) if (visible(e.getKey())) e.getValue().items.forEach((k, v) -> m.merge(k, v, Integer::sum));
        return m;
    }

    /** Containers holding {@code itemId}, most-stocked first. */
    public synchronized List<Pos> containersWith(String itemId) {
        List<Map.Entry<String, Snapshot>> l = new ArrayList<>();
        for (var e : containers.entrySet()) if (visible(e.getKey()) && e.getValue().items.getOrDefault(itemId, 0) > 0) l.add(e);
        l.sort(Comparator.comparingInt((Map.Entry<String, Snapshot> e) -> e.getValue().items.get(itemId)).reversed());
        List<Pos> out = new ArrayList<>();
        for (var e : l) out.add(Pos.parseKey(e.getKey()));
        return out;
    }

    /** True if some material container holds {@code itemId} now or was ever seen holding it. */
    public synchronized boolean everHeld(String itemId) {
        if (scope != null) {
            // scoped: only the visible containers' own history (the global set does not say where an item was seen)
            for (var e : containers.entrySet()) {
                if (!visible(e.getKey())) continue;
                Snapshot s = e.getValue();
                if (s.items.getOrDefault(itemId, 0) > 0 || (s.held != null && s.held.contains(itemId))) return true;
            }
            return false;
        }
        if (seen != null && seen.contains(itemId)) return true;
        for (Snapshot s : containers.values()) if (s.items.getOrDefault(itemId, 0) > 0) return true;
        return false;
    }

    /**
     * The items of {@code items} that no material container has ever held: the base has no chest for them,
     * so a job needing them cannot be built until someone adds one. Sorted; empty while nothing has been
     * scanned (then nothing is known).
     */
    public synchronized Set<String> unstocked(Collection<String> items) {
        Set<String> out = new TreeSet<>();
        if (items == null || containers.isEmpty() || (scope != null && containers.keySet().stream().noneMatch(this::visible))) return out;
        for (String id : items) if (!everHeld(id)) out.add(id);
        return out;
    }

    /** Oldest snapshot time, or 0 if empty. */
    public synchronized long oldestScan() {
        long min = Long.MAX_VALUE;
        for (Snapshot s : containers.values()) min = Math.min(min, s.scannedAt);
        return containers.isEmpty() ? 0 : min;
    }

    /** item id → shortfall (needed − available), only items that are short. */
    public synchronized Map<String, Integer> shortfall(Map<String, Integer> needed) {
        Map<String, Integer> m = new TreeMap<>();
        needed.forEach((k, v) -> {
            int have = total(k);
            if (have < v) m.put(k, v - have);
        });
        return m;
    }
}
