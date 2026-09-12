package network.vonix.serverutilities.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression for VSU-PUBLIC-API-RESIDUAL-001: production cells must not publish
 * {@code network.vonix.serverutilities.api} or a ServiceLoader inventory SPI,
 * while {@code /backsee} internals remain packaged.
 */
class ProductionJarBoundaryTest {

    private static final String PUBLIC_API_PREFIX = "network/vonix/serverutilities/api/";
    private static final String PUBLIC_SPI_DESCRIPTOR =
            "META-INF/services/network.vonix.serverutilities.api.InventoryProvider";
    private static final String INTERNAL_SPI_DESCRIPTOR =
            "META-INF/services/network.vonix.serverutilities.inventory.internal.InventoryProvider";

    private static final List<ProductionJar> PRODUCTION_JARS = List.of(
            jar("1.18.2-fabric",
                    "vonix_server_utils-1.18.2-fabric-forge-template/fabric/build/libs/vsu-1.18.2-fabric-2.2.0.jar",
                    false),
            jar("1.18.2-forge",
                    "vonix_server_utils-1.18.2-fabric-forge-template/forge/build/libs/vsu-1.18.2-forge-2.2.0.jar",
                    false),
            jar("1.19.2-fabric",
                    "vonix_server_utils-1.19.2-fabric-forge-template/fabric/build/libs/vsu-1.19.2-fabric-2.2.0.jar",
                    false),
            jar("1.19.2-forge",
                    "vonix_server_utils-1.19.2-fabric-forge-template/forge/build/libs/vsu-1.19.2-forge-2.2.0.jar",
                    false),
            jar("1.20.1-fabric",
                    "vonix_server_utils-1.20.1-fabric-forge-template/fabric/build/libs/vsu-1.20.1-fabric-2.2.0.jar",
                    false),
            jar("1.20.1-forge",
                    "vonix_server_utils-1.20.1-fabric-forge-template/forge/build/libs/vsu-1.20.1-forge-2.2.0.jar",
                    false),
            jar("1.21.1-fabric",
                    "vonix_server_utils-1.21.1-fabric-neoforgetemplate/fabric/build/libs/vsu-1.21.1-fabric-2.2.0.jar",
                    true),
            jar("1.21.1-neoforge",
                    "vonix_server_utils-1.21.1-fabric-neoforgetemplate/neoforge/build/libs/vsu-1.21.1-neoforge-2.2.0.jar",
                    true),
            jar("26.1.2-neoforge",
                    "vonix_server_utils-26.1.2-neoforge-template/build/libs/vsu-26.1.2-neoforge-2.2.0.jar",
                    true)
    );

    @Test
    void sourceTreeHasNoPublicApiPackageOrInventoryServiceDescriptor() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        List<String> hits = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> {
                String name = path.toString().replace('\\', '/');
                if (name.contains("/.git/") || name.contains("/build/") || name.contains("/src/test/")) {
                    return false;
                }
                return name.endsWith(".java")
                        || name.contains("/META-INF/services/")
                        || name.endsWith(".json")
                        || name.endsWith(".toml");
            }).forEach(path -> {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (relative.contains("/network/vonix/serverutilities/api/")) {
                    hits.add("public api path: " + relative);
                }
                if (relative.contains("/META-INF/services/") && relative.contains("InventoryProvider")) {
                    hits.add("service descriptor: " + relative);
                }
                if (relative.endsWith(".java")) {
                    try {
                        String source = Files.readString(path);
                        if (source.contains("package network.vonix.serverutilities.api")) {
                            hits.add("package declaration: " + relative);
                        }
                        if (source.contains("import network.vonix.serverutilities.api.")) {
                            hits.add("api import: " + relative);
                        }
                        if (source.contains("ServiceLoader.load(InventoryProvider")) {
                            hits.add("inventory ServiceLoader: " + relative);
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        if (!hits.isEmpty()) {
            fail("public inventory API/SPI still present in source:\n" + String.join("\n", hits));
        }
    }

    @Test
    void presentProductionJarsHaveNoPublicApiAndKeepBackseeInternals() throws IOException {
        Path root = ImportBoundaryTest.repoRoot();
        List<ProductionJar> present = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (ProductionJar jar : PRODUCTION_JARS) {
            Path path = root.resolve(jar.relativePath);
            if (Files.isRegularFile(path)) {
                present.add(jar);
            } else {
                missing.add(jar.cell + "=" + jar.relativePath);
            }
        }
        if (present.isEmpty()) {
            return;
        }
        if (present.size() != PRODUCTION_JARS.size()) {
            fail("partial production JAR set: present=" + present.size()
                    + " expected=" + PRODUCTION_JARS.size() + " missing=" + missing);
        }
        for (ProductionJar jar : PRODUCTION_JARS) {
            assertJarBoundary(root.resolve(jar.relativePath), jar);
        }
    }

    static void assertJarBoundary(Path jarPath, ProductionJar jar) throws IOException {
        assertTrue(Files.isRegularFile(jarPath), "missing production jar for " + jar.cell + ": " + jarPath);
        List<String> entries = zipEntries(jarPath);
        List<String> publicApi = entries.stream()
                .filter(name -> name.startsWith(PUBLIC_API_PREFIX))
                .toList();
        if (!publicApi.isEmpty()) {
            fail(jar.cell + " packages public API entries: " + publicApi);
        }
        assertFalse(entries.contains(PUBLIC_SPI_DESCRIPTOR),
                jar.cell + " packages removed public SPI descriptor");
        assertFalse(entries.contains(INTERNAL_SPI_DESCRIPTOR),
                jar.cell + " packages an inventory ServiceLoader descriptor");
        assertTrue(entries.contains("network/vonix/serverutilities/command/UtilityCommands.class"),
                jar.cell + " missing /backsee command class");
        assertTrue(entries.contains("network/vonix/serverutilities/inventory/internal/InventoryProvider.class"),
                jar.cell + " missing internal InventoryProvider");
        assertTrue(entries.contains("network/vonix/serverutilities/inventory/internal/InventoryProviderRegistry.class"),
                jar.cell + " missing internal InventoryProviderRegistry");
        assertTrue(entries.contains("network/vonix/serverutilities/inventory/internal/InventoryView.class"),
                jar.cell + " missing internal InventoryView");
        assertTrue(entries.contains("network/vonix/serverutilities/inventory/providers/CuriosInventoryProvider.class"),
                jar.cell + " missing CuriosInventoryProvider");
        assertTrue(entries.contains("network/vonix/serverutilities/inventory/providers/CapabilityInventoryProvider.class"),
                jar.cell + " missing CapabilityInventoryProvider");
        assertTrue(entries.contains("network/vonix/serverutilities/inventory/providers/LegacyNbtInventoryProvider.class"),
                jar.cell + " missing LegacyNbtInventoryProvider");
        if (jar.hasDataComponents) {
            assertTrue(entries.contains("network/vonix/serverutilities/inventory/providers/DataComponentsInventoryProvider.class"),
                    jar.cell + " missing DataComponentsInventoryProvider");
        }
        assertTrue(entries.contains("network/vonix/serverutilities/VonixServerUtilities.class"),
                jar.cell + " missing VonixServerUtilities");
    }

    private static List<String> zipEntries(Path jarPath) throws IOException {
        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                names.add(enumeration.nextElement().getName());
            }
        }
        return names;
    }

    private static ProductionJar jar(String cell, String relativePath, boolean hasDataComponents) {
        return new ProductionJar(cell, relativePath, hasDataComponents);
    }

    record ProductionJar(String cell, String relativePath, boolean hasDataComponents) {}
}
