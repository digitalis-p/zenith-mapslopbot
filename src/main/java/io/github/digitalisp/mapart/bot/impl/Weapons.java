package io.github.digitalisp.mapart.bot.impl;

import java.util.List;
import java.util.Map;

/**
 * The weapon a fight holds (pure, unit-tested): a sword, then an axe, the strongest material first, and only one with
 * more than {@code floor} uses left (tools and weapons never break: {@code build.toolSafetyFloor}). None: -1, the bot
 * fights with a bare hand rather than wear one out.
 */
final class Weapons {
    private Weapons() {
    }

    /** One carried item: its player slot, id, uses left and whether it wears at all. */
    record Item(int slot, String id, int remaining, boolean damageable) { }

    private static final Map<String, Integer> TIER = Map.of(
        "netherite", 6, "diamond", 5, "iron", 4, "stone", 3, "golden", 2, "wooden", 1);

    /** 2 for a sword, 1 for an axe, 0 for anything else. */
    static int kind(String id) {
        String p = id == null ? "" : id.substring(id.indexOf(':') + 1);
        if (p.endsWith("_sword")) return 2;
        if (p.endsWith("_axe") && !p.endsWith("_pickaxe")) return 1;
        return 0;
    }

    static int tier(String id) {
        String p = id.substring(id.indexOf(':') + 1);
        int us = p.indexOf('_');
        return us < 0 ? 0 : TIER.getOrDefault(p.substring(0, us), 0);
    }

    /** The slot of the weapon to hold, or -1 (bare hand). */
    static int pick(List<Item> items, int floor) {
        Item best = null;
        for (Item i : items) {
            if (kind(i.id()) == 0) continue;
            if (i.damageable() && i.remaining() <= floor) continue;
            if (best == null || better(i, best)) best = i;
        }
        return best == null ? -1 : best.slot();
    }

    /**
     * Ticks until the attack strength of the weapon {@code id} (null: a bare hand) is full again after a hit (vanilla
     * 1.21: 20 / attack speed, one more for the tick the hit lands in): sword 1.6 per second, axe 1.0 (diamond, netherite,
     * gold), 0.9 (iron), 0.8 (stone, wood), hand 4.0. A creeper is hit only with a full attack.
     */
    static int cooldownTicks(String id) {
        double speed = 4.0;
        if (id != null) {
            String p = id.substring(id.indexOf(':') + 1);
            if (p.endsWith("_sword")) speed = 1.6;
            else if (kind(id) == 1) {
                String t = p.substring(0, p.indexOf('_'));
                speed = t.equals("iron") ? 0.9 : t.equals("stone") || t.equals("wooden") ? 0.8 : 1.0;
            }
        }
        return (int) Math.ceil(20.0 / speed) + 1;
    }

    private static boolean better(Item a, Item b) {
        if (kind(a.id()) != kind(b.id())) return kind(a.id()) > kind(b.id());
        if (tier(a.id()) != tier(b.id())) return tier(a.id()) > tier(b.id());
        return a.remaining() > b.remaining();
    }
}
