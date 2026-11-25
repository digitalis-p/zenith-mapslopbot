package io.github.digitalisp.mapart.engine;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.WorldView;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.config.MapArtConfig.PlatformCfg;
import io.github.digitalisp.mapart.coord.Teammates;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * When and where the engine fights mobs (pure, unit-tested). Only while a tile is
 * active on a platform that fights ({@code fightMobs}; soul sand by default), never in IDLE, SELECT, BLOCKED, or
 * during the clear (CLEAR, WAIT_CLEAR: the bot stays on the centre structure for the presence rule; the mobs are
 * fought once the clear is over).
 */
final class Combat {
    private Combat() {
    }

    /** Blocks below the art layer and above it that count as the platform's (the floor, the centre structure). */
    static final int BELOW = 8;
    static final int ABOVE = 12;

    static boolean phaseFights(Phase phase) {
        return switch (phase) {
            case IDLE, SELECT, CLEAR, WAIT_CLEAR, BLOCKED -> false;
            default -> true;
        };
    }

    /** The fought entity types: {@code combat.mobs} (type names), and creeper while hit and run is on. */
    static Set<String> types(MapArtConfig.CombatCfg cfg) {
        Set<String> types = new LinkedHashSet<>();
        for (String id : cfg.mobs) {
            String t = FightArea.typeName(id);
            if (!t.isEmpty()) types.add(t);
        }
        if (cfg.creeperHitAndRun) types.add("creeper");
        return Set.copyOf(types);
    }

    /** The fight area on {@code p} for a bot at (x, y, z), or null when this platform does not fight now. */
    static @Nullable FightArea area(MapArtConfig.CombatCfg cfg, @Nullable PlatformCfg p, Phase phase,
                                    double x, double y, double z) {
        if (cfg == null || !cfg.enabled || p == null || !p.fightsMobs() || !phaseFights(phase)) return null;
        Set<String> types = types(cfg);
        if (types.isEmpty()) return null;
        int r = Math.max(0, cfg.radius);
        return new FightArea(types, p.minX - r, p.minX + TileArt.SIZE - 1 + r, p.artY - BELOW,
            p.artY + ABOVE, p.minZ - r, p.minZ + TileArt.SIZE - 1 + r, x, y, z, r, true);
    }

    /**
     * Self-defence ({@code combat.selfDefense}): the listed mobs within {@code radius} of the bot at (x, y, z), wherever
     * it is and whatever the platform's {@code fightMobs}, in every phase the engine acts in (IDLE and SELECT too: a
     * requested stock scan walks the storage in IDLE) except during the clear (CLEAR, WAIT_CLEAR: the bot stays on the
     * centre structure) and BLOCKED (stopped, the engine asks the phase-free form below); null when combat or
     * self-defence is off. The engine asks for it only while a listed mob hits the bot ({@link #attacker}; live
     * 2026-10-12: pillagers on the moss walkway).
     */
    static @Nullable FightArea defenceArea(MapArtConfig.CombatCfg cfg, Phase phase, double x, double y, double z) {
        if (phase == Phase.CLEAR || phase == Phase.WAIT_CLEAR || phase == Phase.BLOCKED) return null;
        return defenceArea(cfg, x, y, z, true);
    }

    /**
     * The self-defence area whatever the phase (the engine stopped: paused, BLOCKED, backing off; the caller keeps the
     * clear out): the listed mobs within {@code radius} of (x, y, z); {@code chase} false fights only what comes within
     * reach (safety-locked: the bot does not walk). Null when combat or self-defence is off.
     */
    static @Nullable FightArea defenceArea(MapArtConfig.CombatCfg cfg, double x, double y, double z, boolean chase) {
        if (cfg == null || !cfg.enabled || !cfg.selfDefense) return null;
        Set<String> types = types(cfg);
        if (types.isEmpty()) return null;
        // an empty box: only the sphere around the bot counts
        return new FightArea(types, 1, 0, 1, 0, 1, 0, x, y, z, Math.max(0, cfg.radius), chase);
    }

    /**
     * The listed mob ({@code listed}: type names as the entity cache gives them) behind a hit on the bot, or null when
     * none is: the damage's cause (the pillager that shot the arrow, the zombie that struck), else its direct source (a
     * mob's blow with no cause given). A fall, flying into a wall, fire, drowning or starving has neither; a player, a
     * mob not listed (a skeleton's arrow), or an entity the client does not know (type "") is none either. Live
     * 2026-10-13: falls and elytra wall hits while the owner flew the account counted as hits and started a fight with two
     * harmless pillagers in a water channel.
     */
    static WorldView.@Nullable Thing attacker(Hit hit, Set<String> listed) {
        if (hit.cause() != null && listed.contains(hit.cause().type())) return hit.cause();
        if (hit.direct() != null && listed.contains(hit.direct().type())) return hit.direct();
        return null;
    }

    /**
     * {@link #attacker(Hit, Set)}, never a teammate: a hit whose cause, or
     * with no cause its direct entity, is a teammate has no attacker, even with {@code player} among the listed mobs.
     */
    static WorldView.@Nullable Thing attacker(Hit hit, Set<String> listed, Teammates teammates) {
        return byTeammate(hit, teammates) ? null : attacker(hit, listed);
    }

    /** The hit came from a teammate: its cause, or with no cause its direct entity, is one. */
    static boolean byTeammate(Hit hit, Teammates teammates) {
        WorldView.Thing from = hit.cause() != null ? hit.cause() : hit.direct();
        return from != null && teammate(teammates, from);
    }

    /** {@code m} is a teammate (a player of the config list, {@link Teammates}): never a fight target or a threat. */
    static boolean teammate(Teammates teammates, WorldView.Mob m) {
        return (m.uuid() != null || m.name() != null) && teammates.isTeammate(m.uuid(), m.name());
    }

    /** {@code t} is a teammate ({@link Teammates}). */
    static boolean teammate(Teammates teammates, WorldView.Thing t) {
        return (t.uuid() != null || t.name() != null) && teammates.isTeammate(t.uuid(), t.name());
    }

    /** {@code mobs} without the teammates among them; {@code mobs} itself when there are none. */
    static List<WorldView.Mob> withoutTeammates(List<WorldView.Mob> mobs, Teammates teammates) {
        if (mobs.isEmpty() || teammates.isEmpty()) return mobs;
        return mobs.stream().filter(m -> !teammate(teammates, m)).toList();
    }

    /** "2 pillagers, 1 witch" for log and Discord lines. */
    static String describe(List<WorldView.Mob> mobs) {
        Map<String, Integer> n = new TreeMap<>();
        for (WorldView.Mob m : mobs) n.merge(m.type(), 1, Integer::sum);
        StringBuilder sb = new StringBuilder();
        for (var e : n.entrySet()) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(e.getValue()).append(' ').append(e.getKey().replace('_', ' ')).append(e.getValue() == 1 ? "" : "s");
        }
        return sb.toString();
    }
}
