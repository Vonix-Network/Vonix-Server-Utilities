package network.vonix.serverutilities.api;

import java.util.Locale;
import java.util.Objects;

/**
 * Read-only projection of one {@code vsu_homes} row.
 * Coordinates are display data; teleport requests name an existing home.
 *
 * @since 2.0.2
 */
public record HomeSnapshot(
        String name,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public HomeSnapshot {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(world, "world");
        name = name.trim().toLowerCase(Locale.ROOT);
        if (name.isBlank()) throw new IllegalArgumentException("home name must not be blank");
        if (world.isBlank()) throw new IllegalArgumentException("home world must not be blank");
    }
}
