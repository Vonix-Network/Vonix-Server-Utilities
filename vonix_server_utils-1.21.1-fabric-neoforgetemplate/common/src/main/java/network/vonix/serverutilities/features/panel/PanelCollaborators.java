package network.vonix.serverutilities.features.panel;

import network.vonix.serverutilities.api.HomeSnapshot;
import network.vonix.serverutilities.api.InventorySnapshot;
import network.vonix.serverutilities.api.LastDeathSnapshot;
import network.vonix.serverutilities.api.PanelTeleportTarget;
import network.vonix.serverutilities.api.PlayerStateSnapshot;
import network.vonix.serverutilities.api.ServerSnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Test seams for {@link PanelService}. Production adapters talk to existing
 * VSU managers; unit tests bind in-memory fakes so Minecraft is not required.
 */
public final class PanelCollaborators {

    @FunctionalInterface
    public interface HomeDirectory {
        List<HomeSnapshot> list(UUID playerId);

        default Optional<HomeSnapshot> find(UUID playerId, String name) {
            if (name == null || name.isBlank()) return Optional.empty();
            String key = name.trim().toLowerCase(java.util.Locale.ROOT);
            for (HomeSnapshot home : list(playerId)) {
                if (home.name().equals(key)) return Optional.of(home);
            }
            return Optional.empty();
        }
    }

    @FunctionalInterface
    public interface DeathDirectory {
        Optional<LastDeathSnapshot> lastDeath(UUID playerId);
    }

    public interface OnlinePlayers {
        boolean isOnline(UUID playerId);

        Optional<String> username(UUID playerId);

        Optional<PlayerStateSnapshot> state(UUID playerId);

        List<InventorySnapshot> inventory(UUID playerId);

        default ServerSnapshot server(String vsuVersion, String minecraftVersion) {
            return new ServerSnapshot(vsuVersion, minecraftVersion, 0, 0);
        }
    }

    @FunctionalInterface
    public interface TeleportExecutor {
        boolean teleport(UUID playerId, PanelTeleportTarget target, Optional<HomeSnapshot> home);
    }

    @FunctionalInterface
    public interface Permissions {
        boolean has(UUID playerId, String node);
    }

    public interface ServerThreads {
        boolean isServerThread();

        boolean canSchedule();

        void execute(Runnable task);
    }

    private PanelCollaborators() {}
}
