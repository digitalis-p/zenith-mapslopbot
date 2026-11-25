package io.github.digitalisp.mapart.engine;

import io.github.digitalisp.mapart.bot.impl.ItemIds;
import io.github.digitalisp.mapart.bot.impl.Valuables;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * What RESTOCK may get rid of. The account is the owner's own, so the bot deposits or drops MATERIALS only:
 * <ul>
 *   <li>items it withdrew from material chests,</li>
 *   <li>items in the current or remaining jobs' material lists (the current tile's needs included),</li>
 *   <li>the platform's materials: items a material (sorter output) chest holds or once held ({@code StockIndex}),</li>
 *   <li>the items of the blocks the NUKE broke on an art layer (the drops it picked up itself).</li>
 * </ul>
 * Everything else stays where it is: totems, gear, shulker boxes, maps the bot did not make, unknown items. They are
 * "untouchable": their slots count as occupied when the load is planned. The engine's keep set (tools, food, maps,
 * panes, XP bottles, a spare anvil) is kept as before, and {@link Valuables} are never materials. Pure; unit-tested.
 */
final class Leftovers {
    private final Predicate<String> keepAlways;
    private final Set<String> known;
    private final Predicate<String> sorterItem;
    /** Junk the bot picked up (mob drops): disposable like a material, whatever it is ({@code build.junkItems}). */
    private final Predicate<String> junkItem;
    /** Counts kept on top of the needs (the fall rescue's climbing kit, {@code build.fallRescueKit}). */
    private Map<String, Integer> reserve = Map.of();

    /**
     * @param keepAlways   the engine's working items, never disposed of
     * @param known        withdrawn materials, job material lists, the current needs, the nuked blocks' items
     * @param sorterItem   true for an item a material container holds or once held
     */
    Leftovers(Predicate<String> keepAlways, Collection<String> known, Predicate<String> sorterItem) {
        this(keepAlways, known, sorterItem, id -> false);
    }

    /** @param junkItem true for a junk item the drop point may take ({@code build.dropJunk}); never a valuable */
    Leftovers(Predicate<String> keepAlways, Collection<String> known, Predicate<String> sorterItem,
              Predicate<String> junkItem) {
        this.keepAlways = keepAlways;
        this.known = new HashSet<>();
        for (String id : known) if (id != null) this.known.add(ItemIds.material(id));
        this.sorterItem = sorterItem;
        this.junkItem = junkItem;
    }

    /**
     * Keeps {@code r} (id → count) on top of the needs in {@link #junk}: the fall rescue's climbing kit
     * ({@code build.fallRescueKit}). Returns this.
     */
    Leftovers reserving(Map<String, Integer> r) {
        this.reserve = Map.copyOf(r);
        return this;
    }

    /** {@code id} is junk ({@code build.junkItems}) the bot throws at its junk spot. */
    boolean isJunk(String id) {
        return id != null && !keepAlways.test(id) && !kept(id) && junkItem.test(id);
    }

    /** A valuable the bot keeps: every one, except a bow or crossbow listed as junk ({@link Valuables#junkWhenPlain}). */
    private boolean kept(String id) {
        return Valuables.isValuable(id) && !(Valuables.junkWhenPlain(id) && junkItem.test(id));
    }

    /** True if {@code id} is a material the bot may deposit or drop. */
    boolean material(String id) {
        if (id == null || keepAlways.test(id) || kept(id)) return false;
        if (junkItem.test(id)) return true;
        String m = ItemIds.material(id);
        return known.contains(m) || sorterItem.test(m) || (!m.equals(id) && sorterItem.test(id));
    }

    /** Neither a material nor one of the engine's working items: the bot never moves it. */
    boolean untouchable(String id) {
        return id != null && !keepAlways.test(id) && !material(id);
    }

    /**
     * Materials to get rid of: everything above {@code needs} (and the {@link #reserving reserve}), in whole stacks; a
     * partial surplus of a material still needed stays (leftovers go out in whole stacks).
     */
    Map<String, Integer> junk(Map<String, Integer> contents, Map<String, Integer> needs, ToIntFunction<String> maxStack) {
        Map<String, Integer> m = new TreeMap<>();
        contents.forEach((id, n) -> {
            if (!material(id)) return;
            int need = needs.getOrDefault(id, 0);
            int extra = n - need - reserve.getOrDefault(id, 0);
            if (extra <= 0 || (need > 0 && extra < Math.max(1, maxStack.applyAsInt(id)))) return;
            m.put(id, extra);
        });
        return m;
    }

    /** The untouchable items of {@code contents} (hotbar + main), id → count. */
    Map<String, Integer> untouchable(Map<String, Integer> contents) {
        Map<String, Integer> m = new TreeMap<>();
        contents.forEach((id, n) -> {
            if (untouchable(id) && n != null && n > 0) m.put(id, n);
        });
        return m;
    }

    /** Hotbar + main slots the untouchable items hold ({@code slotsUsed}: id → slots). */
    int untouchableSlots(Map<String, Integer> slotsUsed) {
        int n = 0;
        for (var e : slotsUsed.entrySet()) if (untouchable(e.getKey())) n += Math.max(0, e.getValue());
        return n;
    }

    /**
     * "minecraft:totem_of_undying (offhand), minecraft:diamond_sword, minecraft:dirt ×128": the items kept although
     * they are no materials, the offhand first (everything there is kept, whatever it is).
     */
    static String describe(Map<String, Integer> offhand, Map<String, Integer> kept) {
        List<String> out = new ArrayList<>();
        offhand.forEach((id, n) -> out.add(id + (n > 1 ? " ×" + n : "") + " (offhand)"));
        kept.forEach((id, n) -> out.add(id + (n > 1 ? " ×" + n : "")));
        return String.join(", ", out);
    }

    /** Number of distinct items {@link #describe} lists. */
    static int count(Map<String, Integer> offhand, Map<String, Integer> kept) {
        return offhand.size() + kept.size();
    }
}
