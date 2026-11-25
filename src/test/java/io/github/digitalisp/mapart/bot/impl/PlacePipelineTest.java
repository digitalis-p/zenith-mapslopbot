package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pipelined tick sequence of the lane placer against a fake world. The fake models the server side: the rotation
 * "in effect" is the one ZenithProxy's Bot sent with the previous tick's movement packet, and every click must be for
 * the target that rotation points at (Grim RotationPlace pre check, and the post check's lastYaw/lastPitch).
 * Each tick logs its packets in wire order: [select n] [click t] then "move t" (new rotation) or "move keep".
 */
class PlacePipelineTest {
    private static final String WHITE = "minecraft:white_carpet";
    private static final String RED = "minecraft:red_carpet";
    private static final String BLUE = "minecraft:blue_carpet";

    record Shot(String target, float yaw, float pitch) implements PlacePipeline.Rotation { }

    /** Fake ZenithProxy side (input manager, Bot, inventory, pacer) for one lane. */
    private static final class Fake implements PlacePipeline.Env<String, Shot> {
        PlacePipeline<String, Shot> pipe = new PlacePipeline<>();
        final List<String> todo = new ArrayList<>();
        final Map<String, String> itemOf = new HashMap<>();
        final Map<String, Integer> slotOf = new HashMap<>();
        final Set<String> noSight = new HashSet<>();
        final Set<String> drifted = new HashSet<>();
        final AdaptivePacer pacer = new AdaptivePacer(1, 1, 8);
        final List<List<String>> ticks = new ArrayList<>();
        int held = 1;
        boolean ownedLast = true;
        boolean accept = true;
        boolean busy;
        long now;
        long lastClick = -1000;
        @Nullable String inEffect;
        PlacePipeline.@Nullable Plan<String, Shot> lastPlan;
        private boolean submitted;
        private List<String> log = new ArrayList<>();

        Fake(String... targets) {
            todo.addAll(List.of(targets));
            slotOf.put(WHITE, 1);
        }

        /** One client tick: our handler (plan, submit, execute), then Bot's movement packet. */
        List<String> tick() {
            now++;
            log = new ArrayList<>();
            submitted = false;
            PlacePipeline.Plan<String, Shot> plan = pipe.plan(now, List.copyOf(todo), this);
            lastPlan = plan;
            submitted = true; // the task submits plan.nextShot() (null = keep) with its movement keys here
            pipe.execute(now, plan, this);
            if (accept) {
                if (plan.nextShot() != null) {
                    inEffect = plan.nextShot().target();
                    log.add("move " + inEffect);
                } else {
                    log.add("move keep");
                }
            } else {
                log.add("move other"); // a higher-priority module owned the input this tick
                inEffect = null;
            }
            ownedLast = accept;
            ticks.add(log);
            return log;
        }

        List<String> run(int n) {
            List<String> all = new ArrayList<>();
            for (int i = 0; i < n; i++) all.addAll(tick());
            return all;
        }

        @Override
        public @Nullable Shot aim(String t) {
            return noSight.contains(t) ? null : new Shot(t, t.hashCode() % 90, 50f);
        }

        @Override
        public @Nullable Shot recheck(String t) {
            return t.equals(inEffect) && !drifted.contains(t) ? new Shot(t, 0f, 50f) : null;
        }

        @Override
        public String item(String t) {
            return itemOf.getOrDefault(t, WHITE);
        }

        @Override
        public @Nullable String heldItem() {
            for (var e : slotOf.entrySet()) if (e.getValue() == held) return e.getKey();
            return null;
        }

        @Override
        public int heldSlot() {
            return held;
        }

        @Override
        public int hotbarSlot(String item) {
            return slotOf.getOrDefault(item, -1);
        }

        @Override
        public boolean ownedLastTick() {
            return ownedLast;
        }

        @Override
        public boolean inventoryBusy() {
            return busy;
        }

        @Override
        public boolean pacerReady() {
            return pacer.ready(now, lastClick);
        }

        @Override
        public boolean canClick() {
            assertTrue(submitted, "canClick is only asked after this tick's submit");
            return accept && ownedLast;
        }

        @Override
        public boolean select(int slot) {
            assertTrue(log.stream().noneMatch(s -> s.startsWith("click") || s.startsWith("air")),
                "slot change after a click in one tick");
            assertTrue(slot != held, "re-sending the selected slot (BadPacketsA)");
            log.add("select " + slot);
            held = slot;
            return true;
        }

        @Override
        public boolean click(String t, Shot shot) {
            assertTrue(log.stream().noneMatch(s -> s.startsWith("click") || s.startsWith("air")), "two clicks in one tick");
            assertEquals(inEffect, t, "click must use the rotation sent on the previous tick");
            assertEquals(t, shot.target());
            assertEquals(item(t), heldItem(), "clicked without holding the item");
            assertTrue(todo.contains(t), "clicked a target that is not TODO");
            log.add("click " + t);
            todo.remove(t);
            lastClick = now;
            return true;
        }

        /** Targets placed by airplace (no aim); the rest by LOOK. */
        final Set<String> air = new HashSet<>();

        @Override
        public PlaceMethod method(String t) {
            return air.contains(t) ? PlaceMethod.AIRPLACE : PlaceMethod.LOOK;
        }

        @Override
        public boolean airPlace(String t) {
            assertTrue(log.stream().noneMatch(s -> s.startsWith("click") || s.startsWith("air")), "two placements in one tick");
            assertTrue(air.contains(t), "airplace of a LOOK target");
            assertEquals(item(t), heldItem(), "airplaced without holding the item");
            assertTrue(todo.contains(t), "airplaced a target that is not TODO");
            log.add("air " + t);
            todo.remove(t);
            lastClick = now;
            return true;
        }
    }

    @Test
    void clicksEveryTickAndAimsTheNextTargetInTheClickTick() {
        Fake f = new Fake("a", "b", "c", "d");
        assertEquals(List.of("move a"), f.tick(), "first tick only aims");
        assertEquals(List.of("click a", "move b"), f.tick());
        assertEquals(List.of("click b", "move c"), f.tick());
        assertEquals(List.of("click c", "move d"), f.tick());
        assertEquals(List.of("click d", "move keep"), f.tick(), "end of lane: last click keeps the rotation");
        assertTrue(f.todo.isEmpty());
        assertNull(f.pipe.aimed());
        assertEquals(List.of("move keep"), f.tick(), "nothing left: no click");
    }

    @Test
    void endOfLaneFlushClicksTheLastAimedTargetWithTheRotationUnchanged() {
        Fake f = new Fake("z");
        f.tick();
        List<String> flush = f.tick();
        assertEquals(List.of("click z", "move keep"), flush);
        assertNull(f.lastPlan.nextShot(), "no new rotation after the last click");
        assertNull(f.pipe.aimed());
    }

    @Test
    void slotChangeGoesOutDirectlyBeforeTheClickInTheSameTick() {
        Fake f = new Fake("a", "b");
        f.itemOf.put("b", RED);
        f.slotOf.put(RED, 4);
        f.tick(); // aim a
        assertEquals(List.of("click a", "move b"), f.tick());
        assertEquals(List.of("select 4", "click b", "move keep"), f.tick());
        assertEquals(4, f.held);
    }

    @Test
    void noSlotChangeWhileAnInventoryRequestIsInFlight() {
        Fake f = new Fake("a");
        f.itemOf.put("a", RED);
        f.slotOf.put(RED, 4);
        f.tick(); // aim a
        f.busy = true;
        assertEquals(List.of("move keep"), f.tick(), "aim held, no select, no click");
        assertEquals(List.of("move keep"), f.tick());
        f.busy = false;
        assertEquals(List.of("select 4", "click a", "move keep"), f.tick());
    }

    @Test
    void heldItemIsAimedFirst() {
        Fake f = new Fake("b", "a");
        f.itemOf.put("b", RED);
        f.slotOf.put(RED, 4);
        assertEquals(List.of("move a"), f.tick(), "white is held: a before b despite lane order");
        assertEquals(List.of("click a", "move b"), f.tick());
        assertEquals(List.of("select 4", "click b", "move keep"), f.tick());
    }

    @Test
    void itemSelectedForTheClickIsPreferredForTheNextAim() {
        // lane order b, a, c; b and c need red (slot 4), a white (slot 1); an empty slot is held
        Fake f = new Fake("b", "a", "c");
        f.held = 0;
        f.itemOf.put("b", RED);
        f.itemOf.put("c", RED);
        f.slotOf.put(RED, 4);
        assertEquals(List.of("move b"), f.tick(), "nothing useful in hand: lane order");
        assertEquals(List.of("select 4", "click b", "move c"), f.tick(), "red stays in hand: c before a");
        assertEquals(List.of("click c", "move a"), f.tick(), "no select for c");
        assertEquals(List.of("select 1", "click a", "move keep"), f.tick());
    }

    @Test
    void pacerIntervalTwoInsertsAimOnlyTicks() {
        Fake f = new Fake("a", "b", "c", "d", "e");
        f.tick();
        f.tick(); // click a
        f.pacer.onMiss(); // a placement was not confirmed: interval 1 -> 2
        assertEquals(2, f.pacer.interval());
        assertEquals(List.of("move keep"), f.tick(), "aim-only tick: rotation held on b");
        assertEquals(List.of("click b", "move c"), f.tick());
        assertEquals(List.of("move keep"), f.tick());
        assertEquals(List.of("click c", "move d"), f.tick());
        f.pacer.onMiss(); // 4
        assertEquals(List.of("move keep"), f.tick());
        assertEquals(List.of("move keep"), f.tick());
        assertEquals(List.of("move keep"), f.tick());
        assertEquals(List.of("click d", "move e"), f.tick());
    }

    @Test
    void aimedTargetSkippedMeanwhileIsNotClicked() {
        Fake f = new Fake("a", "b", "c");
        f.tick();
        f.tick(); // click a, aim b
        f.todo.remove("b"); // skipped by updateEntries (occupied / missing item / support gone) or confirmed
        assertEquals(List.of("move c"), f.tick(), "no click for the stale aim, re-aim");
        assertEquals(List.of("click c", "move keep"), f.tick());
    }

    @Test
    void missedPlacementIsRetriedWithoutDesync() {
        Fake f = new Fake("a", "b", "c");
        f.tick();
        f.tick(); // click a, aim b
        f.tick(); // click b, aim c
        // a was not confirmed within placeConfirmTicks: back to TODO (one retry), pacer slows down
        f.todo.add(0, "a");
        f.pacer.onMiss();
        List<String> next = f.run(6);
        assertEquals(2, next.stream().filter(s -> s.startsWith("click")).count(), "c and the retried a");
        assertTrue(next.contains("click a"));
        assertTrue(f.todo.isEmpty());
    }

    @Test
    void rotationNoLongerHittingIsReAimedNotClicked() {
        Fake f = new Fake("a", "b");
        f.tick(); // aim a
        f.drifted.add("a"); // the bot walked: the ray with the sent rotation misses a now
        List<String> t = f.tick();
        assertFalse(t.contains("click a"));
        f.drifted.clear();
        assertTrue(f.run(3).contains("click a"));
    }

    @Test
    void noClickWhenAnotherModuleOwnedTheInput() {
        Fake f = new Fake("a", "b");
        f.tick(); // aim a
        f.accept = false; // this tick's request is rejected: no click, rotation not ours
        assertEquals(List.of("move other"), f.tick());
        f.accept = true;
        assertEquals(List.of("move a"), f.tick(), "previous tick not ours: aim again first");
        assertEquals(List.of("click a", "move b"), f.tick());
    }

    @Test
    void lineOfSightMissFallsBackToTheNextCandidate() {
        Fake f = new Fake("a", "b");
        f.noSight.add("a");
        assertEquals(List.of("move b"), f.tick());
        assertEquals(List.of("click b", "move keep"), f.tick());
    }

    @Test
    void mainInventoryItemIsMovedInATickWithoutAClick() {
        Fake f = new Fake("a", "b");
        f.itemOf.put("b", BLUE); // only in the main inventory
        f.tick(); // aim a
        List<String> t2 = f.tick();
        assertEquals(List.of("click a", "move b"), t2);
        assertEquals(BLUE, f.lastPlan.inventoryMove(), "caller must stand still and move blue in a no-click tick");
        assertEquals(List.of("move keep"), f.tick(), "aim held while blue is not in the hotbar");
        assertEquals(BLUE, f.lastPlan.inventoryMove());
        assertNull(f.lastPlan.click());
        // the caller's inventory request: move to hotbar 5 and select it (ZenithProxy InventoryManager)
        f.busy = true;
        f.slotOf.put(BLUE, 5);
        assertEquals(List.of("move keep"), f.tick(), "no click while the request is in flight");
        f.held = 5;
        f.busy = false;
        assertEquals(List.of("click b", "move keep"), f.tick());
    }

    @Test
    void invariantsHoldUnderRandomDisturbances() {
        Random r = new Random(1234);
        for (int run = 0; run < 200; run++) {
            Fake f = new Fake();
            for (int i = 0; i < 30; i++) {
                String t = "t" + i;
                f.todo.add(t);
                f.itemOf.put(t, switch (r.nextInt(4)) {
                    case 0 -> RED;
                    case 1 -> BLUE;
                    default -> WHITE;
                });
            }
            f.slotOf.put(RED, 3);
            int clicks = 0;
            for (int tick = 0; tick < 3000 && !f.todo.isEmpty(); tick++) {
                f.noSight.clear();
                for (String t : f.todo) if (r.nextInt(8) == 0) f.noSight.add(t);
                f.drifted.clear();
                if (r.nextInt(10) == 0 && f.inEffect != null) f.drifted.add(f.inEffect);
                f.accept = r.nextInt(15) != 0;
                f.busy = r.nextInt(12) == 0;
                if (r.nextInt(40) == 0) f.pacer.onMiss();
                if (r.nextInt(3) == 0) f.pacer.onConfirm();
                if (r.nextInt(30) == 0 && !f.todo.isEmpty()) f.todo.remove(r.nextInt(f.todo.size())); // skipped
                if (f.lastPlan != null && f.lastPlan.inventoryMove() != null && !f.busy) {
                    f.slotOf.putIfAbsent(f.lastPlan.inventoryMove(), 6); // the caller's move completed
                }
                List<String> log = f.tick();
                long c = log.stream().filter(s -> s.startsWith("click")).count();
                assertTrue(c <= 1, "at most one click per tick: " + log);
                int sel = log.indexOf(log.stream().filter(s -> s.startsWith("select")).findFirst().orElse(""));
                if (sel >= 0) assertTrue(log.get(sel + 1).startsWith("click"), "select only directly before a click: " + log);
                assertTrue(log.getLast().startsWith("move"), "exactly one movement packet, last: " + log);
                assertEquals(1, log.stream().filter(s -> s.startsWith("move")).count());
                clicks += (int) c;
            }
            assertTrue(f.todo.isEmpty(), "run " + run + " finished: " + f.todo);
            assertTrue(clicks > 0);
        }
    }

    @Test
    void unpipelinedFallbackKeepsTheRotationInTheClickTick() {
        Fake f = new Fake("a", "b", "c");
        f.pipe = new PlacePipeline<>(false); // build.pipelinePlacement = false
        assertEquals(List.of("move a"), f.tick());
        assertEquals(List.of("click a", "move keep"), f.tick(), "no turn right after the click");
        assertEquals(List.of("move b"), f.tick());
        assertEquals(List.of("click b", "move keep"), f.tick());
        assertEquals(List.of("move c"), f.tick());
        assertEquals(List.of("click c", "move keep"), f.tick());
        assertTrue(f.todo.isEmpty());
    }

    @Test
    void steadyStateRateIsOnePlacementPerTick() {
        String[] ts = new String[100];
        for (int i = 0; i < ts.length; i++) ts[i] = "t" + i;
        Fake f = new Fake(ts);
        int ticks = 0;
        while (!f.todo.isEmpty()) {
            f.tick();
            ticks++;
        }
        assertEquals(101, ticks, "one aim tick, then one placement per tick");
    }

    @Test
    void airplaceLanePlacesFromTheFirstTickWithoutAnyRotation() {
        Fake f = new Fake("a", "b", "c", "d");
        f.air.addAll(f.todo);
        f.itemOf.put("b", RED);
        f.slotOf.put(RED, 4);
        assertEquals(List.of("air a", "move keep"), f.tick(), "no aim tick first");
        assertTrue(f.lastPlan.air());
        assertNull(f.lastPlan.clickShot());
        assertNull(f.lastPlan.nextShot());
        assertEquals(List.of("air c", "move keep"), f.tick(), "held item first, then lane order");
        assertEquals(List.of("air d", "move keep"), f.tick());
        assertEquals(List.of("select 4", "air b", "move keep"), f.tick());
        assertTrue(f.todo.isEmpty());
        assertNull(f.pipe.aimed());
        assertNull(f.inEffect, "no rotation was ever requested");
        assertEquals(List.of("move keep"), f.tick());
    }

    @Test
    void airplaceSlotChangeGoesOutDirectlyBeforeItInTheSameTick() {
        Fake f = new Fake("a");
        f.air.add("a");
        f.itemOf.put("a", RED);
        f.slotOf.put(RED, 4);
        assertEquals(List.of("select 4", "air a", "move keep"), f.tick());
        assertEquals(4, f.lastPlan.select());
        assertEquals(4, f.held);
    }

    /**
     * a and c by LOOK, b, d and e by airplace: the LOOK target aimed in one tick is clicked in the next, the airplaces
     * fill the ticks without a LOOK click, one placement per tick.
     */
    @Test
    void mixedLaneAirplacesInTheTicksWithoutALookClick() {
        Fake f = new Fake("a", "b", "c", "d", "e");
        f.air.addAll(List.of("b", "d", "e"));
        assertEquals(List.of("air b", "move a"), f.tick(), "airplace now, the LOOK target aimed in the same tick");
        assertEquals(List.of("click a", "move c"), f.tick(), "aimed on the previous tick: clicked, no airplace");
        assertEquals(List.of("click c", "move keep"), f.tick(), "airplace targets are never aimed");
        assertEquals(List.of("air d", "move keep"), f.tick());
        assertEquals(List.of("air e", "move keep"), f.tick());
        assertTrue(f.todo.isEmpty());
    }

    @Test
    void airplacesWaitForThePacer() {
        Fake f = new Fake("a", "b", "c");
        f.air.addAll(f.todo);
        f.pacer.onMiss(); // interval 1 -> 2
        assertEquals(2, f.pacer.interval());
        assertEquals(List.of("air a", "move keep"), f.tick());
        assertEquals(List.of("move keep"), f.tick(), "pacer gap");
        assertEquals(List.of("air b", "move keep"), f.tick());
        assertEquals(List.of("move keep"), f.tick());
        assertEquals(List.of("air c", "move keep"), f.tick());
    }

    @Test
    void noAirplaceWhileAnInventoryRequestIsInFlight() {
        Fake f = new Fake("a");
        f.air.add("a");
        f.busy = true;
        assertEquals(List.of("move keep"), f.tick());
        assertEquals(List.of("move keep"), f.tick());
        f.busy = false;
        assertEquals(List.of("air a", "move keep"), f.tick());
    }

    @Test
    void noAirplaceWhenAnotherModuleOwnedTheInput() {
        Fake f = new Fake("a");
        f.air.add("a");
        f.accept = false;
        assertEquals(List.of("move other"), f.tick());
        assertTrue(f.todo.contains("a"));
        f.accept = true;
        assertEquals(List.of("move keep"), f.tick(), "the previous tick's input was not ours: canClick false");
        assertEquals(List.of("air a", "move keep"), f.tick());
    }

    @Test
    void airplaceItemOnlyInTheMainInventoryIsMovedFirst() {
        Fake f = new Fake("a");
        f.air.add("a");
        f.itemOf.put("a", BLUE);
        assertEquals(List.of("move keep"), f.tick(), "nothing placed");
        assertEquals(BLUE, f.lastPlan.inventoryMove());
        assertNull(f.lastPlan.click());
        assertTrue(f.todo.contains("a"));
        // the caller's inventory request moves blue to hotbar 5
        f.busy = true;
        f.slotOf.put(BLUE, 5);
        assertEquals(List.of("move keep"), f.tick(), "no airplace while the request is in flight");
        f.busy = false;
        assertEquals(List.of("select 5", "air a", "move keep"), f.tick());
    }

    @Test
    void mixedLaneInvariantsHoldUnderRandomDisturbances() {
        Random r = new Random(4321);
        for (int run = 0; run < 200; run++) {
            Fake f = new Fake();
            for (int i = 0; i < 30; i++) {
                String t = "t" + i;
                f.todo.add(t);
                if (r.nextBoolean()) f.air.add(t);
                f.itemOf.put(t, switch (r.nextInt(4)) {
                    case 0 -> RED;
                    case 1 -> BLUE;
                    default -> WHITE;
                });
            }
            f.slotOf.put(RED, 3);
            for (int tick = 0; tick < 3000 && !f.todo.isEmpty(); tick++) {
                f.noSight.clear();
                for (String t : f.todo) if (r.nextInt(8) == 0) f.noSight.add(t);
                f.drifted.clear();
                if (r.nextInt(10) == 0 && f.inEffect != null) f.drifted.add(f.inEffect);
                f.accept = r.nextInt(15) != 0;
                f.busy = r.nextInt(12) == 0;
                if (r.nextInt(40) == 0) f.pacer.onMiss();
                if (r.nextInt(3) == 0) f.pacer.onConfirm();
                if (r.nextInt(30) == 0 && !f.todo.isEmpty()) f.todo.remove(r.nextInt(f.todo.size()));
                if (f.lastPlan != null && f.lastPlan.inventoryMove() != null && !f.busy) {
                    f.slotOf.putIfAbsent(f.lastPlan.inventoryMove(), 6);
                }
                List<String> log = f.tick();
                // the fake asserts: a LOOK click uses the previous tick's rotation, no airplace of a LOOK target
                List<String> placed = log.stream().filter(s -> s.startsWith("click") || s.startsWith("air")).toList();
                assertTrue(placed.size() <= 1, "at most one placement per tick: " + log);
                for (String s : log) {
                    if (s.startsWith("move ")) assertFalse(f.air.contains(s.substring(5)), "airplace target aimed: " + log);
                    if (s.startsWith("click ")) assertFalse(f.air.contains(s.substring(6)), "airplace target clicked: " + log);
                }
                int sel = log.indexOf(log.stream().filter(s -> s.startsWith("select")).findFirst().orElse(""));
                if (sel >= 0) assertTrue(placed.contains(log.get(sel + 1)), "select only directly before a placement: " + log);
                assertTrue(log.getLast().startsWith("move"), "exactly one movement packet, last: " + log);
            }
            assertTrue(f.todo.isEmpty(), "run " + run + " finished: " + f.todo);
        }
    }
}
