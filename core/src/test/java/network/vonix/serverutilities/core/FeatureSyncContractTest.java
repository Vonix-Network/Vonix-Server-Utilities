package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Standalone-boundary contract for the nine packaged VSU cells. */
class FeatureSyncContractTest {
    private static final List<String> MAIN_ROOTS = List.of(
            "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities",
            "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities",
            "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities");

    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "Venary", "venary", "ServerConfigClient", "FeatureRegistry", "FeatureCommand",
            "RankGroupSyncer", "RankSyncTask", "LinkCommands", "PlayerSyncTask",
            "VonixPanel", "PanelCapabilities", "PanelTeleport", "server-config",
            "api.vonix", "java.net.http", "HttpClient");

    @Test
    void everyCellHasNoRemoteOrCompanionControlSource() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : MAIN_ROOTS) {
            Path sourceRoot = root.resolve(relative);
            assertTrue(Files.isDirectory(sourceRoot), relative);
            assertFalse(Files.exists(sourceRoot.resolve("venary")), relative);
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                    try {
                        String source = Files.readString(path);
                        for (String marker : FORBIDDEN_MARKERS) {
                            assertFalse(source.contains(marker), path + " contains removed marker " + marker);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }

    @Test
    void metaCommandKeepsStandaloneAdministrativeSurface() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : MAIN_ROOTS) {
            Path commands = root.resolve(relative).resolve("command/ModCommands.java");
            String source = Files.readString(commands);
            assertTrue(source.contains("Commands.literal(\"vonixsu\")"), commands.toString());
            assertTrue(source.contains("Commands.literal(\"version\")"), commands.toString());
            assertTrue(source.contains("Commands.literal(\"status\")"), commands.toString());
            assertTrue(source.contains("Commands.literal(\"reload\")"), commands.toString());
            assertFalse(source.contains("FeatureCommand"), commands.toString());
            assertFalse(source.contains("LinkCommands"), commands.toString());
        }
    }

    @Test
    void currentOperatorDocsDescribeStandaloneOnly() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String readme = Files.readString(root.resolve("README.md"));
        String commands = Files.readString(root.resolve("docs/COMMANDS.md"));
        assertTrue(readme.contains("2.2.0"));
        assertTrue(readme.contains("standalone"));
        for (String marker : List.of("Venary", "venary", "/link", "/unlink", "/server-config", "/vonixsu feature")) {
            assertFalse(readme.contains(marker), "README contains removed marker " + marker);
            assertFalse(commands.contains(marker), "COMMANDS.md contains removed marker " + marker);
        }
    }
}
