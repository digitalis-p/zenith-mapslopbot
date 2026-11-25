package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Bot;
import com.zenith.feature.player.Input;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;

import static com.zenith.Globals.BOT;

/** Diagnostics for sprint stops (temporary): the Bot's collision flags and last sent input, by reflection. */
final class SprintDiag {
    private SprintDiag() { }

    private static final @Nullable Field H = field("horizontalCollision");
    private static final @Nullable Field H_MINOR = field("horizontalCollisionMinor");
    private static final @Nullable Field LAST_INPUT = field("lastSentMovementInput");

    private static @Nullable Field field(String name) {
        try {
            Field f = Bot.class.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    static String collision() {
        try {
            if (H == null || H_MINOR == null) return "?";
            return H.getBoolean(BOT) + (H_MINOR.getBoolean(BOT) ? " (minor)" : "");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    static String input() {
        try {
            if (LAST_INPUT == null) return "?";
            Input in = (Input) LAST_INPUT.get(BOT);
            if (in == null) return "none";
            return (in.pressingForward ? "W" : "") + (in.pressingBack ? "S" : "") + (in.pressingLeft ? "A" : "")
                + (in.pressingRight ? "D" : "") + (in.sprinting ? "+sprint" : "") + (in.jumping ? "+jump" : "");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }
}
