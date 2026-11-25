package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.coord.Teammates;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KillAura during the plugin's fight never hits a teammate: with the
 * owner's {@code targetPlayers} on, players are not targeted while a teammate is tracked that KillAura's own lists
 * (friends, whitelist, spectator whitelist) do not spare; a stranger or a spared teammate changes nothing, and with
 * nobody a teammate the owner's setting stands.
 */
class KillAuraTeammateTest {
    private static final UUID MATE = UUID.fromString("0f3c2a1e-6b7d-4c8e-9f10-112233445566");
    private static final UUID FRIEND = UUID.fromString("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d");
    private static final UUID STRANGER = UUID.fromString("deadbeef-0000-4000-8000-000000000001");

    private static final Predicate<UUID> FRIENDS = Set.of(FRIEND)::contains;

    @Test
    void aTeammateKillAuraWouldHitIsExposed() {
        Teammates t = new Teammates();
        t.configured(() -> List.of(MATE.toString(), FRIEND.toString(), "NamedMate"));
        assertTrue(KillAuraGuard.exposed(t, MATE, "Mate", FRIENDS), "a teammate not on KillAura's friend list");
        assertFalse(KillAuraGuard.exposed(t, FRIEND, "Friend", FRIENDS), "on the friend list: KillAura spares it");
        assertFalse(KillAuraGuard.exposed(t, STRANGER, "Stranger", FRIENDS), "a stranger is the owner's business");
        assertTrue(KillAuraGuard.exposed(t, STRANGER, "namedmate", FRIENDS), "listed by name, UUID not on the list");
        assertTrue(KillAuraGuard.exposed(t, null, "NamedMate", FRIENDS), "no UUID: the friend list cannot spare it");
        assertFalse(KillAuraGuard.exposed(new Teammates(), MATE, "Mate", FRIENDS), "nobody a teammate");
    }

    @Test
    void playersAreTargetedOnlyAsTheOwnerSetItAndNeverWhileATeammateIsExposed() {
        assertTrue(KillAuraGuard.targetPlayers(true, false), "the owner's on stands without an exposed teammate");
        assertFalse(KillAuraGuard.targetPlayers(true, true), "off while a teammate would be hit");
        assertFalse(KillAuraGuard.targetPlayers(false, false), "the owner's off stands");
        assertFalse(KillAuraGuard.targetPlayers(false, true), "never turned on");
    }
}
