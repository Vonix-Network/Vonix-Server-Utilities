package network.vonix.serverutilities.api;

import java.util.Optional;


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
        try {
            Class<?> registry = Class.forName(
                    "network.vonix.serverutilities.listener.PanelRegistry",
                    false,
                    VonixPanels.class.getClassLoader());
            java.lang.reflect.Method current = registry.getDeclaredMethod("current");
            current.setAccessible(true);
            Object value = current.invoke(null);
            if (!(value instanceof Optional<?> optional)) return Optional.empty();
            return optional.filter(VonixPanel.class::isInstance).map(VonixPanel.class::cast);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }
}
