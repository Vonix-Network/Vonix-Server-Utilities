package network.vonix.serverutilities.api;

import java.util.Objects;

/**
 * Immutable server projection. No world, player, or database objects.
 *
 * @since 2.0.2
 */
public record ServerSnapshot(
        String vsuVersion,
        String minecraftVersion,
        int onlinePlayers,
        int maxPlayers
) {
    public ServerSnapshot {
        Objects.requireNonNull(vsuVersion, "vsuVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (onlinePlayers < 0) throw new IllegalArgumentException("onlinePlayers");
        if (maxPlayers < 0) throw new IllegalArgumentException("maxPlayers");
    }
}
