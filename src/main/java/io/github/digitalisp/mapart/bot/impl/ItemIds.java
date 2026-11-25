package io.github.digitalisp.mapart.bot.impl;

import java.util.Locale;
import java.util.Map;

/**
 * Pure helpers for namespaced ids and inventory slot numbering.
 *
 * <p>Player inventory (container 0) numbering: 0 craft result, 1-4 craft grid, 5 head, 6 chest, 7 legs, 8 feet,
 * 9-35 main, 36-44 hotbar, 45 offhand. When another container with {@code top} own slots is open, the player's
 * main inventory follows at {@code top .. top+26} and the hotbar at {@code top+27 .. top+35}.
 */
public final class ItemIds {
    private ItemIds() { }

    public static final int MAIN_START = 9;
    public static final int HOTBAR_START = 36;
    public static final int HOTBAR_END = 44;
    public static final int OFFHAND = 45;

    /** "white_carpet" → "minecraft:white_carpet"; trims and lower-cases. Null stays null. */
    public static String ns(String id) {
        if (id == null) return null;
        String s = id.trim().toLowerCase(Locale.ROOT);
        return s.contains(":") ? s : "minecraft:" + s;
    }

    /** "minecraft:white_carpet" → "white_carpet". */
    public static String path(String id) {
        if (id == null) return null;
        String s = ns(id);
        return s.substring(s.indexOf(':') + 1);
    }

    /**
     * Ids that are one material for the bot: the world flips these blocks by itself, so the item a broken block
     * drops (and the state a placed one ends up in) can be either. Eyeblossoms open at night and close by day
     * (random ticks); the moss sorter stocks open_eyeblossom.
     */
    private static final Map<String, String> SAME_MATERIAL = Map.of(
        "minecraft:closed_eyeblossom", "minecraft:open_eyeblossom");

    /**
     * The canonical id of {@code id}'s material ("minecraft:closed_eyeblossom" → "minecraft:open_eyeblossom"),
     * namespaced; every other id unchanged. Used for items (inventory, containers, placement items) and for
     * comparing block ids. Null stays null.
     */
    public static String material(String id) {
        String s = ns(id);
        return s == null ? null : SAME_MATERIAL.getOrDefault(s, s);
    }

    /** Player inventory armour slot for an equippable item id (5 head .. 8 feet), or -1. */
    public static int armorSlot(String id) {
        String p = path(id);
        if (p == null) return -1;
        if (p.endsWith("_helmet") || p.equals("turtle_helmet") || p.endsWith("_head") || p.equals("carved_pumpkin")) {
            return 5;
        }
        if (p.endsWith("_chestplate") || p.equals("elytra")) return 6;
        if (p.endsWith("_leggings")) return 7;
        if (p.endsWith("_boots")) return 8;
        return -1;
    }

    /** Player-inventory slot (9..44) → slot number inside an open container with {@code top} own slots. */
    public static int toContainerSlot(int playerSlot, int top) {
        if (top <= 0) return playerSlot;
        if (playerSlot < MAIN_START || playerSlot > HOTBAR_END) {
            throw new IllegalArgumentException("slot " + playerSlot + " is not visible in a container");
        }
        return top + (playerSlot - MAIN_START);
    }

    /** Inverse of {@link #toContainerSlot}: container slot → player-inventory slot, or -1 for the container's own slots. */
    public static int toPlayerSlot(int containerSlot, int top) {
        if (top <= 0) return containerSlot;
        if (containerSlot < top || containerSlot >= top + 36) return -1;
        return MAIN_START + (containerSlot - top);
    }

    /** Hotbar index 0..8 of a player-inventory slot, or -1. */
    public static int hotbarIndex(int playerSlot) {
        return playerSlot >= HOTBAR_START && playerSlot <= HOTBAR_END ? playerSlot - HOTBAR_START : -1;
    }
}
