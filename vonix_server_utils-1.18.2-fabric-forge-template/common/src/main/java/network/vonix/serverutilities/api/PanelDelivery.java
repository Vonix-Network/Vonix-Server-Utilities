package network.vonix.serverutilities.api;

/**
 * Bounded event-bus offer result. Oldest events drop when the buffer is full.
 *
 * @since 2.0.2
 */
public enum PanelDelivery {
    ACCEPTED,
    DROPPED_OLDEST,
    DISABLED,
    REJECTED
}
