package io.github.digitalisp.mapart.command;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class CommandLineTest {
    @Test
    void parsesNameArgsAndRest() {
        CommandLine c = CommandLine.parse("!map move j0007 2", "!map").orElseThrow();
        assertEquals("move", c.name());
        assertEquals(List.of("j0007", "2"), c.args());
        assertEquals("j0007 2", c.rest());
        assertEquals(OptionalInt.of(2), c.intArg(1));
        assertEquals("j0007", c.arg(0).orElseThrow());
        assertTrue(c.arg(2).isEmpty());
    }

    @Test
    void prefixIsCaseInsensitiveAndNeedsSeparator() {
        assertEquals("queue", CommandLine.parse("!MAP QUEUE", "!map").orElseThrow().name());
        assertTrue(CommandLine.parse("!mapqueue", "!map").isEmpty());
        assertTrue(CommandLine.parse("hello !map queue", "!map").isEmpty());
        assertTrue(CommandLine.parse("", "!map").isEmpty());
        assertTrue(CommandLine.parse(null, "!map").isEmpty());
        assertTrue(CommandLine.parse("!map queue", "").isEmpty());
    }

    @Test
    void bareprefixIsHelp() {
        assertEquals("help", CommandLine.parse("  !map  ", "!map").orElseThrow().name());
    }

    @Test
    void restKeepsFreeTextWithInnerSpacing() {
        CommandLine c = CommandLine.parse("  !map   pause   taking a  break ", "!map").orElseThrow();
        assertEquals("pause", c.name());
        assertEquals("taking a  break", c.rest());
        assertEquals(List.of("taking", "a", "break"), c.args());
    }

    @Test
    void multiLineMessages() {
        CommandLine c = CommandLine.parse("!map\nstock\ncarpet", "!map").orElseThrow();
        assertEquals("stock", c.name());
        assertEquals("carpet", c.rest());
    }

    @Test
    void intArgRejectsGarbageAndAcceptsHash() {
        CommandLine c = CommandLine.parse("!map move j7 two", "!map").orElseThrow();
        assertTrue(c.intArg(1).isEmpty());
        assertTrue(c.intArg(5).isEmpty());
        assertEquals(OptionalInt.of(3), CommandLine.parse("!map move j7 #3", "!map").orElseThrow().intArg(1));
    }

    @Test
    void customPrefix() {
        CommandLine c = CommandLine.parse(".art status", ".art").orElseThrow();
        assertEquals("status", c.name());
        assertTrue(c.args().isEmpty());
        assertEquals("", c.rest());
    }
}
