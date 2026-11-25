package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.Pos;
import io.github.digitalisp.mapart.testutil.ContainerSim;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.digitalisp.mapart.testutil.ContainerSim.STONE;
import static io.github.digitalisp.mapart.testutil.ContainerSim.chest;

/**
 * A container use after its menu opened ({@link ContainerRun}) on a simulated ZenithProxy that, like 3.7.2's Bot,
 * completes every input request FALSE while a menu is open and resets the movement input. Gate B: the still tick then
 * waited for an "executed" request that never came, and every scan, withdraw, lock and food fetch failed with "input
 * not held still" after 200 ticks.
 */
class ContainerRunTest {
    private static final double REACH = 4.5;
    private static final Pos SCAN = new Pos(0, 64, 4);
    private static final Pos WITHDRAW = new Pos(2, 64, 4);
    private static final Pos TABLE = new Pos(4, 64, 4);
    private static final Pos ANVIL = new Pos(6, 64, 4);
    private static final Pos OUTPUT = new Pos(8, 64, 4);

    private ContainerSim s;
    private long t;

    private record Use(TaskStatus status, ContainerRun run) { }

    @BeforeEach
    void site() {
        s = new ContainerSim(71);
        s.fill(-6, 63, -4, 14, 63, 10, STONE);
        s.set(SCAN, chest("north", "single"));
        s.set(WITHDRAW, chest("north", "single"));
        s.set(TABLE, BlockSpec.of("minecraft:cartography_table"));
        s.set(ANVIL, BlockSpec.of("minecraft:anvil", Map.of("facing", "north")));
        s.set(OUTPUT, chest("north", "single"));
        s.teleport(4.5, 64, 1.5);
    }

    /** Approach + click, wait for the menu (as ContainerTask's OPEN), then the run in it. */
    private Use use(Pos target, int clicks, int delay) {
        s.targets = List.of(target);
        s.opened = null;
        Approach<ContainerSim.Shot> a = new Approach<>("block at " + target, s.targets, REACH, null, s);
        ContainerRun run = null;
        long clicked = -1;
        long end = t + 3000;
        while (t < end) {
            s.beginTick(++t);
            TaskStatus st = TaskStatus.RUNNING;
            if (run != null) {
                st = run.tick(t);
            } else if (clicked < 0) {
                TaskStatus as = a.tick(t);
                if (as == TaskStatus.FAILED) st = TaskStatus.FAILED;
                else if (as == TaskStatus.SUCCESS) clicked = t;
            } else {
                s.holdStill();
                if (s.openMenuId() != 0) {
                    s.menuClicks = clicks;
                    s.clickDelay = delay;
                    run = new ContainerRun(s.openMenuId(), s);
                } else if (t - clicked > ContainerTask.OPEN_TIMEOUT) {
                    st = TaskStatus.FAILED;
                }
            }
            s.endTick();
            if (st != TaskStatus.RUNNING) return new Use(st, run);
        }
        return new Use(TaskStatus.FAILED, run);
    }

    private static void assertDone(Use u, String what) {
        assertEquals(TaskStatus.SUCCESS, u.status(), what + ": " + (u.run() == null ? "no menu" : u.run().failure()));
        assertEquals(null, u.run().problem(), what);
    }

    @Test
    void scanWithdrawLockRenameAndStoreAllSucceedWhileTheMenuDropsEveryInput() {
        Use scan = use(SCAN, 0, 1);
        assertDone(scan, "scan");
        Use withdraw = use(WITHDRAW, 3, 1);
        assertDone(withdraw, "withdraw");
        Use lock = use(TABLE, 8, 3);
        assertDone(lock, "lock at the cartography table");
        Use rename = use(ANVIL, 6, 3);
        assertDone(rename, "rename at the anvil");
        Use store = use(OUTPUT, 1, 1);
        assertDone(store, "store");

        assertEquals(5, s.menusOpened);
        assertEquals(4, s.clickRequests, "one request per use with clicks; the scan has none");
        assertEquals(0, s.openMenuId(), "every menu closed again");
        assertTrue(s.droppedInputs > 0, "the simulated ZenithProxy dropped the input requests in the menus");
        assertEquals(0, s.multiActions, "no request while the bot moved (Grim MultiActionsC)");
        for (Use u : List.of(scan, withdraw, lock, rename, store)) {
            assertEquals(0, u.run().stillWaits(), "clicked on the first menu tick: the bot stood still");
        }
    }

