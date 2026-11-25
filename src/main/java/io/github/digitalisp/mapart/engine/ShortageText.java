package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.model.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Human wording for material shortages in Discord lines (the owner, 2026-10-12: the "Materials short" alert read as a wait
 * while the bot was building another map, and named items as {@code minecraft:cyan_terracotta → 199 missing}). Item
 * names without the namespace and with spaces ("cyan terracotta"), counts as "199 more needed", and maps named by their
 * tile ("tile 3,3"), never by their index in the job: maps are built out of order, so "map 16 of 28" read as 15 maps
 * done (the owner, 2026-10-12). Progress is the count of stored maps ("6 of 28 maps done"). Pure.
 */
final class ShortageText {
    private ShortageText() { }

    /** Short items listed in a sentence before "and N more materials". */
    static final int SENTENCE_ITEMS = 3;

    /** "minecraft:cyan_terracotta" → "cyan terracotta". */
    static String item(String id) {
        if (id == null) return "?";
        String s = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        return s.replace('_', ' ');
    }

    static String count(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** Most missing first, then by name. */
    static List<Map.Entry<String, Integer>> sorted(Map<String, Integer> m) {
        List<Map.Entry<String, Integer>> es = new ArrayList<>(m.entrySet());
        es.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        return es;
    }

    /** "199 cyan terracotta, 1 crimson nylium" (status notes, the job's status detail, logs). */
    static String amounts(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sorted(m)) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(count(e.getValue())).append(' ').append(item(e.getKey()));
        }
        return sb.toString();
    }

    /**
     * "199 more cyan terracotta", "199 more cyan terracotta and 1 more crimson nylium", "199 more cyan terracotta, 40 more
     * lime carpet, 3 more blue wool and 2 more materials".
     */
    static String needs(Map<String, Integer> shortfall) {
        List<Map.Entry<String, Integer>> es = sorted(shortfall);
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < es.size() && i < SENTENCE_ITEMS; i++)
            parts.add(count(es.get(i).getValue()) + " more " + item(es.get(i).getKey()));
        if (es.size() > SENTENCE_ITEMS) parts.add((es.size() - SENTENCE_ITEMS) + " more materials");
        if (parts.size() <= 1) return parts.isEmpty() ? "" : parts.getFirst();
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /**
     * One line per item, each starting with a newline: "- cyan terracotta: 199 more needed (about 4 stacks)", and for an
     * item no chest at this base has ever held "- crimson nylium: 1 more needed (no chest at this base holds it)".
     */
    static String lines(Map<String, Integer> shortfall, Set<String> unstocked) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sorted(shortfall)) {
            int n = e.getValue();
            boolean none = unstocked != null && unstocked.contains(e.getKey());
            sb.append("\n- ").append(item(e.getKey())).append(": ").append(count(n)).append(" more needed");
            if (n >= 64 || none) {
                sb.append(" (");
                if (n >= 64) sb.append(AlertText.stacks(n));
                if (n >= 64 && none) sb.append("; ");
                if (none) sb.append("no chest at this base holds it");
                sb.append(')');
            }
        }
        return sb.toString();
    }

    /** "tile 3,3"; for a one-map job "the map". */
    static String mapOf(Job j, Job.TileState t) {
        return j.tiles.size() > 1 ? "tile " + t.x + "," + t.z : "the map";
    }

    /** "ellen tile 3,3", or "Solo" for a one-map job. */
    static String fullMap(Job j, Job.TileState t) {
        return j.tiles.size() > 1 ? j.name + " " + mapOf(j, t) : j.name;
    }

    /** A map named in a line about {@code context}'s map: "tile 3,3" in the same job, else its full name. */
    static String mapIn(Job context, Job j, Job.TileState t) {
        return context != null && context.id != null && context.id.equals(j.id) ? mapOf(j, t) : fullMap(j, t);
    }

    /** "6 of 28 maps done": the job's stored maps. */
    static String done(Job j) {
        int stored = 0;
        for (Job.TileState t : j.tiles) if (t.status == io.github.digitalisp.mapart.model.TileStatus.STORED) stored++;
        int n = j.tiles.size();
        return stored + " of " + n + (n == 1 ? " map" : " maps") + " done";
    }
}
