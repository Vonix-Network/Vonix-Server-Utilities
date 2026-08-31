package network.vonix.serverutilities.api;

import java.util.Optional;
import network.vonix.serverutilities.listener.PanelRegistry;

/**
 * Read-only access to the optional {@link VonixPanel} implementation.
 *
 * <p>Empty when VSU has not started. Binding is owned by VSU's internal
 * lifecycle and is not part of the outside-mod API.
 *
 * @since 2.0.2
 */
public final class VonixPanels {

    private VonixPanels() {}

    public static Optional<VonixPanel> current() {
        return PanelRegistry.current();
    }
}
