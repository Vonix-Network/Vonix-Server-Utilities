package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the standalone startup, command, and shutdown sequence in every cell. */
class LifecycleParityTest {
    private static final List<String> EVENT_HANDLERS = List.of(
            "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/listener/EventHandler.java");

    private static final List<String> REQUIRED = List.of(
            "ModConfig.INSTANCE.load",
            "getDatabase().init",
            "TeleportManager.getInstance().hydrateFromDb()",
            "UtilityCommands.hydrateFromDb()",
            "ensureSchema",
            "createCrate(\"playtime\", \"playtime\")",
            "createCrate(\"event\", \"event\")",
            "recoverPendingClaims()",
            "KitManager.getInstance().loadFromJson",
            "CratePlaytimeTask.register()",
            "ModerationBootstrap.serverStarted",
            "ModerationBootstrap.serverStopping",
            "TeleportManager.getInstance().clear()",
            "AdminManager.getInstance().clear()",
            "CratePlaytimeTask.clear()",
            "getInstance().shutdown()",
            "getDatabase().close()",
            "ModCommands.register",
            "CrateCommands.register",
            "UtilityCommands.register",
            "WorldCommands.register",
            "ModerationBootstrap.registerCommands",
            "CratePlaytimeTask.onServerTick");

    private static final List<String> FORBIDDEN = List.of(
            "Venary", "venary", "ServerConfigClient", "FeatureRegistry", "FeatureCommand",
            "RankGroupSyncer", "RankSyncTask", "LinkCommands", "PlayerSyncTask");

    @Test
    void everyCellSharesStandaloneLifecycleSequence() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : EVENT_HANDLERS) {
            String source = Files.readString(root.resolve(relative));
            for (String token : REQUIRED) {
                assertTrue(source.contains(token), relative + " missing lifecycle token: " + token);
            }
            for (String token : FORBIDDEN) {
                assertFalse(source.contains(token), relative + " contains removed integration: " + token);
            }
        }
    }

    @Test
    void allCellsStillHydrateKitsAndKeepModerationHooks() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : EVENT_HANDLERS) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("KitManager.getInstance().loadFromJson"), relative);
            assertTrue(source.contains("ModerationBootstrap.init()"), relative);
            assertTrue(source.contains("livingDeath"), relative);
        }
    }
}
