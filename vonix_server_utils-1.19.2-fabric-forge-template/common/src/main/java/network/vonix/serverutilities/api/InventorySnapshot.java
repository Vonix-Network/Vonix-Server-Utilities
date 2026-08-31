package network.vonix.serverutilities.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only inventory projection. There are no write methods.
 *
 * @since 2.0.2
 */
public record InventorySnapshot(
        UUID playerId,
        String sourceId,
        String title,
        List<InventorySlotSnapshot> slots
) {
    public InventorySnapshot {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(title, "title");
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        if (sourceId.isBlank()) throw new IllegalArgumentException("source id must not be blank");
    }
}
