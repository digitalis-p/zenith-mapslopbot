package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link OffhandRepair}: one container click, only standing still with the inventory free; done when it shows. */
class OffhandRepairTest {
    private static final String TOTEM = AirPlaceGuard.TOTEM;
    private static final String CLAY = "minecraft:clay";

    private static final class Fake implements OffhandRepair.Port {
        @Nullable String offhand = CLAY;
        int slot = 24;
        boolean containerOpen;
        boolean busy;
        boolean still = true;
        /** The click's result shows in the cache after this many ticks; -1 = never (the server undid it). */
        int echoAfter;
        final List<Integer> clicks = new ArrayList<>();
        private long clickedAt = -1;
        long now;

        void tick() {
            now++;
            if (clickedAt >= 0 && echoAfter >= 0 && now - clickedAt >= echoAfter) {
                offhand = TOTEM;
                slot = -1;
            }
        }

        @Override public @Nullable String offhand() { return offhand; }
        @Override public int repairSlot() { return slot; }
        @Override public String item() { return TOTEM; }
        @Override public boolean containerOpen() { return containerOpen; }
        @Override public boolean inventoryBusy() { return busy; }
        @Override public boolean stillLastTick() { return still; }

        @Override
        public boolean swapIntoOffhand(int playerSlot) {
            clicks.add(playerSlot);
            clickedAt = now;
            return true;
        }

        // the server check (drill gs2): the cache shows no totem; the server's full inventory decides
        boolean checkWanted;
        /** The answer arrives this many ticks after the request; -1 = never. */
        int answerAfter = 2;
        /** What the server's answer shows: the totem's slot (-1: none) or, with {@link #answerInOffhand}, the offhand. */
        int answerSlot = 24;
        boolean answerInOffhand;
        long mark;
        long requestedAt = -1;
        final List<String> events = new ArrayList<>();

        void serverAnswers() {
            if (requestedAt >= 0 && answerAfter >= 0 && now - requestedAt == answerAfter) mark++;
        }

        @Override public boolean checkWanted() { return checkWanted; }
        @Override public long inventoryMark() { return mark; }

        @Override
        public boolean requestInventory() {
            events.add("request");
            requestedAt = now;
            return true;
        }

        @Override
        public void checked() {
            events.add("checked");
            checkWanted = false;
            if (answerInOffhand) offhand = TOTEM;
            else slot = answerSlot;
        }
    }

    private static OffhandRepair.Step runChecked(OffhandRepair r, Fake f, int max) {
        OffhandRepair.Step s = OffhandRepair.Step.RUNNING;
        for (int i = 0; i < max && s == OffhandRepair.Step.RUNNING; i++) {
            f.tick();
            f.serverAnswers();
            s = r.tick(f.now);
        }
        return s;
    }

    /** No-totem recovery: the cache shows none, the server's answer has it in slot 24: one check, one click, done. */
    @Test
    void noTotemInTheCacheAsksTheServerThenPutsItBack() {
        Fake f = new Fake();
        f.slot = -1;
        f.checkWanted = true;
        f.echoAfter = 3;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.DONE, runChecked(r, f, 100));
        assertEquals(List.of("request", "checked"), f.events);
        assertEquals(List.of(24), f.clicks);
    }

    /** The server has the totem in the offhand (the cache was stale): done with no click. */
    @Test
    void aStaleCacheIsDoneOnTheServersAnswerWithoutAClick() {
        Fake f = new Fake();
        f.slot = -1;
        f.checkWanted = true;
        f.answerInOffhand = true;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.DONE, runChecked(r, f, 100));
        assertEquals(List.of("request", "checked"), f.events);
        assertEquals(List.of(), f.clicks);
    }

    /** The check is a click too: never while walking; and no answer at all fails the run (the engine retries later). */
    @Test
    void theCheckWaitsForAStillTickAndFailsWithoutAnAnswer() {
        Fake f = new Fake();
        f.slot = -1;
        f.checkWanted = true;
        f.still = false;
        f.answerAfter = -1;
        OffhandRepair r = new OffhandRepair(f);
        for (int i = 0; i < 30; i++) {
            f.tick();
            assertEquals(OffhandRepair.Step.RUNNING, r.tick(f.now));
        }
        assertEquals(List.of(), f.events, "no request while walking");
        f.still = true;
        assertEquals(OffhandRepair.Step.FAILED, runChecked(r, f, 100));
        assertEquals(List.of("request"), f.events);
        assertTrue(r.failure().contains("no answer to the full inventory request"), r.failure());
    }

    /** The server's answer has no totem either: the run fails with the server's word. */
    @Test
    void aServerAnswerWithoutATotemFails() {
        Fake f = new Fake();
        f.slot = -1;
        f.checkWanted = true;
        f.answerSlot = -1;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.FAILED, runChecked(r, f, 100));
        assertTrue(r.failure().contains("the server's full inventory has none"), r.failure());
        assertEquals(List.of(), f.clicks);
    }

    private static OffhandRepair.Step runUntilDone(OffhandRepair r, Fake f, int max) {
        OffhandRepair.Step s = OffhandRepair.Step.RUNNING;
        for (int i = 0; i < max && s == OffhandRepair.Step.RUNNING; i++) {
            f.tick();
            s = r.tick(f.now);
        }
        return s;
    }

    @Test
    void oneClickOnTheGuardsSlotThenDone() {
        Fake f = new Fake();
        f.echoAfter = 3;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.DONE, runUntilDone(r, f, 50));
        assertEquals(List.of(24), f.clicks);
    }

    @Test
    void waitsForAStillTickNoMenuAndAFreeInventory() {
        Fake f = new Fake();
        f.still = false;
        f.containerOpen = true;
        f.busy = true;
        OffhandRepair r = new OffhandRepair(f);
        for (int i = 0; i < 30; i++) {
            f.tick();
            assertEquals(OffhandRepair.Step.RUNNING, r.tick(f.now));
        }
        assertEquals(List.of(), f.clicks, "never while walking, in a menu or with a request in flight");
        f.still = true;
        f.containerOpen = false;
        f.busy = false;
        assertEquals(OffhandRepair.Step.DONE, runUntilDone(r, f, 50));
        assertEquals(List.of(24), f.clicks);
    }

    @Test
    void aClickTheServerUndoesFailsAfterTheConfirmTime() {
        Fake f = new Fake();
        f.echoAfter = -1;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.FAILED, runUntilDone(r, f, 200));
        assertEquals(List.of(24), f.clicks, "one click per run; the engine retries later");
        assertTrue(r.failure().contains("still holds " + CLAY), r.failure());
    }

    @Test
    void nothingToPutBackFails() {
        Fake f = new Fake();
        f.slot = -1;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.FAILED, runUntilDone(r, f, 100));
        assertEquals(List.of(), f.clicks);
    }

    @Test
    void alreadyRightIsDoneAtOnce() {
        Fake f = new Fake();
        f.offhand = TOTEM;
        OffhandRepair r = new OffhandRepair(f);
        f.tick();
        assertEquals(OffhandRepair.Step.DONE, r.tick(f.now));
        assertEquals(List.of(), f.clicks);
    }

    @Test
    void neverWaitsForeverForAStillTick() {
        Fake f = new Fake();
        f.still = false;
        OffhandRepair r = new OffhandRepair(f);
        assertEquals(OffhandRepair.Step.FAILED, runUntilDone(r, f, 400));
        assertTrue(r.failure().contains("no still tick"), r.failure());
    }
}
