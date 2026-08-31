package network.vonix.serverutilities.api;

/**
 * Result of a restricted outside-mod teleport request.
 *
 * @since 2.0.2
 */
public enum PanelTeleportStatus {
    ACCEPTED,
    QUEUED,
    DISABLED,
    WRONG_IDENTITY,
    PLAYER_OFFLINE,
    HOME_MISSING,
    FEATURE_DISABLED,
    WORLD_UNAVAILABLE,
    PERMISSION_DENIED,
    NOT_ON_SERVER_THREAD,
    COOLDOWN,
    REJECTED
}
