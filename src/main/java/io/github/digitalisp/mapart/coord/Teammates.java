package io.github.digitalisp.mapart.coord;

import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Who is a teammate: a player listed in the plugin config ({@code teammates} in mapart.json, read live through
 * {@link #configured}). The one place the plugin asks "is this player one of ours?". A teammate is never a fight target,
 * never a threat (self-defence, the health hold's retreat), never an attacker when its hit lands on the bot, and
 * KillAura, which the plugin switches on for a fight, is kept off players while one it would hit is near.
 *
 * <p>Each entry is a UUID (with or without dashes; matched exactly) or a player name (matched in any case). A player is
 * a teammate when its UUID or its name is listed. With the list empty, the default, nobody is a teammate.
 *
 * <p>Thread-safe: the questions come from the client tick thread and the client event loop. Reads take no lock
 * (immutable snapshots behind volatile fields). Pure: no ZenithProxy globals.
 */
public final class Teammates {
    /** The process-wide instance: the engine, the world view and KillAura's guard ask it; the config feeds it. */
    private static final Teammates SHARED = new Teammates();

    /** The instance production code asks. Tests make their own ({@code new Teammates()}). */
    public static Teammates shared() {
        return SHARED;
    }

    /** Parsed entries: UUIDs, and names in lower case. Immutable. */
    private record Members(Set<UUID> uuids, Set<String> names) {
        static final Members NONE = new Members(Set.of(), Set.of());

        boolean isEmpty() {
            return uuids.isEmpty() && names.isEmpty();
        }

        boolean has(@Nullable UUID uuid, @Nullable String name) {
            if (uuid != null && uuids.contains(uuid)) return true;
            return name != null && !names.isEmpty() && names.contains(name.trim().toLowerCase(Locale.ROOT));
        }

        int size() {
            return uuids.size() + names.size();
        }

        static Members of(@Nullable Collection<String> entries) {
            if (entries == null || entries.isEmpty()) return NONE;
            Set<UUID> u = new HashSet<>();
            Set<String> n = new HashSet<>();
            for (String e : entries) {
                if (e == null || e.isBlank()) continue;
                UUID id = uuid(e);
                if (id != null) u.add(id);
                else n.add(e.trim().toLowerCase(Locale.ROOT));
            }
            return u.isEmpty() && n.isEmpty() ? NONE : new Members(Set.copyOf(u), Set.copyOf(n));
        }
    }

    /** The config list last read and what it parsed to (one field, so a reader never sees a mismatched pair). */
    private record ConfigCache(List<String> seen, Members members) {
        static final ConfigCache NONE = new ConfigCache(List.of(), Members.NONE);
    }

    private volatile Supplier<? extends List<String>> configured = List::of;
    private volatile ConfigCache config = ConfigCache.NONE;

    /**
     * The static list from the plugin config ({@code teammates}), read live on every question (the config may change
     * at run time); parsed again only when it changed. Null: no config feed.
     */
    public void configured(@Nullable Supplier<? extends List<String>> feed) {
        configured = feed == null ? List::of : feed;
        config = ConfigCache.NONE;
    }

    /** The player with this UUID and/or name (either may be null) is a teammate. False when both are null. */
    public boolean isTeammate(@Nullable UUID uuid, @Nullable String name) {
        if (uuid == null && name == null) return false;
        return configMembers().has(uuid, name);
    }

    /** Nobody is a teammate (the config list empty). */
    public boolean isEmpty() {
        return configMembers().isEmpty();
    }

    /** "3 teammates (3 from config)" or "no teammates", for log and status lines. */
    public String summary() {
        Members c = configMembers();
        int n = c.size();
        if (n == 0) return "no teammates";
        return n + (n == 1 ? " teammate (" : " teammates (") + n + " from config)";
    }

    private Members configMembers() {
        List<String> now;
        try {
            now = configured.get();
        } catch (RuntimeException e) {
            return config.members(); // the config is being replaced: the last list read
        }
        if (now == null || now.isEmpty()) return Members.NONE;
        ConfigCache c = config;
        try {
            if (now.equals(c.seen())) return c.members();
            List<String> copy = now.stream().filter(java.util.Objects::nonNull).toList();
            c = new ConfigCache(copy, Members.of(copy));
        } catch (RuntimeException e) {
            return c.members(); // changed while read (a config command on another thread): the last list read
        }
        config = c;
        return c.members();
    }

    /** {@code s} as a UUID (with dashes, or 32 hex digits without), or null when it is none (a player name). */
    static @Nullable UUID uuid(String s) {
        String t = s.trim();
        if (t.length() == 32 && t.chars().allMatch(ch -> Character.digit(ch, 16) >= 0)) {
            t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-" + t.substring(16, 20)
                + "-" + t.substring(20);
        }
        if (t.length() != 36 || t.charAt(8) != '-' || t.charAt(13) != '-' || t.charAt(18) != '-' || t.charAt(23) != '-')
            return null;
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
