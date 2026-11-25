package io.github.digitalisp.mapart.model;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A block state as written in a structure NBT palette: namespaced id plus optional properties.
 * Properties are kept sorted so equal states compare equal and print identically.
 * CONTRACT (lead-owned).
 */
public record BlockSpec(String id, SortedMap<String, String> props) {
    public static final BlockSpec AIR = new BlockSpec("minecraft:air", new TreeMap<>());

    public BlockSpec {
        if (!id.contains(":")) id = "minecraft:" + id;
        props = Collections.unmodifiableSortedMap(new TreeMap<>(props));
    }

    public static BlockSpec of(String id) {
        return of(id, Map.of());
    }

    public static BlockSpec of(String id, Map<String, String> props) {
        return new BlockSpec(id, new TreeMap<>(props));
    }

    /** Parses "minecraft:oak_log[axis=y]" or "oak_log". */
    public static BlockSpec parse(String state) {
        int b = state.indexOf('[');
        if (b < 0) return of(state.trim());
        String id = state.substring(0, b).trim();
        String body = state.substring(b + 1, state.lastIndexOf(']'));
        TreeMap<String, String> props = new TreeMap<>();
        for (String kv : body.split(",")) {
            if (kv.isBlank()) continue;
            String[] p = kv.split("=", 2);
            props.put(p[0].trim(), p.length > 1 ? p[1].trim() : "");
        }
        return new BlockSpec(id, props);
    }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }

    /** Canonical state string, e.g. "minecraft:oak_log[axis=y]". */
    @Override
    public String toString() {
        if (props.isEmpty()) return id;
        StringBuilder sb = new StringBuilder(id).append('[');
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
