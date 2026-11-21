package network.vonix.serverutilities.pvp;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRules;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.config.ModConfig;

import java.util.HashMap;
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

    /** Enable vanilla PvP through the 26.1.2 gamerule (the old server.properties key was removed). */
    public static void onServerStarting(MinecraftServer server) {
        server.getGameRules().set(GameRules.PVP, true, server);
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

}
