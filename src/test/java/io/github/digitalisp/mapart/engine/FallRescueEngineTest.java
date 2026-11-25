package io.github.digitalisp.mapart.engine;

import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.FallRescue;
import io.github.digitalisp.mapart.engine.SimFakes.RecordingNotifier.Msg;
import io.github.digitalisp.mapart.model.Job;
import io.github.digitalisp.mapart.model.JobStatus;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine's fall rescue ({@link FallWatch}): the Sim's moss platform (tile x and
 * z -64..63, art layer y64) stands in for the live soul sand tile; the channel is the column x -65 just west of its edge
 * (the gap of the live 2026-10-13 fall), water in the feet cell three blocks under the art layer.
 */
class FallRescueEngineTest {
    private static final Pos FLOOR = new Pos(-60, 64, -30);
    private static final Pos CHANNEL = new Pos(-65, 61, -30);
    private static final String NETHERRACK = "minecraft:netherrack";

    private static Sim onTheFloor() {
        Sim sim = new Sim();
        sim.start();
        sim.world.moveTo(FLOOR);
        sim.run(5);
        return sim;
    }

    private static void fall(Sim sim) {
        sim.world.moveTo(CHANNEL);
        sim.world.fluid.add(CHANNEL);
    }

    @Test
    void aFallIntoTheChannelIsRescuedAndTheEngineCarriesOn() {
        Sim sim = onTheFloor();
        fall(sim);
        sim.run(FallWatch.BELOW_TICKS - 2);
        assertTrue(sim.bot.fallRescues.isEmpty(), "not before " + FallWatch.BELOW_TICKS + " ticks below the floor");
        sim.run(40);
        assertEquals(1, sim.bot.fallRescues.size(), sim.state());
        FallRescue r = sim.bot.fallRescues.getFirst();
        assertEquals(CHANNEL, r.at());
        assertEquals(64, r.floorY(), "back to the art layer's level");
        assertEquals(BuildEngine.FALL_RESCUE_TICKS, r.maxTicks());
        assertTrue(r.why().startsWith("feet at y61.0, 3.0 below the art layer of platform moss (y64), in water"), r.why());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertFalse(sim.notifier.has("alert", "Bot fell"), sim.state());
        assertEquals(64, sim.world.playerBlock().y(), "out on the floor");
        sim.run(100);
        assertEquals(1, sim.bot.fallRescues.size(), "once out, no second rescue");
    }

    @Test
    void aRescueThatFailsIsTriedAgainAfterAWaitWithOneAlert() {
        Sim sim = onTheFloor();
        sim.bot.fallRescueFailure = r -> sim.bot.fallRescues.size() == 1 ? "no way out from -65,61,-30: 4 exits tried" : null;
        fall(sim);
        sim.run(60);
        // the next try starts at once and holds the bot's place for the wait (a plain wait lets a channel's current
        // carry it off)
        assertEquals(2, sim.bot.fallRescues.size(), sim.state());
        assertEquals(BuildEngine.FALL_RETRY_SECONDS[0] * 20, sim.bot.fallRescues.get(1).holdTicks());
        assertEquals(61, sim.world.playerBlock().y(), "still in the channel: " + sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.state());
        Msg a = alerts.getFirst();
        assertEquals("Bot fell", a.title());
        assertTrue(a.body().contains("The bot fell at -65,61,-30 (feet at y61.0"), a.body());
        assertTrue(a.body().contains("did not get out yet: rescueFall from -65,61,-30: no way out"), a.body());
        assertTrue(a.body().contains("It tries again in " + BuildEngine.FALL_RETRY_SECONDS[0] + " s"), a.body());
        sim.run(BuildEngine.FALL_RETRY_SECONDS[0] * 20 + 60);
        assertEquals(2, sim.bot.fallRescues.size(), "the second try: " + sim.state());
        assertEquals(64, sim.world.playerBlock().y(), "out on the floor");
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertEquals(1, sim.notifier.of("alert").size(), "no second alert: " + sim.state());
        sim.run(200);
        assertEquals(2, sim.bot.fallRescues.size(), "once out, no further try");
    }

