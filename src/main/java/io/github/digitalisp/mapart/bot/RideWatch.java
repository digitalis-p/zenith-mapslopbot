package io.github.digitalisp.mapart.bot;

/**
 * What the colour-suppression ride watches ({@code BotActions.ride}): the cart standing still longer than
 * {@code stallTicks}, the whole ride longer than {@code maxTicks}, health below {@code minHealth}; and an arm swing
 * every {@code swingTicks} (0 = none). Ticks are the bot's client ticks.
 */
public record RideWatch(int stallTicks, int maxTicks, float minHealth, int swingTicks) { }
