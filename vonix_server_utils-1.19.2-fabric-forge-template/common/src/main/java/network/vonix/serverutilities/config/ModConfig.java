package network.vonix.serverutilities.config;

import network.vonix.serverutilities.VonixServerUtilities;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Simple properties-file config for Vonix Server Utilities.
 * File location: config/vonix_server_utilities.properties
 */
public final class ModConfig {
    public static final ModConfig INSTANCE = new ModConfig();

    private int maxHomes = 5;
    private int tpaTimeoutSeconds = 120;
    private int deathBackDelaySeconds = 0;
    private int playtimeKeyIntervalMinutes = 60;

    private boolean panelEventsEnabled = false;
    private boolean panelStatsEnabled = false;
    private boolean panelDeathHistoryEnabled = true;
    private boolean panelInventoryReadEnabled = false;
    private boolean panelTeleportActionsEnabled = false;
    private int spiTeleportCooldownSeconds = 3;


    private ModConfig() {}

    /** Remember the config dir so {@link #reload()} can re-read without arguments. */
    private Path configDir;

    /**
     * Re-read the properties file from the previously-cached config directory.
     * Safe to call from any thread; refreshes all fields in place.
     * @return true if reload completed (file present, or defaults rewritten); false if load() was never called yet.
     */
    public boolean reload() {
        if (configDir == null) {
            VonixServerUtilities.LOGGER.warn("[VonixSU] ModConfig.reload() called before initial load()");
            return false;
        }
        load(configDir);
        return true;
    }

    public void load(Path configDir) {
        this.configDir = configDir;
        Path file = configDir.resolve("vonix_server_utilities.properties");
        if (!Files.exists(file)) {
            writeDefaults(file);
            return;
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file)) {
            p.load(r);
        } catch (IOException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] Failed to read config, using defaults", e);
            return;
        }
        maxHomes              = intOf(p, "max_homes", 5);
        tpaTimeoutSeconds     = intOf(p, "tpa_timeout_seconds", 120);
        deathBackDelaySeconds = intOf(p, "death_back_delay_seconds", 0);
        playtimeKeyIntervalMinutes = Math.max(1, intOf(p, "playtime_key_interval_minutes", 60));

        panelEventsEnabled = boolOf(p, "panel_events_enabled", false);
        panelStatsEnabled = boolOf(p, "panel_stats_enabled", false);
        panelDeathHistoryEnabled = boolOf(p, "panel_death_history_enabled", true);
        panelInventoryReadEnabled = boolOf(p, "panel_inventory_read_enabled", false);
        panelTeleportActionsEnabled = boolOf(p, "panel_teleport_actions_enabled", false);
        spiTeleportCooldownSeconds = Math.max(0, intOf(p, "spi_teleport_cooldown_seconds", 3));


        VonixServerUtilities.LOGGER.info("[VonixSU] Config loaded (max_homes={}, tpa_timeout={}s, death_back_delay={}s)",
                maxHomes, tpaTimeoutSeconds, deathBackDelaySeconds);
    }

    private void writeDefaults(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write("""
                        # Vonix Server Utilities Configuration
                        # Maximum homes per player
                        max_homes=5
                        # Seconds before a TPA request expires
                        tpa_timeout_seconds=120
                        # Seconds after death before /backdeath is usable (0 = instant)
                        death_back_delay_seconds=0

                        # Minutes of playtime required for each automatic Playtime Key.
                        playtime_key_interval_minutes=60

                        # ─── Outside-mod SPI gates (fail-closed except last-death) ──
                        panel_events_enabled=false
                        panel_stats_enabled=false
                        panel_death_history_enabled=true
                        panel_inventory_read_enabled=false
                        panel_teleport_actions_enabled=false
                        spi_teleport_cooldown_seconds=3
                        """);
            }
        } catch (IOException e) {
            VonixServerUtilities.LOGGER.error("[VonixSU] Failed to write default config", e);
        }
    }

    private static int intOf(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static boolean boolOf(Properties p, String key, boolean def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("yes") || v.equals("1") || v.equals("on");
    }

    private static String strOf(Properties p, String key, String def) {
        String v = p.getProperty(key);
        if (v == null) return def;
        v = v.trim();
        return v.isEmpty() ? def : v;
    }

    public int getMaxHomes()              { return maxHomes; }
    public long getTpaTimeoutMs()         { return tpaTimeoutSeconds * 1000L; }
    public int getDeathBackDelaySeconds() { return deathBackDelaySeconds; }
    public int getPlaytimeKeyIntervalMinutes() { return playtimeKeyIntervalMinutes; }

    public PanelGates panelGates() {
        return new PanelGates(
                panelEventsEnabled,
                panelStatsEnabled,
                panelDeathHistoryEnabled,
                panelInventoryReadEnabled,
                panelTeleportActionsEnabled);
    }

    public int getSpiTeleportCooldownSeconds() { return spiTeleportCooldownSeconds; }

    public void resetForTesting() {
        maxHomes = 5;
        tpaTimeoutSeconds = 120;
        deathBackDelaySeconds = 0;
        playtimeKeyIntervalMinutes = 60;
        panelEventsEnabled = false;
        panelStatsEnabled = false;
        panelDeathHistoryEnabled = true;
        panelInventoryReadEnabled = false;
        panelTeleportActionsEnabled = false;
        spiTeleportCooldownSeconds = 3;
        configDir = null;
    }

    public record PanelGates(
            boolean eventsEnabled,
            boolean statsEnabled,
            boolean deathHistoryEnabled,
            boolean inventoryReadEnabled,
            boolean teleportActionsEnabled) {}
}