    @Test
    void onDryGroundTriesThatGetItNoHigherGoBlockedWithABotFellAlert() {
        Sim sim = onTheFloor();
        // a dry hole: the first try's end finds no water (nothing would carry a bot left to itself off)
        sim.bot.fallRescueFailure = r -> {
            sim.world.fluid.remove(sim.world.playerBlock());
            return "no way out from -65,61,-30: 4 exits tried";
        };
        sim.bot.fallRescueOwn = Map.of(new Pos(-65, 61, -29), "minecraft:netherrack");
        fall(sim);
        int waits = 0;
        for (int i = 0; i < BuildEngine.MAX_FALL_TRIES - 1; i++) waits += BuildEngine.FALL_RETRY_SECONDS[i];
        sim.run(waits * 20 + BuildEngine.MAX_FALL_TRIES * 40 + 60);
        assertEquals(BuildEngine.MAX_FALL_TRIES, sim.bot.fallRescues.size(), sim.state());
        assertEquals(Phase.BLOCKED, sim.phase(), sim.state());
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(2, alerts.size(), "the first failure and the BLOCKED: " + sim.state());
        Msg a = alerts.getLast();
        assertEquals("Bot fell", a.title());
        assertTrue(a.body().contains("could not get out: rescueFall from -65,61,-30: no way out"), a.body());
        assertTrue(a.body().contains("(" + BuildEngine.MAX_FALL_TRIES + " tries, the last " + BuildEngine.MAX_FALL_TRIES
            + " no higher)"), a.body());
        assertTrue(a.body().contains("(no disconnect)"), a.body());
        assertTrue(a.body().contains("Blocks the rescue placed still stand at -65,61,-29 netherrack"), a.body());
        sim.run(400);
        assertEquals(2, sim.notifier.of("alert").size(), "BLOCKED: no further alert");
        assertEquals(BuildEngine.MAX_FALL_TRIES, sim.bot.fallRescues.size());
    }

