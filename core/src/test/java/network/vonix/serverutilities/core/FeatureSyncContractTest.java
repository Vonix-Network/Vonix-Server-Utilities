package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the in-game feature-sync command surface and the 1.20.1 / shared
 * EventHandler poller wiring. {@code /server-config} is a Venary HTTP path.
 */
class FeatureSyncContractTest {

    private static final String[] FEATURE_COMMANDS = {
            "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
            "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
            "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java"
    };

    private static final String[] MOD_COMMANDS = {
            "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/ModCommands.java",
            "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/ModCommands.java",
            "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/ModCommands.java",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/command/ModCommands.java",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/command/ModCommands.java"
    };

    private static final String[] EVENT_HANDLERS = {
            "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/listener/EventHandler.java",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/listener/EventHandler.java"
    };

    @Test
    void featureCommandTreeExposesListReloadEnableDisableAndStatus() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : FEATURE_COMMANDS) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("Commands.literal(\"feature\")"), relative);
            assertTrue(source.contains("Commands.literal(\"list\")"), relative);
            assertTrue(source.contains("Commands.literal(\"reload\")"), relative);
            assertTrue(source.contains("Commands.literal(\"enable\")"), relative);
            assertTrue(source.contains("Commands.literal(\"disable\")"), relative);
            assertTrue(source.contains("Commands.literal(\"status\")"), relative);
            assertTrue(source.contains("ServerConfigClient.requestImmediateFetch()"), relative);
            assertTrue(source.contains("enable Venary, then run /vonixsu feature reload"), relative);
            assertFalse(source.contains("Forcing /server-config fetch"), relative);
        }
    }

    @Test
    void vonixsuRegistersVersionStatusReloadAndFeatureTree() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : MOD_COMMANDS) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("Commands.literal(\"vonixsu\")"), relative);
            assertTrue(source.contains("Commands.literal(\"version\")"), relative);
            assertTrue(source.contains("Commands.literal(\"status\")"), relative);
            assertTrue(source.contains("Commands.literal(\"reload\")"), relative);
            assertTrue(source.contains("FeatureCommand.tree()"), relative);
            assertTrue(source.contains("ModConfig.INSTANCE.reload()"), relative);
            assertTrue(source.contains("VenaryClient.init(ModConfig.INSTANCE.getVenaryConfig())"), relative);
        }
    }

    @Test
    void eventHandlersStartTheFeaturePollerAndTickIt() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : EVENT_HANDLERS) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("ServerConfigClient.startPolling()"), relative);
            assertTrue(source.contains("ServerConfigClient.onTick"), relative);
            assertTrue(source.contains("FeatureRegistry.getInstance()"), relative);
            assertTrue(source.contains("VenaryClient.init"), relative);
            assertTrue(source.contains("KitManager.getInstance().loadFromJson"), relative);
        }
    }

    @Test
    void operatorDocsDoNotPresentServerConfigAsAMinecraftCommand() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String commands = Files.readString(root.resolve("docs/COMMANDS.md"));
        String readme = Files.readString(root.resolve("README.md"));
        assertTrue(commands.contains("/vonixsu feature reload"));
        assertTrue(commands.contains("/vonixsu feature list"));
        assertTrue(commands.contains("is not a Minecraft command"));
        assertTrue(readme.contains("/vonixsu feature reload"));
        assertFalse(readme.contains("run /server-config"));
        assertFalse(commands.contains("Use `/server-config`"));
        assertFalse(commands.contains("run `/server-config`"));
    }
}
