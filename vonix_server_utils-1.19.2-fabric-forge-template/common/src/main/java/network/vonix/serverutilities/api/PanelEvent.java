package network.vonix.serverutilities.api;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable lifecycle event. Delivered on the Minecraft server thread.
 *
 * @since 2.0.2
 */
public record PanelEvent(
        PanelEventKind kind,
        UUID playerId,
        String username,
        long occurredAtEpochMs,
        Optional<String> detail
) {
    public PanelEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(playerId, "playerId");
        username = username == null ? "" : username;
        detail = detail == null ? Optional.empty() : detail;
    }
}
