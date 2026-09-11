package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicIdentityTest {

    @Test
    void supportedCellsSharePublicVersionAndModId() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String[] sources = {
                "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
                "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
                "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java",
                "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java"
        };
        for (String relative : sources) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("MOD_ID  = \"vonix_server_utilities\"")
                    || source.contains("MOD_ID = \"vonix_server_utilities\""), relative);
            assertTrue(source.contains("VERSION = \"2.1.1\""), relative);
            assertFalse(source.contains("VERSION = \"2.0.0\""), relative);
        }
    }

    @Test
    void featureSyncTextUsesTheMinecraftCommandAndNotTheHttpPath() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String[] commands = {
                "vonix_server_utils-1.18.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
                "vonix_server_utils-1.19.2-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
                "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java",
                "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/command/FeatureCommand.java"
        };
        for (String relative : commands) {
            String source = Files.readString(root.resolve(relative));
            assertTrue(source.contains("/vonixsu feature reload"), relative);
            assertTrue(source.contains("Venary feature poll"), relative);
            assertFalse(source.contains("/server-config poll"), relative);
            assertFalse(source.contains("Forcing /server-config fetch"), relative);
        }
    }

    @Test
    void versionCommandUsesTargetSpecificPlatformText() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String commands201 = Files.readString(root.resolve(
                "vonix_server_utils-1.20.1-fabric-forge-template/common/src/main/java/network/vonix/serverutilities/command/ModCommands.java"));
        String commands1211 = Files.readString(root.resolve(
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/command/ModCommands.java"));
        String commands2612 = Files.readString(root.resolve(
                "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/command/ModCommands.java"));
        assertTrue(commands201.contains("Platform: Architectury 1.20.1"),
                "1.20.1 must report its actual Minecraft target");
        assertFalse(commands201.contains("Platform: Architectury 1.21.1"),
                "1.20.1 must not report the 1.21.1 target");
        assertFalse(commands1211.contains("Platform: Architectury"),
                "1.21.1 must not report Architectury as the public platform");
        assertTrue(commands1211.contains("platformDisplay()"),
                "1.21.1 /vonixsu version must ask the loader adapter for platform text");
        assertTrue(commands2612.contains("Platform: NeoForge 26.1.2"));
        assertFalse(commands2612.contains("Architectury"));
    }

    @Test
    void loaderMetadataHasVonixIdentityAndNoPlaceholders() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String fabric = Files.readString(root.resolve(
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/fabric/src/main/resources/fabric.mod.json"));
        String neo1211 = Files.readString(root.resolve(
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/neoforge/src/main/resources/META-INF/neoforge.mods.toml"));
        String neo2612 = Files.readString(root.resolve(
                "vonix_server_utils-26.1.2-neoforge-template/src/main/resources/META-INF/neoforge.mods.toml"));

        for (String meta : new String[] { fabric, neo1211, neo2612 }) {
            assertTrue(meta.contains("vonix_server_utilities"));
            assertTrue(meta.contains("Vonix Network"));
            assertTrue(meta.contains("MIT"), "requested-cell metadata must use MIT, not a placeholder license");
            assertFalse(meta.contains("Insert License Here"));
            assertFalse(meta.contains("Me!"));
            assertFalse(meta.contains("example-mod"));
            assertFalse(meta.contains("CC0-1.0"));
        }
        assertFalse(fabric.contains("\"architectury\""));
        assertFalse(neo1211.contains("modId = \"architectury\""));
        assertFalse(neo2612.contains("architectury"));
    }
}
