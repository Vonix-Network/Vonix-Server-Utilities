package network.vonix.serverutilities;

import network.vonix.serverutilities.api.PanelFeatureKeys;
import network.vonix.serverutilities.api.VonixPanels;
import network.vonix.serverutilities.database.Database;
import network.vonix.serverutilities.features.FeatureRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone VSU: no companion required, VonixCore is not the active migration. */
public final class VsuStandaloneContractTest {
    private VsuStandaloneContractTest() {}

    public static void main(String[] args) throws Exception {
        FeatureRegistry.resetForTesting();
        require(VonixPanels.current().isEmpty(), "SPI must be empty before bind");
        FeatureRegistry registry = FeatureRegistry.getInstance();
        require(registry.isEnabled("homes"), "homes stay fail-open");
        require(!registry.isEnabled(PanelFeatureKeys.EVENTS), "panel events fail-closed");
        require(!registry.isEnabled(PanelFeatureKeys.TELEPORT_ACTIONS), "teleport actions fail-closed");
        require(registry.isEnabled(PanelFeatureKeys.DEATH_HISTORY), "last-death defaults on");

        Path root = WebsiteRemovalScanTest.templateRoot();
        Path db = find(root, "database/Database.java");
        String dbText = Files.readString(db, StandardCharsets.UTF_8);
        require(dbText.contains("attemptVsuRecovery()"), "VSU-to-VSU recovery must be present");
        require(dbText.contains("findLegacyVsuDatabases()"), "retained VSU backup discovery must remain");
        require(!dbText.contains("attemptVonixCoreMigration("), "VonixCore migration must not remain as a method");
        require(!dbText.contains("attemptVonixCoreMigration(server)"), "VonixCore must not be invoked");
        require(dbText.contains("VSU-to-VSU") || dbText.contains("retained VSU"), "docs in source must describe VSU recovery");

        Path event = find(root, "listener/EventHandler.java");
        String ev = Files.readString(event, StandardCharsets.UTF_8);
        require(!ev.contains("VenaryClient"), "EventHandler must not reference VenaryClient");
        require(!ev.contains("PlayerSyncTask"), "EventHandler must not start player sync");
        require(!ev.contains("ServerConfigClient"), "EventHandler must not poll server-config");
        require(!ev.contains("LinkCommands"), "EventHandler must not register link commands");
        require(!ev.contains("RankSyncTask"), "EventHandler must not sync remote ranks");
        require(ev.contains("PanelBootstrap"), "EventHandler must bind the outside-mod SPI");

        require(!classExists("network.vonix.serverutilities.venary.VenaryClient"),
                "VenaryClient class must not exist");
        require(!classExists("network.vonix.serverutilities.features.ServerConfigClient"),
                "ServerConfigClient class must not exist");
        FeatureRegistry.resetForTesting();
        System.out.println("VsuStandaloneContractTest: PASS");
    }

    private static Path find(Path root, String suffix) throws Exception {
        try (var walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().replace('\\', '/').endsWith(suffix))
                    .filter(p -> !p.toString().contains("/build/"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("missing " + suffix));
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
