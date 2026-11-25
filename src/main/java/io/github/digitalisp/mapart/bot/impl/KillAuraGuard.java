package io.github.digitalisp.mapart.bot.impl;

import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.entity.EntityPlayer;
import com.zenith.util.config.Config;
import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.coord.Teammates;

import java.util.UUID;
import java.util.function.Predicate;

import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.PLAYER_LISTS;

/**
 * ZenithProxy KillAura settings for a fight ({@link CombatTask}), restored afterwards (reference-counted like
 * {@link PathingGuard}): hostile mobs targeted, the nearest first, also those not (yet) aggressive (a pillager keeps
 * its distance), and no weapon switching of its own: the fight holds a weapon above the durability floor itself, and
 * KillAura's switch would take any iron-or-better sword or axe however worn. Neutral mobs, custom targets, the attack
 * delay and raycast stay as the owner set them; so do players, except that a teammate is never a target:
 * with {@code targetPlayers} on and a teammate ({@link Teammates}) tracked
 * that KillAura's own lists do not spare (its friends, whitelist and spectator whitelist), players are not targeted
 * until it is gone. With nobody a teammate (the single bot) this never applies.
 */
final class KillAuraGuard {
    private KillAuraGuard() {
    }

    private record Saved(boolean switchWeapon, boolean hostile, boolean onlyAggressive,
                         Config.Client.Extra.KillAura.Priority priority, boolean players) { }

    /** Hostile mobs are attacked (false: the hand holds nothing safe to hit with, so KillAura targets none). */
    private static volatile boolean attack = true;
    /** This guard turned KillAura's {@code targetPlayers} off for a teammate (restored with the rest). */
    private static volatile boolean playersOff;

    private static final RefCountedOverride<Saved> OVERRIDE = new RefCountedOverride<>(
        () -> {
            var k = CONFIG.client.extra.killAura;
            return new Saved(k.switchWeapon, k.targetHostileMobs, k.onlyHostileAggressive, k.priority, k.targetPlayers);
        },
        () -> {
            var k = CONFIG.client.extra.killAura;
            k.switchWeapon = false;
            k.targetHostileMobs = attack;
            k.onlyHostileAggressive = false;
            k.priority = Config.Client.Extra.KillAura.Priority.NEAREST;
            // players: written only to keep a teammate safe, and back when it is gone (never for a single bot)
            boolean want = targetPlayers();
            if (!want && k.targetPlayers) {
                k.targetPlayers = false;
                playersOff = true;
            } else if (want && playersOff) {
                k.targetPlayers = true;
                playersOff = false;
            }
        },
        s -> {
            var k = CONFIG.client.extra.killAura;
            k.switchWeapon = s.switchWeapon();
            k.targetHostileMobs = s.hostile();
            k.onlyHostileAggressive = s.onlyAggressive();
            k.priority = s.priority();
            if (playersOff) k.targetPlayers = s.players();
            playersOff = false;
        });

    static void attack(boolean on) {
        if (attack == on) return;
        attack = on;
        OVERRIDE.enforce();
    }

    static RefCountedOverride.Handle acquire() {
        return OVERRIDE.acquire();
    }

    static void enforce() {
        OVERRIDE.enforce();
    }

    /** KillAura's {@code targetPlayers} while the override holds: the owner's, off while a teammate would be hit. */
    private static boolean targetPlayers() {
        Saved s = OVERRIDE.saved();
        boolean owner = s != null ? s.players() : CONFIG.client.extra.killAura.targetPlayers;
        return targetPlayers(owner, owner && teammateExposed());
    }

    /** Pure: players are targeted only as the owner set it, and never while a teammate is exposed. */
    static boolean targetPlayers(boolean owner, boolean teammateExposed) {
        return owner && !teammateExposed;
    }

    /** A teammate KillAura would hit is tracked: a player of the entity cache, not the bot, that its lists do not spare. */
    private static boolean teammateExposed() {
        Teammates t = Teammates.shared();
        if (t.isEmpty()) return false;
        var cache = CACHE.getEntityCache();
        if (cache == null) return false;
        Predicate<UUID> spared = KillAuraGuard::spared;
        for (Entity e : cache.getEntities().values()) {
            if (!(e instanceof EntityPlayer p) || p.isSelfPlayer() || e.isRemoved()) continue;
            if (exposed(t, e.getUuid(), ZenithWorldView.playerName(e.getUuid()), spared)) return true;
        }
        return false;
    }

    /** KillAura 3.7.2 skips players on its friends list, the whitelist and the spectator whitelist (validTarget). */
    private static boolean spared(UUID uuid) {
        try {
            return PLAYER_LISTS.getFriendsList().contains(uuid) || PLAYER_LISTS.getWhitelist().contains(uuid)
                || PLAYER_LISTS.getSpectatorWhitelist().contains(uuid);
        } catch (RuntimeException e) {
            return false; // lists not loaded: assume KillAura would hit it
        }
    }

    /** Pure: the player ({@code uuid}, {@code name}) is a teammate that {@code spared} (KillAura's own lists) misses. */
    static boolean exposed(Teammates teammates, @Nullable UUID uuid, @Nullable String name, Predicate<UUID> spared) {
        if (!teammates.isTeammate(uuid, name)) return false;
        return uuid == null || !spared.test(uuid);
    }
}
