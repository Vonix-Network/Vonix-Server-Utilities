package network.vonix.serverutilities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Read-only last-death projection. Cause, killer, and earlier deaths are not stored.
 *
 * @since 2.0.2
 */
public record LastDeathSnapshot(
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        Optional<String> cause,
        Optional<String> killer,
        OptionalLong occurredAtEpochMs
) {
    public LastDeathSnapshot {
        Objects.requireNonNull(world, "world");
        cause = cause == null ? Optional.empty() : cause;
        killer = killer == null ? Optional.empty() : killer;
        occurredAtEpochMs = occurredAtEpochMs == null ? OptionalLong.empty() : occurredAtEpochMs;
        if (world.isBlank()) throw new IllegalArgumentException("death world must not be blank");
    }

    public static LastDeathSnapshot locationOnly(
            String world, double x, double y, double z, float yaw, float pitch, long occurredAtEpochMs) {
        return new LastDeathSnapshot(
                world, x, y, z, yaw, pitch,
                Optional.empty(), Optional.empty(), OptionalLong.of(occurredAtEpochMs));
    }
}
