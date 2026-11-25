package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Job;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain wording for the always-on alerts. The owner, 2026-10-29, on a live
 * "No progress" alert that led with the job hash and the lane numbers, named the phase STOCK_CHECK, buried the cause
 * (801 white carpet short) in brackets, tacked on an older warning about another tile and ended with "Check it in game":
 * "literally incomprehensible". So: the cause in the title, the action in the first sentence, no phase names, materials
 * as "white carpet", counts of 64 and over in stacks, the job id and lanes last. "Check it in game" only when the cause
 * is not known. Log lines keep their detail; only the alert text is plain. Pure.
 */
final class AlertText {
    private AlertText() { }

    /** A title and a body for {@link Notifier#alert}. */
    record Alert(String title, String body) { }

    private static final Pattern ITEM_ID = Pattern.compile("minecraft:([a-z0-9_]+)");

    /** "minecraft:white_carpet" → "white carpet" everywhere in a text. */
    static String plain(String text) {
        if (text == null || !text.contains("minecraft:")) return text;
        Matcher m = ITEM_ID.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).replace('_', ' ')));
        m.appendTail(sb);
        return sb.toString();
    }

    /** "about 13 stacks" for 801, "2 stacks" for 128, "1 stack" for 64, "40" for 40: stacks are ceil(n / 64). */
    static String stacks(int n) {
        if (n < 64) return ShortageText.count(n);
        int s = (n + 63) / 64;
        return (n % 64 == 0 ? "" : "about ") + s + (s == 1 ? " stack" : " stacks");
    }

    /** "Out of white carpet", "Out of white carpet and black wool", "Out of white carpet and 3 more materials". */
    static String outOf(Map<String, Integer> shortfall) {
        List<Map.Entry<String, Integer>> es = ShortageText.sorted(shortfall);
        if (es.isEmpty()) return "Out of materials";
        String first = ShortageText.item(es.getFirst().getKey());
        if (es.size() == 1) return "Out of " + first;
        if (es.size() == 2) return "Out of " + first + " and " + ShortageText.item(es.get(1).getKey());
        return "Out of " + first + " and " + (es.size() - 1) + " more materials";
    }

    /** "about 13 stacks" (one item), "about 13 stacks of white carpet and 40 black wool" (several, at most 3 named). */
    static String toPut(Map<String, Integer> shortfall) {
        List<Map.Entry<String, Integer>> es = ShortageText.sorted(shortfall);
        if (es.size() == 1) return stacks(es.getFirst().getValue());
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < es.size() && i < ShortageText.SENTENCE_ITEMS; i++) {
            int n = es.get(i).getValue();
            parts.add(stacks(n) + (n >= 64 ? " of " : " ") + ShortageText.item(es.get(i).getKey()));
        }
        if (es.size() > ShortageText.SENTENCE_ITEMS) parts.add("the rest listed below");
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /**
     * A started map waits on its platform for items the stock lacks (everything else of it is built). {@code inStock}:
     * per item, how many storage still holds (too few for a trip); {@code unstocked}: items no chest at the base held.
     * "Snowy Angel tile 6,1 needs 801 more white carpet; none left in near or far storage. Put about 13 stacks in a chest
     * on the soulsand platform. Everything else on the tile is built (1,503 of 2,304). Nothing to build until then."
     * The first line carries "Nothing to build": the ops watchers look for it in the ALERT log line.
     */
    static Alert outOfMaterials(String map, String platformId, Map<String, Integer> shortfall,
                                Map<String, Integer> inStock, Set<String> unstocked, int placed, int total,
                                long recheckMinutes, String more) {
        StringBuilder b = new StringBuilder(map).append(" needs ").append(ShortageText.needs(shortfall)).append("; ");
        boolean allUnstocked = unstocked != null && unstocked.containsAll(shortfall.keySet());
        int held = 0;
        for (String id : shortfall.keySet()) held += Math.max(0, inStock.getOrDefault(id, 0));
        if (allUnstocked) b.append(shortfall.size() == 1 ? "no chest at this base holds it" : "no chest at this base holds them");
        else if (held > 0) b.append("too few left in storage for a trip");
        else b.append("none left in near or far storage");
        b.append(". Put ").append(toPut(shortfall)).append(" in a chest on the ").append(platformId).append(" platform.")
            .append(" Everything else on the ").append(map.contains(" tile ") ? "tile" : "map").append(" is built (")
            .append(ShortageText.count(placed)).append(" of ").append(ShortageText.count(total)).append(").")
            .append(" Nothing to build until then.");
        if (shortfall.size() > 1 || (unstocked != null && !allUnstocked && unstocked.stream().anyMatch(shortfall::containsKey)))
            b.append("\nIt needs:").append(ShortageText.lines(shortfall, unstocked));
        b.append(more);
        b.append("\nThe bot looks in the chests again every ").append(recheckMinutes).append(" min.");
        return new Alert(outOf(shortfall), b.toString());
    }

    /** The place an alert is about, for its last line: "Snowy Angel tile 6,1 (#fbw204nw4zvwd, lanes 2-3)". */
    static String where(@Nullable Job job, Job.@Nullable TileState tile, String part) {
        if (job == null) return "";
        String map = tile != null ? ShortageText.fullMap(job, tile) : job.name;
        return map + " " + ref(job, part);
    }

    /** The job id and the unit's part, for an alert's last line: "(#fbw204nw4zvwd, lanes 2-3)". */
    static String ref(Job job, String part) {
        return "(#" + job.id + (part.isEmpty() ? "" : ", " + part) + ")";
    }

    /** Plain words for what the engine does in a phase: "restocking", "placing blocks". */
    static String doing(Phase p) {
        if (p == null) return "idle";
        return switch (p) {
            case IDLE -> "idle";
            case SELECT -> "choosing the next map";
            case INSPECT -> "looking at the platform";
            case STOCK_CHECK -> "checking the chests for materials";
            case GEAR -> "fetching tools and gear";
            case RESTOCK -> "restocking";
            case BUILD -> "placing blocks";
            case VERIFY -> "checking the art";
            case FILL_MAP -> "filling the map";
            case CHECK_MAP -> "checking the map";
            case RIDE_BOARD -> "getting ready to ride";
            case RIDE_PRELOAD -> "riding the preload lap";
            case RIDE -> "riding the muncher";
            case RIDE_END -> "leaving the cart";
            case RIDE_RESET -> "waiting for the muncher reset";
            case LOCK -> "locking the map";
            case RENAME -> "renaming the map";
            case STORE -> "storing the map";
            case COUNT -> "pressing the map counter";
            case NUKE -> "breaking leftover blocks";
            case CLEAR -> "clearing the platform";
            case WAIT_CLEAR -> "waiting for the platform to clear";
            case FINISH_TILE -> "finishing the map";
            case BLOCKED -> "stopped, needing help";
        };
    }
}
