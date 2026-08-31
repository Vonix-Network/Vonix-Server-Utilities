package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 1.21.1 EventHandler startup/started/stopping/stopped sequence as
 * the requested-cell parity reference. 26.1.2 stays native NeoForge.
 * Website/Venary HTTP, player-sync, server-config polling, and /link are
 * not part of the 2.0.2 public core.
 */
class LifecycleParityTest {

    private static final String[] STARTING = {
            "ModConfig.INSTANCE.load",
            "getDatabase().init",
            "TeleportManager.getInstance().hydrateFromDb()",
            "UtilityCommands.hydrateFromDb()",
            "ensureSchema",
            "createCrate(\"playtime\", \"playtime\")",
            "createCrate(\"event\", \"event\")",
            "recoverPendingClaims()",
            "KitManager.getInstance().loadFromJson",
            "PanelBootstrap.start",
            "CratePlaytimeTask.register()",
            "FeatureRegistry.getInstance()"
    };

    private static final String[] STARTED = {
            "ModerationBootstrap.serverStarted"
    };

    private static final String[] STOPPING = {
            "ModerationBootstrap.serverStopping"
    };

    private static final String[] STOPPED = {
            "TeleportManager.getInstance().clear()",
            "AdminManager.getInstance().clear()",
            "CratePlaytimeTask.clear()",
            "PanelBootstrap.stop()",
            "getInstance().shutdown()",
            "getDatabase().close()"
    };

    private static final String[] COMMANDS = {
            "ModCommands.register",
            "CrateCommands.register",
            "UtilityCommands.register",
            "WorldCommands.register",
            "ModerationBootstrap.registerCommands"
    };

    private static final String[] TICK = {
            "CratePlaytimeTask.onServerTick"
    };

    private static final String[] FORBIDDEN = {
            "VenaryClient",
            "PlayerSyncTask",
            "ServerConfigClient",
            "LinkCommands",
            "RankGroupSyncer",
            "RankSyncTask",
            "venary.shutdown"
    };

    @Test
    void requestedCellsShareEventHandlerLifecycleSequence() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String cell1211 = Files.readString(root.resolve(
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java"));
        String cell2612 = Files.readString(root.resolve(
                "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/listener/EventHandler.java"));

        assertContainsAll(cell1211, "1.21.1 EventHandler", STARTING);
        assertContainsAll(cell2612, "26.1.2 EventHandler", STARTING);
        assertContainsAll(cell1211, "1.21.1 EventHandler", STARTED);
        assertContainsAll(cell2612, "26.1.2 EventHandler", STARTED);
        assertContainsAll(cell1211, "1.21.1 EventHandler", STOPPING);
        assertContainsAll(cell2612, "26.1.2 EventHandler", STOPPING);
        assertContainsAll(cell1211, "1.21.1 EventHandler", STOPPED);
        assertContainsAll(cell2612, "26.1.2 EventHandler", STOPPED);
        assertContainsAll(cell1211, "1.21.1 EventHandler", COMMANDS);
        assertContainsAll(cell2612, "26.1.2 EventHandler", COMMANDS);
        assertContainsAll(cell1211, "1.21.1 EventHandler", TICK);
        assertContainsAll(cell2612, "26.1.2 EventHandler", TICK);
        assertAbsentAll(cell1211, "1.21.1 EventHandler", FORBIDDEN);
        assertAbsentAll(cell2612, "26.1.2 EventHandler", FORBIDDEN);
    }

    @Test
    void twentySixStaysNativeNeoForgeWithoutArchitecturyOrPlatformEventsHolder() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String handler = Files.readString(root.resolve(
                "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/listener/EventHandler.java"));
        assertTrue(handler.contains("net.neoforged.neoforge.common.NeoForge.EVENT_BUS"),
                "26.1.2 EventHandler must keep native NeoForge bus registration");
        assertTrue(handler.contains("ServerStartingEvent"));
        assertTrue(handler.contains("ServerStartedEvent"));
        assertTrue(handler.contains("ServerStoppingEvent"));
        assertTrue(handler.contains("ServerStoppedEvent"));
        assertFalse(handler.contains("dev.architectury"), "26.1.2 must not reintroduce Architectury");
        assertFalse(handler.contains("PlatformEvents.Holder"),
                "26.1.2 must not be forced onto PlatformEvents.Holder");
    }

    @Test
    void twentySixSharedNamedLifecycleClassesDoNotImportNeoForge() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        Path src = root.resolve("vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities");
        assertFalse(Files.exists(src.resolve("venary/PlayerSyncTask.java")),
                "26.1.2 must not keep the Venary player-sync task");
        assertFalse(Files.exists(src.resolve("features/ServerConfigClient.java")),
                "26.1.2 must not keep website server-config polling");
        List<Path> shared = List.of(
                src.resolve("crates/CratePlaytimeTask.java"),
                src.resolve("features/panel/PanelBootstrap.java"),
                src.resolve("moderation/ModerationBootstrap.java"));
        for (Path file : shared) {
            String text = Files.readString(file);
            assertFalse(text.contains("net.neoforged"),
                    file.getFileName() + " must not mix NeoForge event registration into shared-named classes");
            assertFalse(text.contains("dev.architectury"),
                    file.getFileName() + " must not import Architectury");
        }
    }

    private static void assertContainsAll(String source, String label, String[] tokens) {
        for (String token : tokens) {
            assertTrue(source.contains(token), label + " missing lifecycle token: " + token);
        }
    }

    private static void assertAbsentAll(String source, String label, String[] tokens) {
        for (String token : tokens) {
            assertFalse(source.contains(token), label + " still contains removed connector token: " + token);
        }
    }
}
