package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.model.TileArt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modules the engine switches off while it works: {@code build.disableModules} plus the implicit ones. A config saved
 * before AutoTotem joined the default list (live gate C) still gets AutoTotem off while airplacing.
 */
class ModuleSuppressionTest {
    private static final int LONG = 80_000;
    /** build.disableModules as saved before AutoTotem was in the default list. */
    private static final List<String> OLD_DEFAULT = List.of(
        "AntiAFK", "Spook", "AutoFish", "KillAura", "SpawnPatrol", "AutoDrop", "AutoMend", "AutoArmor");

    private static Sim sim(String placeMode, boolean autoTotemOn) {
        Sim sim = new Sim();
        sim.config.build.placeMode = placeMode;
        sim.config.build.disableModules = new ArrayList<>(OLD_DEFAULT);
        sim.modules.state.put("AutoTotem", autoTotemOn);
        TileArt art = Sim.stripes(64, 4);
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(Sim.RED, 1728));
        sim.bot.addContainer(new Pos(-88, 61, 100), Map.of(Sim.BLACK, 1728));
        sim.addJob("j0100", "Long", 1, 1, List.of("moss"), (x, z) -> art);
        return sim;
    }

    @Test
    void autoTotemOffWhileAirplacingEvenIfTheSavedListLacksIt() {
        Sim sim = sim("AIRPLACE", true);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.run(5);
        assertFalse(sim.modules.isEnabled("AutoTotem"), "AutoTotem off while airplacing");
        assertFalse(sim.modules.isEnabled("AntiAFK"), "the saved list still applies");

        sim.engine.pause("test");
        sim.run(2);
        assertTrue(sim.modules.isEnabled("AutoTotem"), "restored while paused");
        sim.engine.resume();
        sim.run(60);
        assertFalse(sim.modules.isEnabled("AutoTotem"), "off again after resume");
        sim.engine.onBotTickStopped();
        assertTrue(sim.modules.isEnabled("AutoTotem"), "restored when the bot ticks stop");
        sim.start();
        sim.run(60);
        sim.engine.shutdown();
        assertTrue(sim.modules.isEnabled("AutoTotem"), "restored on shutdown");
    }

    @Test
    void autoTotemThatWasOffStaysOff() {
        Sim sim = sim("AIRPLACE", false);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.engine.pause("test");
        sim.run(2);
        assertFalse(sim.modules.isEnabled("AutoTotem"), "only modules the engine switched off are switched back on");
        assertFalse(sim.modules.log.contains("AutoTotem=true"), sim.modules.log.toString());
    }

    @Test
    void lookModeLeavesAutoTotemToTheList() {
        Sim sim = sim("LOOK", true);
        sim.start();
        assertTrue(sim.runUntil(() -> sim.phase() == Phase.BUILD, LONG), sim.state());
        sim.run(200);
        assertTrue(sim.modules.isEnabled("AutoTotem"), "LOOK mode: not in the list, not touched");
        assertFalse(sim.modules.isEnabled("AntiAFK"));

        Sim listed = sim("LOOK", true);
        listed.config.build.disableModules.add("AutoTotem");
        listed.start();
        assertTrue(listed.runUntil(() -> listed.phase() == Phase.BUILD, LONG), listed.state());
        listed.run(5);
        assertFalse(listed.modules.isEnabled("AutoTotem"), "listed: off in LOOK mode too");
    }
}
