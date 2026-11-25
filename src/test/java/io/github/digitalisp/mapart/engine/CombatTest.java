package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.FightArea;
import io.github.digitalisp.mapart.bot.Hit;
import io.github.digitalisp.mapart.bot.WorldView.Mob;
import io.github.digitalisp.mapart.bot.WorldView.Thing;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Combat (the owner 2026-10-12: pillagers spawn on the soul sand platform): a listed mob in
 * the fight area parks the running task, the modules come back with KillAura on, the fight runs until the area is
 * quiet, then the modules are suppressed again and the task carries on; never on moss by default, never during the
 * clear. The Sim's soul sand tile is x 192..319, z -64..63, art y 64.
 */
class CombatTest {
    private static final int LONG = 60_000;

    private static Sim sim() {
        Sim sim = new Sim();
        sim.modules.state.put("KillAura", false); // the owner's KillAura is off (as live): the fight switches it on
        sim.bot.killAuraOn = () -> sim.modules.isEnabled("KillAura");
        return sim;
    }

    @Test
    void pillagersMidBuildAreFoughtThenTheMapIsFinished() {
        Sim sim = sim();
        TileArt art = Sim.stripes(8, 0);
        Job job = sim.addJob("j0001", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD && sim.bot.history.stream()
            .anyMatch(h -> h.startsWith("placeLane")), LONG), sim.state());
        sim.world.mobList.add(new Mob("pillager", 250.5, 64, 20.5));
        sim.world.mobList.add(new Mob("pillager", 300.5, 64, -40.5));
        sim.world.mobList.add(new Mob("cow", 250.5, 64, 21.5)); // not listed: ignored
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.fights.size(), "one fight for both pillagers");
        assertEquals(List.of("KillAura=true"), sim.bot.fightModules, "KillAura on during the fight");
        assertEquals(1, sim.world.mobList.size(), "both pillagers dead, the cow left alone");
        assertEquals(1, sim.notifier.of("info").stream().filter(m -> m.title().equals("Fighting mobs")).count());
        assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.body().contains("2 pillagers")), sim.state());
        assertTrue(sim.matchesArt(sim.soul, art) || sim.output().maps.size() == 1, "the map was finished");
        assertEquals(1, sim.output().maps.size());
        assertFalse(sim.modules.isEnabled("KillAura"), "KillAura back off afterwards (the owner had it off)");
        int park = sim.bot.history.indexOf(sim.bot.history.stream().filter(h -> h.startsWith("fight")).findFirst().orElseThrow());
        assertTrue(sim.bot.history.subList(park, sim.bot.history.size()).stream().anyMatch(h -> h.startsWith("placeLane")
            || h.startsWith("fill") || h.startsWith("createMap")), "work went on after the fight");
    }

    @Test
    void aZombieMidRestockIsFoughtThenTheRestockCarriesOn() {
        Sim sim = sim();
        TileArt art = Sim.stripes(8, 0);
        Job job = sim.addJob("j0002", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.RESTOCK, LONG), sim.state());
        sim.world.mobList.add(new Mob("zombie", 200.5, 64, -60.5));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertEquals(1, sim.bot.fights.size());
        assertTrue(sim.world.mobList.isEmpty());
        assertEquals(1, sim.output().maps.size());
    }

    @Test
    void mossDoesNotFightByDefault() {
        Sim sim = sim();
        TileArt art = Sim.stripes(4, 0);
        Job job = sim.addJob("j0003", "Moss", 1, 1, List.of("moss"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.world.mobList.add(new Mob("pillager", 0.5, 64, 0.5));
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
        assertTrue(sim.bot.fights.isEmpty());
    }

    @Test
    void noFightDuringTheClearOnlyAfterIt() {
        Sim sim = sim();
        TileArt art = Sim.stripes(4, 0);
        // two maps: the work goes on after the first clear (an idle bot does not fight)
        Job job = sim.addJob("j0004", "Soul", 2, 1, List.of("soul"), (x, z) -> art);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.WAIT_CLEAR, LONG), sim.state());
        sim.world.mobList.add(new Mob("witch", 260.5, 64, 5.5));
        assertTrue(sim.runUntil(() -> sim.phase() != Phase.WAIT_CLEAR, LONG), sim.state());
        assertTrue(sim.bot.fights.isEmpty(), "not while the machines run");
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE && sim.world.mobList.isEmpty(), LONG), sim.state());
    }

    @Test
    void theAreaAndThePhaseRules() {
        MapArtConfig.CombatCfg cfg = new MapArtConfig.CombatCfg();
        Sim sim = new Sim();
        MapArtConfig.PlatformCfg soul = sim.soul, moss = sim.moss;
        assertTrue(soul.fightsMobs());
        assertFalse(moss.fightsMobs());
        moss.fightMobs = true;
        assertNotNull(Combat.area(cfg, moss, Phase.BUILD, 0, 64, 0), "on when set");
        assertNull(Combat.area(cfg, soul, Phase.WAIT_CLEAR, 256, 70, 0));
        assertNull(Combat.area(cfg, soul, Phase.CLEAR, 256, 70, 0));
        assertNull(Combat.area(cfg, soul, Phase.IDLE, 256, 70, 0));
        cfg.enabled = false;
        assertNull(Combat.area(cfg, soul, Phase.BUILD, 256, 64, 0), "global kill switch");
        cfg.enabled = true;
        FightArea a = Combat.area(cfg, soul, Phase.RESTOCK, 190, 64, 100);
        assertNotNull(a);
        assertEquals(Set.of("pillager", "vindicator", "ravager", "evoker", "witch", "illusioner", "zombie", "creeper"),
            a.types(), "creeper joins through hit and run");
        cfg.creeperHitAndRun = false;
        a = Combat.area(cfg, soul, Phase.RESTOCK, 190, 64, 100);
        assertEquals(Set.of("pillager", "vindicator", "ravager", "evoker", "witch", "illusioner", "zombie"), a.types(),
            "hit and run off: creepers ignored as before");
        assertTrue(a.contains(new Mob("pillager", 192 - 16 + 0.5, 64, 0.5)), "16 beside the tile");
        assertFalse(a.contains(new Mob("pillager", 192 - 17 + 0.5, 64, 0.5)));
        assertTrue(a.contains(new Mob("pillager", 190.5, 64, 110.5)), "near the bot, far from the tile");
        assertFalse(a.contains(new Mob("pillager", 256.5, 64 + 13, 0.5)), "well above the center structure");
        assertFalse(a.contains(new Mob("creeper", 256.5, 64, 0.5)), "not listed with hit and run off");
        cfg.creeperHitAndRun = true;
        assertTrue(Combat.area(cfg, soul, Phase.RESTOCK, 190, 64, 100).contains(new Mob("creeper", 256.5, 64, 0.5)));
        assertNull(Combat.area(cfg, soul, Phase.WAIT_CLEAR, 256, 70, 0), "never during the clear");
        assertTrue(Combat.defenceArea(cfg, 0, 64, 0, true).types().contains("creeper"));
        assertEquals("2 pillagers, 1 witch", Combat.describe(List.of(new Mob("pillager", 0, 0, 0),
            new Mob("pillager", 0, 0, 0), new Mob("witch", 0, 0, 0))));
        assertEquals("pillager", FightArea.typeName("minecraft:Pillager"));
    }

    @Test
    void aCreeperStartsAFightOnlyWithHitAndRun() {
        for (boolean on : new boolean[]{false, true}) {
            Sim sim = sim();
            sim.config.combat.creeperHitAndRun = on;
            TileArt art = Sim.stripes(8, 0);
            Job job = sim.addJob("j0009", "Soul", 1, 1, List.of("soul"), (x, z) -> art);
            sim.start();
            assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
            sim.world.mobList.add(new Mob("creeper", 250.5, 64, 20.5));
            assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, LONG), sim.state());
            if (on) {
                assertEquals(1, sim.bot.fights.size(), "on: the creeper starts a fight");
                assertTrue(sim.bot.fights.getFirst().types().contains("creeper"));
                assertTrue(sim.world.mobList.isEmpty(), "the creeper is dead");
                assertTrue(sim.notifier.of("info").stream().anyMatch(m -> m.body().contains("1 creeper")), sim.state());
            } else {
                assertTrue(sim.bot.fights.isEmpty(), "off: the creeper is ignored as before");
                assertEquals(1, sim.world.mobList.size());
            }
        }
    }

    @Test
    void onlyAListedMobBehindTheDamageIsAnAttacker() {
        Set<String> listed = Set.of("pillager", "zombie");
        Thing pillager = new Thing(412, "pillager", 10.5, 64, -3.5);
        Thing arrow = new Thing(977, "arrow", 0.5, 65, 0.5);
        assertEquals(pillager, Combat.attacker(new Hit("minecraft:arrow", pillager, arrow), listed), "its arrow");
        Thing zombie = new Thing(13, "zombie", 1, 64, 0);
        assertEquals(zombie, Combat.attacker(new Hit("minecraft:mob_attack", zombie, zombie), listed), "its blow");
        assertEquals(zombie, Combat.attacker(new Hit("minecraft:mob_attack", null, zombie), listed), "no cause given");
        assertNull(Combat.attacker(new Hit("minecraft:fall", null, null), listed));
        assertNull(Combat.attacker(new Hit("minecraft:fly_into_wall", null, null), listed));
        assertNull(Combat.attacker(new Hit("minecraft:on_fire", null, null), listed));
        Thing player = new Thing(7, "player", 1, 64, 0);
        assertNull(Combat.attacker(new Hit("minecraft:player_attack", player, player), listed), "a player");
        Thing skeleton = new Thing(8, "skeleton", 9, 64, 0);
        assertNull(Combat.attacker(new Hit("minecraft:arrow", skeleton, arrow), listed), "a mob not listed");
        assertNull(Combat.attacker(new Hit("minecraft:arrow", null, arrow), listed), "an arrow nobody shot");
        Thing unknown = new Thing(9, "", Double.NaN, Double.NaN, Double.NaN);
        assertNull(Combat.attacker(new Hit("minecraft:arrow", unknown, arrow), listed), "not in the entity cache");
    }
}
