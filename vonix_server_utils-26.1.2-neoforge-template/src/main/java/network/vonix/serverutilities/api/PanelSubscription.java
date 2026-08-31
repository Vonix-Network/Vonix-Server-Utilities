package network.vonix.serverutilities.api;

/**
 * Handle returned by {@link VonixPanel#registerListener(PanelListener)}.
 *
 * @since 2.0.2
 */
public interface PanelSubscription extends AutoCloseable {
    boolean isActive();

    @Override
    void close();
}
