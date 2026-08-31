package network.vonix.serverutilities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Read-only player state projection. Missing numeric fields stay empty.
 *
 * @since 2.0.2
 */
public record PlayerStateSnapshot(
        UUID playerId,
        String username,
        boolean online,
        Optional<String> dimension,
        OptionalDouble health,
        OptionalDouble maxHealth,
        OptionalInt hunger,
        OptionalInt armor,
        OptionalInt xpLevel,
        OptionalLong playtimeTicks,
        OptionalLong lastSeenEpochMs
) {
    public PlayerStateSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        username = username == null ? "" : username;
        dimension = dimension == null ? Optional.empty() : dimension;
        health = health == null ? OptionalDouble.empty() : health;
        maxHealth = maxHealth == null ? OptionalDouble.empty() : maxHealth;
        hunger = hunger == null ? OptionalInt.empty() : hunger;
        armor = armor == null ? OptionalInt.empty() : armor;
        xpLevel = xpLevel == null ? OptionalInt.empty() : xpLevel;
        playtimeTicks = playtimeTicks == null ? OptionalLong.empty() : playtimeTicks;
        lastSeenEpochMs = lastSeenEpochMs == null ? OptionalLong.empty() : lastSeenEpochMs;
    }

    public static PlayerStateSnapshot unknown(UUID playerId) {
        return new PlayerStateSnapshot(
                playerId, "", false, Optional.empty(),
                OptionalDouble.empty(), OptionalDouble.empty(), OptionalInt.empty(),
                OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty(), OptionalLong.empty());
    }
}
