package network.vonix.serverutilities.features.panel;

import java.util.Optional;
import network.vonix.serverutilities.api.VonixPanel;

/** Internal lifecycle registry; only read access is exposed to companions. */
public final class PanelRegistry {

    private static volatile VonixPanel instance;

    private PanelRegistry() {}

    public static Optional<VonixPanel> current() {
        return Optional.ofNullable(instance);
    }

    static void bind(VonixPanel panel) {
        instance = panel;
    }

    static void unbind(VonixPanel panel) {
        if (instance == panel) {
            instance = null;
        }
    }
}
