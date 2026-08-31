package network.vonix.serverutilities.api;

import java.util.Locale;
import java.util.Objects;

/**
 * Closed teleport destination set. Arbitrary coordinates and commands are not representable.
 *
 * @since 2.0.2
 */
public sealed interface PanelTeleportTarget
        permits PanelTeleportTarget.Spawn, PanelTeleportTarget.Home {

    record Spawn() implements PanelTeleportTarget {}

    record Home(String name) implements PanelTeleportTarget {
        public Home {
            Objects.requireNonNull(name, "name");
            name = name.trim().toLowerCase(Locale.ROOT);
            if (name.isBlank()) throw new IllegalArgumentException("home name must not be blank");
        }
    }
}
