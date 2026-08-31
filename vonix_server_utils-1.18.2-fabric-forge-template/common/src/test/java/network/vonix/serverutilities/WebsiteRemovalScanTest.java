package network.vonix.serverutilities;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Fails if executable Venary/website/API-key connector strings remain. */
public final class WebsiteRemovalScanTest {
    private WebsiteRemovalScanTest() {}

    private static final String[] FORBIDDEN = {
            "api.vonix.network",
            "vonix.network/account",
            "venary_api_key",
            "venary_api_base",
            "x-api-key",
            "java.net.http.HttpClient",
            "/minecraft/link",
            "/minecraft/server-config",
            "/minecraft/players/sync",
            "/minecraft/login",
            "/minecraft/verify-player",
            "/minecraft/audit"
    };

    private static final String[] API_FORBIDDEN = {
            "java.sql",
            "javax.sql",
            "HttpClient",
            "ProcessBuilder",
            "Runtime.getRuntime",
            "apiKey",
            "api_key",
            "jdbc:",
            "PreparedStatement",
            "DriverManager"
    };

    public static void main(String[] args) throws Exception {
        Path root = templateRoot();
        List<String> hits = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile)
                    .filter(WebsiteRemovalScanTest::isScanned)
                    .forEach(path -> scan(root, path, hits));
        }
        if (!hits.isEmpty()) {
            throw new AssertionError("stale website strings: " + hits);
        }
        List<String> apiHits = scanPublicApi(root);
        if (!apiHits.isEmpty()) {
            throw new AssertionError("public api escape hatch tokens: " + apiHits);
        }
        require(!Files.isDirectory(root.resolve("common/src/main/java/network/vonix/serverutilities/venary"))
                        && !Files.isDirectory(root.resolve("src/main/java/network/vonix/serverutilities/venary")),
                "venary package must not exist");
        System.out.println("WebsiteRemovalScanTest: PASS root=" + root);
    }

    private static List<String> scanPublicApi(Path root) throws Exception {
        Path api = root.resolve("common/src/main/java/network/vonix/serverutilities/api");
        if (!Files.isDirectory(api)) {
            api = root.resolve("src/main/java/network/vonix/serverutilities/api");
        }
        List<String> hits = new ArrayList<>();
        if (!Files.isDirectory(api)) {
            hits.add("api package missing");
            return hits;
        }
        try (Stream<Path> walk = Files.walk(api)) {
            walk.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String text = Files.readString(path, StandardCharsets.UTF_8);
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    for (String needle : API_FORBIDDEN) {
                        if (text.contains(needle)) hits.add(relative + " :: " + needle);
                    }
                } catch (Exception e) {
                    hits.add(path + " unreadable");
                }
            });
        }
        return hits;
    }

    private static void scan(Path root, Path path, List<String> hits) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            hits.add(relative + " (unreadable)");
            return;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String needle : FORBIDDEN) {
            if (lower.contains(needle.toLowerCase(Locale.ROOT))) {
                hits.add(relative + " :: " + needle);
            }
        }
    }

    private static boolean isScanned(Path path) {
        String name = path.toString().replace('\\', '/');
        if (name.contains("/build/") || name.contains("/.gradle/") || name.contains("/src/test/")) {
            return false;
        }
        return name.endsWith(".java")
                || name.endsWith(".properties")
                || name.endsWith(".json")
                || name.endsWith(".toml")
                || name.endsWith(".gradle");
    }

    static Path templateRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        for (int i = 0; i < 8; i++) {
            if (Files.isRegularFile(dir.resolve("gradle.properties"))) return dir;
            if (dir.getFileName() != null && dir.getFileName().toString().equals("common")
                    && Files.isRegularFile(dir.getParent().resolve("gradle.properties"))) {
                return dir.getParent();
            }
            Path parent = dir.getParent();
            if (parent == null) break;
            dir = parent;
        }
        throw new IllegalStateException("could not locate template root from " + System.getProperty("user.dir"));
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
