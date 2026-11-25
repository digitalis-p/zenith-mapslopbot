package io.github.digitalisp.mapart.bot;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView.Mob;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobMemoryTest {
    private static final Mob CHANNEL = new Mob("pillager", 10.5, 58, 20.5);

    @Test
    void aRememberedMobIsLeftOutAndFollowedAsItDrifts() {
        MobMemory m = new MobMemory();
        m.add(CHANNEL);
        Mob drifted = new Mob("pillager", 12.5, 58, 20.5);
        Mob other = new Mob("pillager", 30.5, 64, 20.5);
        assertEquals(List.of(other), m.without(List.of(drifted, other)));
        Mob further = new Mob("pillager", 15, 58, 20.5); // 2.5 from where it drifted to, 4.5 from where it was
        assertEquals(List.of(), m.without(List.of(further)));
        assertEquals(1, m.size());
    }

    @Test
    void aRecordNoMobMatchedForMoreThanTwoLooksIsDropped() {
        MobMemory m = new MobMemory();
        m.add(CHANNEL);
        for (int i = 0; i < MobMemory.MAX_MISSES; i++) m.without(List.of());
        assertEquals(1, m.size(), "a look or two without it (out of the entity cache for a moment) keeps it");
        assertEquals(List.of(), m.without(List.of(CHANNEL)), "seen again: the misses start over");
        for (int i = 0; i < MobMemory.MAX_MISSES; i++) m.without(List.of());
        assertEquals(1, m.size());
        m.without(List.of());
        assertTrue(m.isEmpty(), "gone (killed, despawned): forgotten");
        Mob newcomer = new Mob("pillager", 11.5, 58, 20.5);
        assertEquals(List.of(newcomer), m.without(List.of(newcomer)), "a new mob at its spot is not hidden");
    }
}
