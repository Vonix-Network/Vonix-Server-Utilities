package network.vonix.serverutilities.api;

/**
 * Observer for bounded lifecycle events.
 * Callbacks run on the Minecraft server thread and must return quickly.
 *
 * @since 2.0.2
 */
@FunctionalInterface
public interface PanelListener {
    void onPanelEvent(PanelEvent event);
}
