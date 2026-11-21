package network.vonix.serverutilities.pvp;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.config.ModConfig;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the optional per-player PvP state and the server-wide vanilla PvP gate.
 *
 * <p>The server-wide gate is deliberately forced on while VSU is active. The
 * per-player state below is the actual opt-in boundary: player-versus-player
 * damage is allowed only when both players have PvP enabled.</p>
 */
public final class PvpManager {
    private static final Object LOCK = new Object();
    private static final Map<UUID, Boolean> STATES = new HashMap<>();
    private static final Map<UUID, Long> REVISIONS = new HashMap<>();

    private PvpManager() {}

    /** Enable vanilla PvP and make the requirement durable in server.properties. */
    public static void onServerStarting(MinecraftServer server) {
        server.setPvpAllowed(true);
        ensureServerProperty(server);
    }

    /** Seed a joining player from the configured default, then restore a saved choice. */
    public static void onPlayerJoin(ServerPlayer player) {
        UUID uuid = player.getUUID();
        long revision;
        synchronized (LOCK) {
            revision = bumpRevision(uuid);
            STATES.put(uuid, ModConfig.INSTANCE.getPvpDefaultEnabled());
        }

        VonixServerUtilities.dbAsync(() -> {
            Boolean saved = VonixServerUtilities.getInstance().getDatabase().getPvpPreference(uuid);
            if (saved == null) return;
            synchronized (LOCK) {
                // Do not overwrite a toggle or a newer reconnect with a stale DB read.
                if (REVISIONS.getOrDefault(uuid, -1L) == revision) {
                    STATES.put(uuid, saved);
                }
            }
        });
    }

    /** Toggle and persist the caller's own PvP state. */
    public static boolean toggle(ServerPlayer player) {
        UUID uuid = player.getUUID();
        boolean enabled;
        synchronized (LOCK) {
            enabled = !STATES.getOrDefault(uuid, ModConfig.INSTANCE.getPvpDefaultEnabled());
            STATES.put(uuid, enabled);
            bumpRevision(uuid);
        }
        VonixServerUtilities.dbAsync(() ->
                VonixServerUtilities.getInstance().getDatabase().setPvpPreference(uuid, enabled));
        return enabled;
    }

    public static boolean isEnabled(ServerPlayer player) {
        synchronized (LOCK) {
            return STATES.getOrDefault(player.getUUID(), ModConfig.INSTANCE.getPvpDefaultEnabled());
        }
    }

    /**
     * Returns true when a player-versus-player hit must be blocked.
     * Non-player damage (mobs, projectiles without a player owner, fall, etc.)
     * is intentionally unaffected.
     */
    public static boolean shouldCancelDamage(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayer victimPlayer)) return false;
        Entity attacker = source.getEntity();
        if (!(attacker instanceof ServerPlayer attackerPlayer)) return false;
        if (attackerPlayer.getUUID().equals(victimPlayer.getUUID())) return false;
        return !isEnabled(attackerPlayer) || !isEnabled(victimPlayer);
    }

    public static void onPlayerQuit(UUID uuid) {
        synchronized (LOCK) {
            bumpRevision(uuid);
            STATES.remove(uuid);
        }
    }

    public static void onServerStopped() {
        synchronized (LOCK) {
            STATES.clear();
            REVISIONS.clear();
        }
    }

    private static long bumpRevision(UUID uuid) {
        long next = REVISIONS.getOrDefault(uuid, 0L) + 1L;
        REVISIONS.put(uuid, next);
        return next;
    }


    private static void ensureServerProperty(MinecraftServer server) {
        try {
            Object directory = server.getServerDirectory();
            Path root;
            if (directory instanceof Path path) {
                root = path;
            } else if (directory instanceof File file) {
                root = file.toPath();
            } else {
                throw new IOException("Unsupported server-directory type: "
                        + (directory == null ? "null" : directory.getClass().getName()));
            }

            Path properties = root.resolve("server.properties");
            List<String> lines = Files.exists(properties)
                    ? new ArrayList<>(Files.readAllLines(properties, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean found = false;
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (!trimmed.startsWith("#") && trimmed.matches("pvp\\s*=.*")) {
                    found = true;
                    if (!"pvp=true".equals(trimmed)) {
                        lines.set(i, "pvp=true");
                        changed = true;
                    }
                }
            }
            if (!found) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isEmpty()) lines.add("");
                lines.add("pvp=true");
                changed = true;
            }
            if (!changed) return;

            Files.createDirectories(root);
            String output = String.join(System.lineSeparator(), lines) + System.lineSeparator();
            Path temporary = properties.resolveSibling("server.properties.vsu.tmp");
            Files.writeString(temporary, output, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temporary, properties, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, properties, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            VonixServerUtilities.LOGGER.warn(
                    "[VSU] Could not persist pvp=true to server.properties", exception);
        }
    }
}
