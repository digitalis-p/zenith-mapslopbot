package io.github.digitalisp.mapart.bot.impl;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.bot.TaskStatus;
import io.github.digitalisp.mapart.model.BlockSpec;
import io.github.digitalisp.mapart.model.PlatformGeometry;
import io.github.digitalisp.mapart.model.Pos;

import java.util.List;

/**
 * One right-click on a block that changes its state: a button (pressed), a lever (flipped), a note block (its pitch
 * cycles, which an observer turns into a pulse: the moss item-collection switches). Pure decisions, tick by tick;
 * {@link UseBlockTask} runs it on ZenithProxy.
 *
 * <p>The bot gets there and clicks with an {@link Approach}: a configured stand cell ({@code standAt}) first, a face
 * centre in reach and in sight, look on one tick and click on the next. Success when the block's state differs from the
 * one at the click within {@link #REACT_TICKS}; otherwise one more click from where the bot stands. A LEVER's hold and
 * flip back are two of these runs with a wait between, driven by the engine.
 *
 * <p>{@linkplain #pressButton Button mode} (the map counter): only a block whose id ends in {@code _button} is clicked,
 * anything else fails at once. No click while the button shows {@code powered=true} (pressed by someone else, or still
 * from an earlier press): the bot gets there and aims, and the click waits until it pops; pressed for
 * {@link #PRESSED_TICKS} in a row, the run fails. Success only when the button shows {@code powered=true} after the
 * bot's own click. Once a click was sent, a button seen pressed is that click's late answer, never a reason to press
 * again.
 */
public final class UseRun<S extends PlacePipeline.Rotation> {
    static final int REACT_TICKS = 20;
    static final int MAX_ATTEMPTS = 2;
    /** Button mode: ticks in a row the button may be seen pressed before the bot's click (5 s); then the run fails. */
    static final int PRESSED_TICKS = 100;

    private final Pos pos;
    private final Approach.Io<S> io;
    private final Approach<S> approach;
    private final boolean button;
    private @Nullable BlockSpec before;
    private long clickedAt = -1;
    /** Button mode: ticks in a row the run saw the button pressed before its click (not those spent parked). */
    private int pressedTicks;
    private int attempts;
    private String failure = "";

    /**
     * @param first     configured stand cells ({@code standAt}), tried first
     * @param preferred the cell this block was last used from, or null
     */
    public UseRun(Pos pos, List<Pos> first, @Nullable Pos preferred, double reach, Approach.Io<S> io) {
        this(pos, first, preferred, reach, io, false);
    }

    private UseRun(Pos pos, List<Pos> first, @Nullable Pos preferred, double reach, Approach.Io<S> io, boolean button) {
        this.pos = pos;
        this.io = io;
        this.button = button;
        this.approach = new Approach<>(ItemIds.path(io.block(pos).id()) + " at " + pos, List.of(pos), reach, first,
            preferred, io);
        if (button) approach.clickOnlyWhen(() -> !pressed(io.block(pos)));
    }

    /** {@link Approach#brake}: centring with the bot's speed (default on). */
    public UseRun<S> brake(boolean on) {
        approach.brake(on);
        return this;
    }

    /**
     * One press of the button at {@code pos} ({@code BotActions.pressButton}): as the plain run, but only a button is
     * clicked, never while it is still pressed, and only {@code powered=true} after the click is success.
     */
    public static <S extends PlacePipeline.Rotation> UseRun<S> pressButton(Pos pos, List<Pos> first,
                                                                           @Nullable Pos preferred, double reach,
                                                                           Approach.Io<S> io) {
        return new UseRun<>(pos, first, preferred, reach, io, true);
    }

    /**
     * Where the bot may stand to press a button outside any clear (the map counter): off every platform's art layer
     * ({@link StandSpots#offArtLayers}); the clear sweep beside the tile is allowed. The press runs right after STORE,
     * with the flying machines parked and no clear running, and on the moss site the counter sits by the supply chests,
     * inside the sweep north of the tile (the same reasoning as the anvil placement, {@link PlaceRun#standCells}).
     */
    public static StandSpots.CellRule buttonStandCells(List<PlatformGeometry> platforms) {
        return StandSpots.offArtLayers(platforms);
    }

    public String failure() {
        return failure;
    }

    public Approach<S> approach() {
        return approach;
    }

    /** Feet cell the bot stands in now. */
    public Pos standingCell() {
        return approach.standingCell();
    }

    /**
     * Baritone walks to a stand spot and nothing was clicked yet: a safe point to park at. Never when the bot clicks
     * from where it stands (a LEVER's flip back), so a held lever is released on time.
     */
    public boolean canPark() {
        return clickedAt < 0 && attempts == 0 && approach.walking();
    }

    public String progress() {
        if (clickedAt >= 0) return " (clicked, waiting for the block to react)";
        if (pressedTicks > 0) return " (the button is pressed, waiting for it to pop)" + approach.progress();
        return approach.progress();
    }

    public TaskStatus tick(long now) {
        BlockSpec cur = io.block(pos);
        if (cur.isAir()) return fail("nothing to use at " + pos);
        if (button && !cur.id().endsWith("_button")) return fail(cur.id() + " at " + pos + " is not a button");
        if (clickedAt >= 0) {
            io.holdStill();
            if (reacted(cur)) return TaskStatus.SUCCESS;
            if (now - clickedAt < REACT_TICKS) return TaskStatus.RUNNING;
            clickedAt = -1;
            if (++attempts >= MAX_ATTEMPTS) return fail(cur.id() + " at " + pos + " did not react");
            approach.retry();
        }
        if (button && pressed(cur)) {
            if (attempts > 0) return TaskStatus.SUCCESS; // the earlier click's late answer: never press twice
            if (++pressedTicks >= PRESSED_TICKS) {
                return fail("the button at " + pos + " stayed pressed for " + PRESSED_TICKS / 20 + " s");
            }
        } else {
            pressedTicks = 0;
        }
        TaskStatus s = approach.tick(now);
        if (s == TaskStatus.FAILED) return fail("cannot reach " + pos + ": " + approach.failure());
        if (s == TaskStatus.SUCCESS) {
            before = cur;
            clickedAt = now;
        }
        return TaskStatus.RUNNING;
    }

    /** The answer to the bot's click: in button mode the button pressed, else any change of state. */
    private boolean reacted(BlockSpec cur) {
        return button ? pressed(cur) : !cur.equals(before);
    }

    private static boolean pressed(BlockSpec b) {
        return "true".equals(b.props().get("powered"));
    }

    private TaskStatus fail(String why) {
        failure = why;
        return TaskStatus.FAILED;
    }
}
