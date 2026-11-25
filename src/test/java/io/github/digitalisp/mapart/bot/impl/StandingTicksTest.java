package io.github.digitalisp.mapart.bot.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StandingTicksTest {
    @Test
    void zeroCountsAndClickedTicksDoNotBecomeIdleReasons() {
        StandingTicks stats = new StandingTicks();
        assertTrue(stats.summary().contains("standing ticks 0, 0 without a click"));
        stats.tick(true, StandingTicks.Idle.WINDOW);
        assertTrue(stats.summary().contains("standing ticks 1, 0 without a click"));
        assertTrue(stats.summary().contains("0 window full"));
    }

    @Test
    void everyIdleReasonIsExclusiveAndCapSamplesAreBounded() {
        StandingTicks stats = new StandingTicks();
        for (StandingTicks.Idle reason : StandingTicks.Idle.values()) stats.tick(false, reason);
        stats.cap(StandingTicks.Hold.ACK, "row 1 column -4 minecraft:white_carpet");
        stats.cap(StandingTicks.Hold.ACK, "row 2 column 4 minecraft:black_carpet");
        String line = stats.summary();
        assertTrue(line.contains("standing ticks 9, 9 without a click"), line);
        for (StandingTicks.Idle reason : StandingTicks.Idle.values()) assertTrue(line.contains("1 " + reason.label), line);
        assertTrue(line.contains("2 ack"), line);
        assertTrue(line.contains("ack=row 1 column -4 minecraft:white_carpet"), line);
        assertFalse(line.contains("row 2"), line);
    }
}
