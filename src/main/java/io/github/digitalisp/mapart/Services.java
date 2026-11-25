package io.github.digitalisp.mapart;

import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import io.github.digitalisp.mapart.bot.BotActions;
import io.github.digitalisp.mapart.catalog.BlockCatalog;
import io.github.digitalisp.mapart.config.MapArtConfig;
import io.github.digitalisp.mapart.coord.Coordination;
import io.github.digitalisp.mapart.engine.EngineControl;
import io.github.digitalisp.mapart.engine.Notifier;
import io.github.digitalisp.mapart.plan.BuildPlanner;
import io.github.digitalisp.mapart.plan.RestockPlanner;
import io.github.digitalisp.mapart.plan.SubmissionService;
import io.github.digitalisp.mapart.render.ColorResolver;
import io.github.digitalisp.mapart.render.Renderer;
import io.github.digitalisp.mapart.store.JobStore;

/**
 * Service locator populated once in {@link MapArtPlugin#onLoad}. Prefer constructor injection inside
 * components (keeps them testable); use this only at wiring points (commands, module construction).
 * CONTRACT (lead-owned).
 */
public final class Services {
    private Services() { }

    public static ComponentLogger LOG;
    public static MapArtConfig CONFIG;
    public static JobStore STORE;
    public static BlockCatalog CATALOG;
    public static ColorResolver COLORS;
    public static Renderer RENDERER;
    public static SubmissionService SUBMISSIONS;
    public static BuildPlanner PLANNER;
    public static RestockPlanner RESTOCK;
    public static BotActions ACTIONS;
    public static EngineControl ENGINE;
    /** Job source and shared-resource arbiter: LocalCoordination for one bot. */
    public static Coordination COORD;
    /** Never null after load: Discord notifier when enabled, otherwise {@link Notifier#LOG_ONLY}. */
    public static Notifier NOTIFIER = Notifier.LOG_ONLY;
}