    /** Ticks from the menu's first run tick to the tick it was closed, for a use of {@code clicks} at {@code delay}. */
    private int menuTicksOf(int clicks, int answerWait, int answerDelay) {
        s.ncpStrict = false; // live: ZenithProxy does not close the menu after the request
        s.answerWait = answerWait;
        s.answerDelay = answerDelay;
        int before = s.menuTicks;
        Use u = use(WITHDRAW, clicks, 1);
        assertDone(u, "withdraw");
        return s.menuTicks - before;
    }

    /**
     * {@code build.containerAnswerEarly}: the run closes the menu when the server's answers are in instead of after
     * the request's fixed tail wait, and never later than the wait it is given.
     */
    @Test
    void theMenuClosesWhenTheAnswersAreInNotAfterTheFixedTail() {
        int fixed = menuTicksOf(1, 0, 3);
        // the fixed way: the request carries its 2 extra actions (wait + snapshot) and a 7-wait tail is the config's
        // job in the engine; here the sim's request is 3 actions long
        int early = menuTicksOf(1, 8, 3);
        assertTrue(early <= fixed + 3, "answers after 3 ticks: " + early + " ticks in the menu, " + fixed + " with the tail");
        int never = menuTicksOf(1, 7, 1_000_000);
        assertTrue(never >= early + 4, "answers that never come: the full wait, " + never + " against " + early);
        assertEquals(0, s.openMenuId(), "closed in every case");
    }

    @Test
    void aMenuWithAnswersInAtOnceClosesRightAfterTheRequest() {
        int quick = menuTicksOf(2, 7, 0);
        int slow = menuTicksOf(2, 7, 5);
        // the run sees the request done a tick after the sim completes it: 5 ticks of delay are 3-5 more in the menu
        assertTrue(slow - quick >= 3 && slow - quick <= 5, "answers 5 ticks later: " + (slow - quick) + " ticks more");
    }


    @Test
    void aBotStillSlidingWhenTheMenuOpensClicksOnceItStopped() {
        s.slideAfterOpen = 3;
        Use u = use(WITHDRAW, 2, 1);
        assertDone(u, "withdraw");
        assertTrue(u.run().stillWaits() >= 3, "waited while sliding: " + u.run().stillWaits());
        assertEquals(1, s.clickRequests);
        assertEquals(0, s.multiActions);
    }

    @Test
    void aBotThatKeepsMovingInTheMenuFailsWithTheReason() {
        s.slideAfterOpen = 10_000;
        Use u = use(WITHDRAW, 2, 1);
        assertEquals(TaskStatus.FAILED, u.status());
        assertTrue(u.run().failure().contains("did not hold still in the menu"), u.run().failure());
        assertEquals(0, s.clickRequests);
    }

    @Test
    void aRejectedRequestIsSubmittedAgain() {
        s.rejectRequests = 3;
        Use u = use(WITHDRAW, 2, 1);
        assertDone(u, "withdraw");
        assertEquals(1, s.clickRequests);
    }

    @Test
    void anotherModulesRequestKeepsTheClicksBackAndEndsInItsReason() {
        Use first = use(SCAN, 0, 1);
        assertDone(first, "scan");
        s.otherInventoryTicks = 1_000_000;
        Use u = use(WITHDRAW, 2, 1);
        assertEquals(TaskStatus.FAILED, u.status());
        assertEquals("inventory manager busy", u.run().failure());
        assertEquals(0, s.clickRequests);
    }

    @Test
    void withoutNcpStrictTheRunClosesTheMenuItself() {
        s.ncpStrict = false;
        Use u = use(OUTPUT, 1, 1);
        assertDone(u, "store");
        assertEquals(0, s.openMenuId());
    }
}
