package network.vonix.serverutilities;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VersionConsistencyTest {
    private VersionConsistencyTest() {}

    public static void main(String[] args) throws Exception {
        Path template = WebsiteRemovalScanTest.templateRoot();
        String props = Files.readString(template.resolve("gradle.properties"), StandardCharsets.UTF_8);
        Matcher version = Pattern.compile("(?m)^mod_version\\s*=\\s*(\\S+)").matcher(props);
        require(version.find(), "mod_version missing");
        require("2.0.2".equals(version.group(1)), "mod_version must be 2.0.2, got " + version.group(1));
        require("2.0.2".equals(VonixServerUtilities.VERSION), "VERSION constant");
        System.out.println("VersionConsistencyTest: PASS");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
