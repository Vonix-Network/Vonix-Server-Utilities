package network.vonix.serverutilities.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Restricted teleport request. Actor must be the player that will move.
 * There is no coordinate field and no command field.
 *
 * @since 2.0.2
 */
public record PanelTeleportRequest(
        UUID actorPlayerId,
        UUID targetPlayerId,
        PanelTeleportTarget target
) {
    public PanelTeleportRequest {
        Objects.requireNonNull(actorPlayerId, "actorPlayerId");
        Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        Objects.requireNonNull(target, "target");
    }

    public static PanelTeleportRequest spawn(UUID playerId) {
        return new PanelTeleportRequest(playerId, playerId, new PanelTeleportTarget.Spawn());
    }

    public static PanelTeleportRequest home(UUID playerId, String homeName) {
        return new PanelTeleportRequest(playerId, playerId, new PanelTeleportTarget.Home(homeName));
    }
}
