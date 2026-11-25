package io.github.digitalisp.mapart.bot;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.WorldView.Mob;
import io.github.digitalisp.mapart.bot.WorldView.Thing;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The damage event as a {@link Hit}, and the fight's choice of target. */
class HitTest {
    private static final int SELF = 5;
    private static final Map<Integer, String> TYPES = Map.of(0, "arrow", 1, "fall", 2, "fly_into_wall",
        3, "mob_attack");
    private static final Map<Integer, Thing> ENTITIES = Map.of(
        412, new Thing(412, "pillager", 10.5, 64, -3.5),
        977, new Thing(977, "arrow", 0.5, 65, 0.5));

    private static Hit of(int entity, int type, int cause, int direct) {
        return Hit.of(entity, SELF, type, cause, direct, TYPES::get, ENTITIES::get);
    }

    @Test
    void aPillagersArrow() {
        Hit h = of(SELF, 0, 412, 977);
        assertEquals("minecraft:arrow", h.type());
        assertEquals(ENTITIES.get(412), h.cause());
        assertEquals(ENTITIES.get(977), h.direct());
        assertEquals("minecraft:arrow from pillager #412 (arrow #977)", h.describe());
    }

    @Test
    void aFallAndAWallHaveNoEntity() {
        Hit fall = of(SELF, 1, -1, -1);
        assertEquals("minecraft:fall", fall.type());
        assertNull(fall.cause());
        assertNull(fall.direct());
        assertEquals("minecraft:fall", fall.describe());
        assertEquals("minecraft:fly_into_wall", of(SELF, 2, -1, -1).type());
    }

    @Test
    void anotherEntitysDamageIsNoHitOnTheBot() {
        assertNull(of(412, 0, 7, 977), "the server sends every tracked entity's damage events");
    }

    @Test
    void unknownEntitiesAndTypesStayNamedByTheirIds() {
        Hit h = of(SELF, 9, 1234, 1234);
        assertEquals("damage type #9", h.type());
        assertEquals("", h.cause().type());
        assertEquals(1234, h.cause().id());
        assertTrue(Double.isNaN(h.cause().x()));
        assertEquals("damage type #9 from entity #1234", h.describe());
        assertEquals("minecraft:mob_attack from pillager #412", of(SELF, 3, 412, 412).describe());
        assertEquals("minecraft:arrow by arrow #977", of(SELF, 0, -1, 977).describe());
        Hit named = Hit.of(SELF, SELF, 0, -1, -1, id -> "minecraft:arrow", id -> null);
        assertEquals("minecraft:arrow", named.type(), "a namespaced name is kept");
    }

    @Test
    void theFightGoesForTheMobsThatHitTheBotFirst() {
        FightArea area = new FightArea(Set.of("pillager"), 1, 0, 1, 0, 1, 0, 0, 64, 0, 16, true);
        Mob near = new Mob("pillager", 2, 64, 0, 1);
        Mob shooter = new Mob("pillager", 9, 64, 0, 2);
        Mob far = new Mob("pillager", 40, 64, 0, 3); // outside the area
        List<Mob> all = List.of(far, shooter, near);
        assertEquals(near, area.target(all, Set.of(), 0, 64, 0).orElseThrow(), "no hit yet: the nearest");
        assertEquals(shooter, area.target(all, Set.of(2), 0, 64, 0).orElseThrow(), "the shooter");
        assertEquals(near, area.target(all, Set.of(3), 0, 64, 0).orElseThrow(), "an attacker out of the area is not");
        assertTrue(area.target(List.of(far), Set.of(3), 0, 64, 0).isEmpty());
        assertEquals(-1, new Mob("zombie", 0, 0, 0).id(), "no id known");
    }
}
