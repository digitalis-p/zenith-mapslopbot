package io.github.digitalisp.mapart.bot.impl;

import com.zenith.feature.player.Bot;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputManager;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.FluidState;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static com.zenith.Globals.BOT;
import static com.zenith.Globals.INPUTS;

/**
 * No glide the plugin did not ask for. ZenithProxy's Bot, as vanilla's LocalPlayer, opens a worn elytra when the jump
 * key goes down in the air (not on the ground, in water or on a climbable block). A walk's or the fall rescue's jump in
 * the air opened it: live 2026-10-25 08:47, the fall rescue's forward and jump glided the bot 230 blocks away under the
 * soul sand tile. With {@code build.elytraGear} "flight" the elytra stays worn through the restock, and a player's
 * flight on the account can leave it worn.
 *
 * <p>After every input request of the tick, before ZenithProxy's InputManager (-10000) applies the winning one: a fresh
 * jump press in the air with an elytra worn waits ({@link #holdJump}), unless an elytra flight ({@link FlightLeg})
 * pressed it this tick. Holding jump that was already down cannot open the elytra and passes. Client tick thread.
 */
final class StrayGlideGuard {
    private StrayGlideGuard() {}

    /** Set by an elytra flight that presses jump this tick (its glide start); read and cleared by {@link #tick}. */
    static volatile boolean flightJump;

    private static final long LOG_GAP_MS = 10_000;
    private static long lastLog;
    private static long held;

    private static final @Nullable Field REQUEST = field(InputManager.class, "currentMovementInputRequest");
    private static final @Nullable Field WAS_JUMP = field(Bot.class, "wasJumpPressed");
    private static final @Nullable Method CLIMBABLE = method(Bot.class, "onClimbable");
    private static boolean warned;

    /**
     * Vanilla's glide start ({@code LocalPlayer.aiStep}): jump down this tick, up the tick before, not on a climbable
     * block, and {@code tryToStartFallFlying}: not on the ground, not gliding, not in water, an elytra in the chest
     * slot. Such a press waits unless the flight asked for it.
     */
    static boolean holdJump(boolean jumping, boolean wasJumpPressed, boolean onGround, boolean fallFlying,
                            boolean inWater, boolean climbable, boolean elytraWorn, boolean flightJump) {
        return jumping && !wasJumpPressed && !onGround && !fallFlying && !inWater && !climbable && elytraWorn
            && !flightJump;
    }

    /** ZenithProxy's fields and method the guard reads were found (tests). */
    static boolean fieldsFound() {
        return REQUEST != null && WAS_JUMP != null && CLIMBABLE != null;
    }

    /** Presses held back so far (status and tests). */
    static long held() {
        return held;
    }

    static void tick() {
        boolean flight = flightJump;
        flightJump = false;
        if (!fieldsFound()) {
            if (!warned) {
                warned = true;
                ZenithTask.LOG.warn("Stray glide guard off: ZenithProxy's input fields were not found");
            }
            return;
        }
        try {
            InputRequest r = (InputRequest) REQUEST.get(INPUTS);
            Input in = r == null ? null : r.input();
            if (in == null || !in.jumping) return;
            if (!FlightLeg.elytraInChestSlot()) return;
            boolean was = WAS_JUMP.getBoolean(BOT);
            boolean climbable = (boolean) CLIMBABLE.invoke(BOT);
            // the water state of this tick's position: the Bot's own isTouchingWater is the tick before's, and its tick
            // recomputes it before the glide check (local 2026-10-25: a jump on the first dry tick out of a channel glided)
            boolean inWater = touchingWaterNow(BOT.getX(), BOT.getY(), BOT.getZ());
            if (!holdJump(true, was, BOT.isOnGround(), BOT.isFallFlying(), inWater, climbable, true, flight)) return;
            in.jumping = false;
            held++;
            long now = System.currentTimeMillis();
            if (now - lastLog >= LOG_GAP_MS) {
                lastLog = now;
                ZenithTask.LOG.info("Jump held back in the air: an elytra is worn and no flight asked for a glide ({} so far)", held);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                ZenithTask.LOG.warn("Stray glide guard off: {}", e.toString());
            }
        }
    }

    /**
     * Vanilla's {@code updateFluidHeightAndDoFluidPushing} test for water on the standing box at the bot's position
     * now (0.6 wide, 1.8 high, deflated by 0.001): a water cell in it whose surface reaches the box's bottom.
     */
    static boolean touchingWaterNow(double x, double y, double z) {
        double minX = x - 0.3 + 0.001, maxX = x + 0.3 - 0.001;
        double minY = y + 0.001, maxY = y + 1.8 - 0.001;
        double minZ = z - 0.3 + 0.001, maxZ = z + 0.3 - 0.001;
        for (int bx = (int) Math.floor(minX); bx < (int) Math.ceil(maxX); bx++) {
            for (int by = (int) Math.floor(minY); by < (int) Math.ceil(maxY); by++) {
                for (int bz = (int) Math.floor(minZ); bz < (int) Math.ceil(maxZ); bz++) {
                    FluidState f = World.getFluidState(bx, by, bz);
                    if (f != null && f.water() && by + World.getFluidHeight(f, bx, by, bz) >= minY) return true;
                }
            }
        }
        return false;
    }

    private static @Nullable Field field(Class<?> c, String name) {
        try {
            Field f = c.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static @Nullable Method method(Class<?> c, String name) {
        try {
            Method m = c.getDeclaredMethod(name);
            m.setAccessible(true);
            return m;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
