package network.vonix.serverutilities.api;

import java.util.Objects;

/**
 * Explicit result of {@link VonixPanel#requestTeleport(PanelTeleportRequest)}.
 *
 * @since 2.0.2
 */
public record PanelTeleportResult(PanelTeleportStatus status, String message) {
    public PanelTeleportResult {
        Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }

    public boolean accepted() {
        return status == PanelTeleportStatus.ACCEPTED || status == PanelTeleportStatus.QUEUED;
    }

    public static PanelTeleportResult of(PanelTeleportStatus status, String message) {
        return new PanelTeleportResult(status, message);
    }
}
