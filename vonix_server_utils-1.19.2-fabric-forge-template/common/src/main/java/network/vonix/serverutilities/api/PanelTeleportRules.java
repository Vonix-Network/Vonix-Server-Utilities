package network.vonix.serverutilities.api;

import java.util.Objects;

/**
 * Pure restricted-teleport rules. Does not teleport and does not touch Minecraft types.
 *
 * @since 2.0.2
 */
public final class PanelTeleportRules {

    private PanelTeleportRules() {}

    public static PanelTeleportResult evaluate(
            PanelTeleportRequest request,
            boolean teleportActionsEnabled,
            boolean playerOnline,
            boolean spawnFeatureEnabled,
            boolean homesFeatureEnabled,
            boolean namedHomeExists,
            boolean permissionGranted,
            boolean cooldownActive) {
        Objects.requireNonNull(request, "request");
        if (!teleportActionsEnabled) {
            return PanelTeleportResult.of(PanelTeleportStatus.DISABLED, "outside-mod teleport actions are disabled");
        }
        if (!request.actorPlayerId().equals(request.targetPlayerId())) {
            return PanelTeleportResult.of(PanelTeleportStatus.WRONG_IDENTITY, "actor must be the target player");
        }
        if (!playerOnline) {
            return PanelTeleportResult.of(PanelTeleportStatus.PLAYER_OFFLINE, "player is not online");
        }
        if (!permissionGranted) {
            return PanelTeleportResult.of(PanelTeleportStatus.PERMISSION_DENIED, "missing spawn or home permission");
        }
        if (cooldownActive) {
            return PanelTeleportResult.of(PanelTeleportStatus.COOLDOWN, "teleport request is on cooldown");
        }
        if (request.target() instanceof PanelTeleportTarget.Spawn) {
            if (!spawnFeatureEnabled) {
                return PanelTeleportResult.of(PanelTeleportStatus.FEATURE_DISABLED, "spawn feature is disabled");
            }
            return PanelTeleportResult.of(PanelTeleportStatus.ACCEPTED, "spawn");
        }
        if (request.target() instanceof PanelTeleportTarget.Home) {
            if (!homesFeatureEnabled) {
                return PanelTeleportResult.of(PanelTeleportStatus.FEATURE_DISABLED, "homes feature is disabled");
            }
            if (!namedHomeExists) {
                return PanelTeleportResult.of(PanelTeleportStatus.HOME_MISSING, "named home does not exist");
            }
            return PanelTeleportResult.of(PanelTeleportStatus.ACCEPTED, "home");
        }
        return rejectUnsupportedTarget();
    }

    public static PanelTeleportResult evaluate(
            PanelTeleportRequest request,
            boolean teleportActionsEnabled,
            boolean playerOnline,
            boolean spawnFeatureEnabled,
            boolean homesFeatureEnabled,
            boolean namedHomeExists) {
        return evaluate(request, teleportActionsEnabled, playerOnline, spawnFeatureEnabled,
                homesFeatureEnabled, namedHomeExists, true, false);
    }

    public static PanelTeleportResult rejectArbitraryCoordinates() {
        return PanelTeleportResult.of(
                PanelTeleportStatus.REJECTED, "arbitrary coordinates are not a panel teleport target");
    }

    public static PanelTeleportResult rejectArbitraryCommand() {
        return PanelTeleportResult.of(
                PanelTeleportStatus.REJECTED, "arbitrary commands are not a panel teleport target");
    }

    public static PanelTeleportResult rejectUnsupportedTarget() {
        return PanelTeleportResult.of(
                PanelTeleportStatus.REJECTED, "unsupported panel teleport target");
    }
}
