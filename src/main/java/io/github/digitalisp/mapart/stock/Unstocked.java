package io.github.digitalisp.mapart.stock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wording for materials the base has no chest for ({@link StockIndex#unstocked}: no material container has
 * ever held them). Such a job cannot be built until someone adds a chest, which is different from an ordinary
 * shortage that the next restock fixes. Shared by the submission card and the WAITING_MATERIALS alert. Pure.
 */
public final class Unstocked {
    private Unstocked() { }

    /** Marks a no-chest row in material tables; "!" marks an ordinary shortage. */
    public static final String MARK = "✗";

    static final String HEAD = "No chest at this base for: ";
    static final String TAIL = ". This job can't be built until these are stocked.";

    /**
     * "No chest at this base for: decorated_pot, white_candle. This job can't be built until these are
     * stocked." Ids are shown without the {@code minecraft:} namespace, in the given order; the list ends with
     * "and N more" when it would pass {@code maxChars}.
     */
    public static String line(Collection<String> items, int maxChars) {
        List<String> names = new ArrayList<>();
        for (String id : items) names.add(shortId(id));
        StringBuilder sb = new StringBuilder(HEAD);
        int budget = maxChars - TAIL.length();
        for (int i = 0; i < names.size(); i++) {
            String part = (i > 0 ? ", " : "") + names.get(i);
            int after = names.size() - i - 1;
            int reserve = after == 0 ? 0 : (" and " + after + " more").length();
            if (i > 0 && sb.length() + part.length() + reserve > budget) {
                sb.append(" and ").append(names.size() - i).append(" more");
                break;
            }
            sb.append(part);
        }
        return sb.append(TAIL).toString();
    }

    /**
     * Item lines of the WAITING_MATERIALS alert, each starting with a newline: "\nminecraft:x → N missing" for
     * every short item the base has a chest for, then one {@link #line} for the {@code unstocked} ones.
     */
    public static String waitingLines(Map<String, Integer> shortfall, Set<String> unstocked) {
        StringBuilder sb = new StringBuilder();
        List<String> noChest = new ArrayList<>();
        shortfall.forEach((k, v) -> {
            if (unstocked != null && unstocked.contains(k)) noChest.add(k);
            else sb.append('\n').append(k).append(" → ").append(v).append(" missing");
        });
        if (!noChest.isEmpty()) sb.append('\n').append(line(noChest, 1000));
        return sb.toString();
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}