    @Test
    void inWaterTriesThatGetItNoHigherHoldItsPlaceAndGoOnWithASecondAlert() {
        Sim sim = onTheFloor();
        sim.bot.fallRescueFailure = r -> "no way out from -65,61,-30: 4 exits tried";
        fall(sim);
        int waits = 0;
        for (int i = 0; i < BuildEngine.MAX_FALL_TRIES - 1; i++) waits += BuildEngine.FALL_RETRY_SECONDS[i];
        sim.run(waits * 20 + BuildEngine.MAX_FALL_TRIES * 40 + 60);
        // left to itself a bot in a channel drifts with the current to its end (and down the drain at the west one's)
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertEquals(BuildEngine.MAX_FALL_TRIES + 1, sim.bot.fallRescues.size(), sim.state());
        int last = BuildEngine.FALL_RETRY_SECONDS[BuildEngine.FALL_RETRY_SECONDS.length - 1];
        assertEquals(last * 20, sim.bot.fallRescues.getLast().holdTicks(), "it holds its place until the next try");
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(2, alerts.size(), "the first failure and the hold: " + sim.state());
        Msg a = alerts.getLast();
        assertEquals("Bot fell", a.title());
        assertTrue(a.body().contains("could not get out: rescueFall from -65,61,-30: no way out"), a.body());
        assertTrue(a.body().contains("(" + BuildEngine.MAX_FALL_TRIES + " tries, the last " + BuildEngine.MAX_FALL_TRIES
            + " no higher)"), a.body());
        assertTrue(a.body().contains("holds its place"), a.body());
        sim.run(last * 20 + 60);
        assertEquals(BuildEngine.MAX_FALL_TRIES + 2, sim.bot.fallRescues.size(), "it tries on: " + sim.state());
        assertEquals(2, sim.notifier.of("alert").size(), "no further alert");
        // the owner gets it out by hand: the next try finds it out and the engine carries on
        sim.bot.fallRescueFailure = r -> null;
        sim.run(last * 20 + 60);
        assertEquals(64, sim.world.playerBlock().y(), "out on the floor: " + sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
    }

    @Test
    void triesThatGetItHigherGoOn() {
        Sim sim = onTheFloor();
        // each failed try leaves the bot a block higher in the channel (a climb that got part of the way)
        sim.bot.fallRescueFailure = r -> {
            if (sim.bot.fallRescues.size() > BuildEngine.MAX_FALL_TRIES + 1) return null;
            sim.world.moveTo(r.at().offset(0, 1, 0));
            return "the climb gave up";
        };
        fall(sim);
        int waits = 0;
        for (int i = 0; i <= BuildEngine.MAX_FALL_TRIES; i++)
            waits += BuildEngine.FALL_RETRY_SECONDS[Math.min(i, BuildEngine.FALL_RETRY_SECONDS.length - 1)];
        sim.run(waits * 20 + (BuildEngine.MAX_FALL_TRIES + 2) * 40 + 60);
        assertEquals(BuildEngine.MAX_FALL_TRIES + 2, sim.bot.fallRescues.size(), sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertEquals(64, sim.world.playerBlock().y(), "out on the floor");
    }

    @Test
    void aBotBobbingHigherInTheWaterIsNoProgress() {
        Sim sim = onTheFloor();
        // each failed try ends with the feet a block higher, but swimming: it never stood higher than the channel's floor
        sim.bot.fallRescueStandY = 61;
        sim.bot.fallRescueFailure = r -> {
            sim.world.moveTo(r.at().offset(0, 1, 0));
            return "4 exits tried";
        };
        fall(sim);
        int waits = 0;
        for (int i = 0; i < BuildEngine.MAX_FALL_TRIES - 1; i++) waits += BuildEngine.FALL_RETRY_SECONDS[i];
        sim.run(waits * 20 + BuildEngine.MAX_FALL_TRIES * 40 + 60);
        // no progress: after the tries the "could not get out" alert (with progress there would be only the first)
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(2, alerts.size(), sim.state());
        assertTrue(alerts.getLast().body().contains("could not get out"), alerts.getLast().body());
    }

    @Test
    void blocksAFailedTryLeftGoToTheNextTryAndIntoTheCouldNotGetOutAlert() {
        Sim sim = onTheFloor();
        sim.bot.fallRescueFailure = r -> "the climb needs 2 block(s) and the bot carries only 1 that suits";
        sim.bot.fallRescueOwn = Map.of(new Pos(-65, 61, -29), "minecraft:netherrack");
        fall(sim);
        sim.run(60);
        assertEquals(2, sim.bot.fallRescues.size(), sim.state());
        assertTrue(sim.bot.fallRescues.getFirst().own().isEmpty(), "a new fall starts with none");
        assertEquals(Map.of(new Pos(-65, 61, -29), "minecraft:netherrack"), sim.bot.fallRescues.get(1).own(),
            "the next try takes them over");
        int waits = 0;
        for (int i = 0; i < BuildEngine.MAX_FALL_TRIES - 1; i++) waits += BuildEngine.FALL_RETRY_SECONDS[i];
        sim.run(waits * 20 + BuildEngine.MAX_FALL_TRIES * 40 + 60);
        Msg a = sim.notifier.of("alert").getLast();
        assertEquals("Bot fell", a.title());
        assertTrue(a.body().contains("could not get out"), a.body());
        assertTrue(a.body().contains("Blocks the rescue placed still stand at -65,61,-29 netherrack"), a.body());
    }

    @Test
    void blocksTheRescueLeftAreAlerted() {
        Sim sim = onTheFloor();
        sim.bot.fallRescueLeft = "-65,61,-30 minecraft:netherrack";
        fall(sim);
        sim.run(60);
        assertEquals(1, sim.bot.fallRescues.size(), sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(1, alerts.size(), sim.state());
        assertEquals("Fall rescue left blocks", alerts.getFirst().title());
        assertTrue(alerts.getFirst().body().contains("-65,61,-30 netherrack"), alerts.getFirst().body());
    }

    @Test
    void belowTheFloorWithoutHavingStoodOnItIsNoFall() {
        Sim sim = new Sim();
        sim.start();
        sim.world.moveTo(new Pos(-60, 61, -60)); // logged in under the tile (never on its floor)
        sim.run(60);
        assertTrue(sim.bot.fallRescues.isEmpty(), sim.state());
    }

    @Test
    void standingOnTheEdgeAboveTheChannelsTopWaterIsNoFall() {
        // local run 2026-10-13: the bot hung over the channel at x E-0.27 on the soul sand edge (feet y172.875); the
        // block cell of its feet (y172, x E-1) held the channel's top water, which is not water around the bot
        Sim sim = onTheFloor();
        Pos top = new Pos(-65, 63, -30);
        sim.world.fluid.add(top);
        sim.world.x = -64.27;
        sim.world.y = 63.875;
        sim.world.z = -29.5;
        sim.run(200);
        assertTrue(sim.bot.fallRescues.isEmpty(), sim.state());
        // in it (feet y63.3, swimming at the top): a fall
        sim.world.y = 63.3;
        sim.run(FallWatch.WATER_TICKS + 30);
        assertEquals(1, sim.bot.fallRescues.size(), sim.state());
    }

    /**
     * Live 2026-10-27: 4 blocks down a dry 1x1 shaft right under the sorter pickup stand, 7 cells north of the tile
     * (outside {@link FallWatch#MARGIN}); every walk failed "boxed in ... the way in was a drop of 4 blocks". Here: the
     * shaft 9 cells north of the moss tile, feet y60, dropped from y64.
     */
    private static final Pos SHAFT = new Pos(-60, 60, -73);

    private static void boxedIn(Sim sim) {
        sim.world.moveTo(SHAFT);
        sim.bot.boxedInDrop = new io.github.digitalisp.mapart.bot.BoxedInDrop(SHAFT, SHAFT.offset(0, 4, 0), 4);
    }

    @Test
    void boxedInAfterADropOutsideTheMarginIsRescuedAndTheEngineCarriesOn() {
        Sim sim = onTheFloor();
        boxedIn(sim);
        sim.run(60);
        assertEquals(1, sim.bot.fallRescues.size(), sim.state());
        FallRescue r = sim.bot.fallRescues.getFirst();
        assertEquals(SHAFT, r.at());
        assertEquals(64, r.floorY(), "back up to the level it dropped from");
        assertTrue(r.why().startsWith("boxed in after a drop of 4 blocks from -60,64,-73"), r.why());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertFalse(sim.notifier.has("alert", "Bot fell"), sim.state());
        assertEquals(64, sim.world.playerBlock().y(), "out at the level it dropped from");
        sim.run(200);
        assertEquals(1, sim.bot.fallRescues.size(), "taken once: no second rescue");
    }

    @Test
    void boxedInAfterADropThatCannotBeClimbedGoesBlockedWithOneBotFellAlertAtTheEnd() {
        Sim sim = onTheFloor();
        sim.bot.fallRescueFailure = r -> "no climb from -60,60,-73 within 6 blocks";
        boxedIn(sim);
        int waits = 0;
        for (int i = 0; i < BuildEngine.MAX_FALL_TRIES - 1; i++) waits += BuildEngine.FALL_RETRY_SECONDS[i];
        sim.run(waits * 20 + BuildEngine.MAX_FALL_TRIES * 40 + 60);
        assertEquals(BuildEngine.MAX_FALL_TRIES, sim.bot.fallRescues.size(), sim.state());
        assertEquals(Phase.BLOCKED, sim.phase(), sim.state());
        List<Msg> alerts = sim.notifier.of("alert");
        assertEquals(2, alerts.size(), "the first failure and the BLOCKED: " + sim.state());
        Msg a = alerts.getLast();
        assertEquals("Bot fell", a.title());
        assertTrue(a.body().contains("The bot fell at -60,60,-73 (boxed in after a drop of 4 blocks"), a.body());
        sim.run(400);
        assertEquals(BuildEngine.MAX_FALL_TRIES, sim.bot.fallRescues.size(), "BLOCKED: no further try");
    }

    @Test
    void aBoxedInDropWhereTheBotNoLongerStandsIsIgnored() {
        Sim sim = onTheFloor();
        sim.bot.boxedInDrop = new io.github.digitalisp.mapart.bot.BoxedInDrop(SHAFT, SHAFT.offset(0, 4, 0), 4);
        sim.run(60);
        assertTrue(sim.bot.fallRescues.isEmpty(), sim.state());
    }

    @Test
    void theKillSwitchTurnsItOff() {
        Sim sim = onTheFloor();
        sim.config.build.fallRescue = false;
        fall(sim);
        sim.run(100);
        assertTrue(sim.bot.fallRescues.isEmpty(), sim.state());
        assertFalse(sim.notifier.has("alert", "Bot fell"));
    }

    @Test
    void aFourthFallWithinTenMinutesIsAlertedAndRescuedAllTheSame() {
        Sim sim = onTheFloor();
        for (int i = 0; i < BuildEngine.MAX_FALL_RESCUES; i++) {
            fall(sim);
            sim.run(60);
            assertEquals(i + 1, sim.bot.fallRescues.size(), sim.state());
            assertNotEquals(Phase.BLOCKED, sim.phase());
        }
        assertTrue(sim.notifier.of("alert").isEmpty(), sim.state());
        fall(sim);
        sim.run(60);
        assertEquals(BuildEngine.MAX_FALL_RESCUES + 1, sim.bot.fallRescues.size(), "the fourth rescue: " + sim.state());
        assertNotEquals(Phase.BLOCKED, sim.phase(), sim.state());
        assertEquals(1, sim.notifier.of("alert").size(), sim.state());
        Msg a = sim.notifier.of("alert").getFirst();
        assertEquals("Bot keeps falling", a.title());
        assertTrue(a.body().contains("The bot fell 4 times within 10 min"), a.body());
        fall(sim);
        sim.run(60);
        assertEquals(BuildEngine.MAX_FALL_RESCUES + 2, sim.bot.fallRescues.size(), sim.state());
        assertEquals(1, sim.notifier.of("alert").size(), "once per window: " + sim.state());
    }

    @Test
    void outsideAClearTheRescueDoesNotHold() {
        Sim sim = onTheFloor();
        fall(sim);
        sim.run(60);
        assertEquals(1, sim.bot.fallRescues.size(), sim.state());
        assertEquals(0, sim.bot.fallRescues.getFirst().holdTicks(), "no clear runs");
    }

    @Test
    void theClimbingKitIsFetchedAtGearAndKeptThroughTheLeftovers() {
        Sim sim = new Sim();
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(NETHERRACK, 64)); // a sorter chest: a material
        sim.inv.add(NETHERRACK, 2);
        Job job = sim.addJob("j0901", "Kit", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, 60_000), "job not done: " + sim.state());
        assertTrue(sim.bot.history.stream().anyMatch(h -> h.startsWith("withdraw " + new Pos(-90, 61, 100))),
            "fetched at GEAR: " + sim.bot.history);
        assertTrue(sim.inv.count(NETHERRACK) >= sim.config.build.fallRescueKit,
            "kept through RESTOCK's leftovers: " + sim.inv.count(NETHERRACK));
    }

    @Test
    void withoutAKitNothingIsFetched() {
        Sim sim = new Sim();
        sim.config.build.fallRescueKit = 0;
        sim.bot.addContainer(new Pos(-90, 61, 100), Map.of(NETHERRACK, 64));
        Job job = sim.addJob("j0902", "NoKit", 1, 1, List.of("moss"), (x, z) -> Sim.single(Sim.WHITE, 2));
        sim.start();
        assertTrue(sim.runUntil(() -> job.status == JobStatus.DONE, 60_000), "job not done: " + sim.state());
        assertFalse(sim.bot.history.stream().anyMatch(h -> h.startsWith("withdraw " + new Pos(-90, 61, 100))),
            sim.bot.history.toString());
    }
}
