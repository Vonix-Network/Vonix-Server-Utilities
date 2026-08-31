package network.vonix.serverutilities.api;

/**
 * Lifecycle events the outside-mod SPI may emit.
 *
 * @since 2.0.2
 */
public enum PanelEventKind {
    PLAYER_JOIN,
    PLAYER_QUIT,
    PLAYER_DEATH,
    TELEPORT_COMPLETED
}
