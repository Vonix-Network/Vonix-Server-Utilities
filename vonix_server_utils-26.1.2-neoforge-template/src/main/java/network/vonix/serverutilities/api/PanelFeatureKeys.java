package network.vonix.serverutilities.api;

/**
 * Local feature-flag keys for the outside-mod SPI.
 *
 * <p>Companion presence does not enable these keys. Defaults are fail-closed
 * except last-death exposure, which is already stored for {@code /backdeath}.
 *
 * @since 2.0.2
 */
public final class PanelFeatureKeys {

    public static final String EVENTS = "panel_events_enabled";
    public static final String STATS = "panel_stats_enabled";
    public static final String DEATH_HISTORY = "panel_death_history_enabled";
    public static final String INVENTORY_READ = "panel_inventory_read_enabled";
    public static final String TELEPORT_ACTIONS = "panel_teleport_actions_enabled";

    private PanelFeatureKeys() {}
}
