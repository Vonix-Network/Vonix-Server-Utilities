package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicIdentityTest {
    private static final List<String> VERSION_SOURCES = List.of(
            "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
            "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
            "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java");

    private static final List<String> METADATA = List.of(
            "vonix_server_utils-1.18.2-fabric-forge-template/fabric/src/main/resources/fabric.mod.json",
            "vonix_server_utils-1.18.2-fabric-forge-template/forge/src/main/resources/META-INF/mods.toml",
            "vonix_server_utils-1.19.2-fabric-forge-template/fabric/src/main/resources/fabric.mod.json",
            "vonix_server_utils-1.19.2-fabric-forge-template/forge/src/main/resources/META-INF/mods.toml",
            "vonix_server_utils-1.20.1-fabric-forge-template/fabric/src/main/resources/fabric.mod.json",
            "vonix_server_utils-1.20.1-fabric-forge-template/forge/src/main/resources/META-INF/mods.toml",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/fabric/src/main/resources/fabric.mod.json",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate/neoforge/src/main/resources/META-INF/neoforge.mods.toml",
            "vonix_server_utils-26.1.2-neoforge-template/src/main/resources/META-INF/neoforge.mods.toml");

    @Test
    void allPublicRuntimeIdentitiesAreExactly220() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : VERSION_SOURCES) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("MOD_ID") && source.contains("vonix_server_utilities"), relative);
            assertTrue(source.contains("VERSION = \"2.2.0\""), relative);
            assertFalse(source.contains("2.1.2"), relative);
        }
    }

    @Test
    void allLoaderMetadataIsVersionTemplatedAndStandalone() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : METADATA) {
            Path path = root.resolve(relative);
            byte[] raw = Files.readAllBytes(path);
            assertFalse(raw.length >= 3 && raw[0] == (byte) 0xEF && raw[1] == (byte) 0xBB && raw[2] == (byte) 0xBF,
                    relative + " must be BOM-free");
            String text = Files.readString(path);
            assertTrue(text.contains("vonix_server_utilities"), relative);
            assertTrue(text.contains("${version}"), relative);
            assertTrue(text.contains("license"), relative);
            assertFalse(text.contains("Venary"), relative);
            assertFalse(text.contains("Me!"), relative);
            assertFalse(text.contains("Insert License Here"), relative);
            assertFalse(text.contains("CC0-1.0"), relative);
        }
    }

    @Test
    void removedPublicApiPackageIsAbsentAndInternalInventoryRemains() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String relative : VERSION_SOURCES) {
            Path source = root.resolve(relative).getParent();
            Path api = source.resolve("api");
            assertFalse(Files.exists(api), "public api package must be absent: " + api);
            Path internal = source.resolve("inventory").resolve("internal");
            assertTrue(Files.exists(internal.resolve("InventoryProvider.java")), internal.toString());
            assertTrue(Files.exists(internal.resolve("InventoryProviderRegistry.java")), internal.toString());
            assertTrue(Files.exists(internal.resolve("InventoryView.java")), internal.toString());
            assertFalse(Files.exists(internal.resolve("VonixPanel.java")), internal.toString());
            assertFalse(Files.exists(internal.resolve("PanelTeleportRequest.java")), internal.toString());
        }
    }
}
