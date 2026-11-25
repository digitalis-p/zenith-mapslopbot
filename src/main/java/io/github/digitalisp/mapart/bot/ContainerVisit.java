package io.github.digitalisp.mapart.bot;

import org.jspecify.annotations.Nullable;
import io.github.digitalisp.mapart.model.Pos;

/**
 * One container of a planned sweep: open {@code container}, standing at {@code standAt} if it still works there
 * (null: wherever the approach finds a stand spot).
 */
public record ContainerVisit(Pos container, @Nullable Pos standAt) { }
