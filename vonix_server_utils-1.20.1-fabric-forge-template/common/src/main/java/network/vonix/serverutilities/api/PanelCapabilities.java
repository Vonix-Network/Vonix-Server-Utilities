package network.vonix.serverutilities.api;

import java.util.Objects;

/**
 * Fail-closed capability snapshot for an optional companion.
 *
 * <p>{@link #lastDeathIsHistory()} is always {@code false}.
 *
 * @since 2.0.2
 */
public record PanelCapabilities(
        boolean eventsEnabled,
        boolean statsEnabled,
        boolean lastDeathEnabled,
        boolean inventoryReadEnabled,
        boolean teleportActionsEnabled,
        boolean homesEnabled,
        boolean spawnEnabled,
        boolean lastDeathIsHistory,
        String vsuVersion,
        String minecraftVersion
) {

    public PanelCapabilities {
        Objects.requireNonNull(vsuVersion, "vsuVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        lastDeathIsHistory = false;
    }

    public boolean featureEnabled(String key) {
        if (PanelFeatureKeys.EVENTS.equals(key)) return eventsEnabled;
        if (PanelFeatureKeys.STATS.equals(key)) return statsEnabled;
        if (PanelFeatureKeys.DEATH_HISTORY.equals(key)) return lastDeathEnabled;
        if (PanelFeatureKeys.INVENTORY_READ.equals(key)) return inventoryReadEnabled;
        if (PanelFeatureKeys.TELEPORT_ACTIONS.equals(key)) return teleportActionsEnabled;
        if ("homes".equals(key)) return homesEnabled;
        if ("spawn".equals(key)) return spawnEnabled;
        return false;
    }
}
