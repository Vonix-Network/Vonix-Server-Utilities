package network.vonix.serverutilities.donation_ranks;

import network.vonix.serverutilities.VonixServerUtilities;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional LuckPerms bridge used only for permission-node enforcement and
 * native chat metadata. No LuckPerms types appear on this public surface, so
 * VSU remains safe when LuckPerms is absent.
 */
public final class LuckPermsBridge {
    private static final boolean LP_PRESENT;
    private static final AtomicBoolean warnedFailure = new AtomicBoolean(false);

    static {
        boolean present;
        try {
            Class.forName("net.luckperms.api.LuckPermsProvider", false,
                    LuckPermsBridge.class.getClassLoader());
            present = true;
        } catch (Throwable ignored) {
            present = false;
        }
        LP_PRESENT = present;
        if (!present) {
            VonixServerUtilities.LOGGER.info(
                    "[VonixSU] LuckPerms not detected on classpath; using vanilla op fallback.");
        }
    }

    private LuckPermsBridge() {}

    /** True when LuckPerms is available for optional permission/metadata checks. */
    public static boolean isPresent() {
        return LP_PRESENT;
    }

    /**
     * Read a player's cached chat metadata without exposing LuckPerms classes.
     * Returns empty when LuckPerms, the user, or the metadata is unavailable.
     */
    public static Optional<UserPrefixInfo> getUserPrefixInfo(UUID player) {
        if (!LP_PRESENT || player == null) return Optional.empty();
        try {
            return LuckPermsBridgeImpl.getUserPrefixInfo(player);
        } catch (LinkageError | RuntimeException t) {
            logFailureOnce("getUserPrefixInfo", t);
            return Optional.empty();
        }
    }

    /**
     * Synchronously check one VSU permission node. Fail-closed when the
     * optional provider is absent or the lookup cannot be completed.
     */
    public static boolean hasPermission(UUID player, String node) {
        if (!LP_PRESENT || player == null || node == null || node.isEmpty()) return false;
        try {
            return LuckPermsBridgeImpl.hasPermission(player, node);
        } catch (LinkageError | RuntimeException t) {
            logFailureOnce("hasPermission", t);
            return false;
        }
    }

    private static void logFailureOnce(String where, Throwable t) {
        if (warnedFailure.compareAndSet(false, true)) {
            VonixServerUtilities.LOGGER.warn(
                    "[VonixSU] LuckPerms bridge call '{}' failed (will not warn again this run): {}: {}",
                    where, t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /** Plain-Java snapshot of LuckPerms chat metadata. */
    public static final class UserPrefixInfo {
        public final String prefix;
        public final String suffix;
        public final String nameColor;

        public UserPrefixInfo(String prefix, String suffix, String nameColor) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.nameColor = nameColor;
        }
    }
}
