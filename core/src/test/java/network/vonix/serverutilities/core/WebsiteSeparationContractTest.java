package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class WebsiteSeparationContractTest {

    private static final String[] LANES = {
            "vonix_server_utils-1.18.2-fabric-forge-template",
            "vonix_server_utils-1.19.2-fabric-forge-template",
            "vonix_server_utils-1.20.1-fabric-forge-template",
            "vonix_server_utils-1.21.1-fabric-neoforgetemplate",
            "vonix_server_utils-26.1.2-neoforge-template"
    };

    private static final String[] FORBIDDEN = {
            "venary_api_key",
            "x-api-key",
            "java.net.http.HttpClient",
            "api.vonix.network"
    };

    @Test
    void everyLaneHasNoVenaryPackageAndNoApiKeyConfig() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        List<String> hits = new ArrayList<>();
        for (String lane : LANES) {
            Path laneRoot = root.resolve(lane);
            assertTrue(Files.isDirectory(laneRoot), lane);
            assertFalse(Files.isDirectory(laneRoot.resolve("common/src/main/java/network/vonix/serverutilities/venary"))
                    || Files.isDirectory(laneRoot.resolve("src/main/java/network/vonix/serverutilities/venary")));
            try (Stream<Path> walk = Files.walk(laneRoot)) {
                walk.filter(p -> p.toString().endsWith(".java") || p.toString().endsWith(".properties"))
                        .filter(p -> !p.toString().contains("/build/") && !p.toString().contains("/src/test/"))
                        .forEach(p -> {
                            try {
                                String text = Files.readString(p, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                                for (String needle : FORBIDDEN) {
                                    if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                                        hits.add(root.relativize(p) + " :: " + needle);
                                    }
                                }
                            } catch (IOException e) {
                                hits.add(p + " unreadable");
                            }
                        });
            }
        }
        if (!hits.isEmpty()) fail("website connector leftovers:\n" + String.join("\n", hits));
    }

    @Test
    void everyLaneKeepsVsuRecoveryAndNotVonixCoreStartup() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        for (String lane : LANES) {
            Path db = find(root.resolve(lane), "database/Database.java");
            String text = Files.readString(db, StandardCharsets.UTF_8);
            assertTrue(text.contains("attemptVsuRecovery()"), lane);
            assertTrue(text.contains("findLegacyVsuDatabases()"), lane);
            assertFalse(text.contains("attemptVonixCoreMigration("), lane);
            assertTrue(text.contains("VonixPanel") || Files.exists(spi(root.resolve(lane), "api/VonixPanel.java")));
        }
    }

    @Test
    void publicVersionIs202() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        String common = Files.readString(root.resolve(
                "vonix_server_utils-1.21.1-fabric-neoforgetemplate/common/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java"));
        String neo = Files.readString(root.resolve(
                "vonix_server_utils-26.1.2-neoforge-template/src/main/java/network/vonix/serverutilities/VonixServerUtilities.java"));
        assertTrue(common.contains("VERSION = \"2.0.2\""));
        assertTrue(neo.contains("VERSION = \"2.0.2\""));
        String readme = Files.readString(root.resolve("README.md"));
        assertTrue(readme.contains("2.0.2"));
        assertFalse(readme.contains("Venary site integration"));
        String changelog = Files.readString(root.resolve("CHANGELOG.md"));
        assertTrue(changelog.contains("## [2.0.2]"));
    }

    private static Path spi(Path lane, String rel) {
        Path a = lane.resolve("common/src/main/java/network/vonix/serverutilities").resolve(rel);
        if (Files.exists(a)) return a;
        return lane.resolve("src/main/java/network/vonix/serverutilities").resolve(rel);
    }

    private static Path find(Path root, String suffix) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().replace('\\', '/').endsWith(suffix))
                    .filter(p -> !p.toString().contains("/build/"))
                    .findFirst()
                    .orElseThrow();
        }
    }
}
