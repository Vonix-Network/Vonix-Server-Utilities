package network.vonix.serverutilities.features.panel;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import network.vonix.serverutilities.api.HomeSnapshot;
import network.vonix.serverutilities.api.InventoryProvider;
import network.vonix.serverutilities.api.InventoryProviderRegistry;
import network.vonix.serverutilities.api.InventorySlotSnapshot;
import network.vonix.serverutilities.api.InventorySnapshot;
import network.vonix.serverutilities.api.InventoryView;
import network.vonix.serverutilities.api.LastDeathSnapshot;
import network.vonix.serverutilities.api.PanelTeleportTarget;
import network.vonix.serverutilities.api.PlayerStateSnapshot;
import network.vonix.serverutilities.api.ServerSnapshot;
import network.vonix.serverutilities.donation_ranks.LuckPermsBridge;
import network.vonix.serverutilities.homes.HomeManager;
import network.vonix.serverutilities.teleport.TeleportManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Minecraft-backed collaborators. Unit tests bind in-memory fakes instead.
 */
public final class PanelMinecraftAdapters {

    private PanelMinecraftAdapters() {}

    public static final PanelCollaborators.HomeDirectory HOMES = playerId -> {
        List<HomeSnapshot> out = new ArrayList<>();
        for (HomeManager.Home home : HomeManager.getInstance().getHomes(playerId)) {
            out.add(new HomeSnapshot(
                    home.name(), home.world(), home.x(), home.y(), home.z(),
                    home.yaw(), home.pitch()));
        }
        return List.copyOf(out);
    };

    public static final PanelCollaborators.DeathDirectory DEATHS = playerId -> {
        TeleportManager.Location loc = TeleportManager.getInstance().getDeathLocation(playerId);
        if (loc == null) return Optional.empty();
        return Optional.of(LastDeathSnapshot.locationOnly(
                loc.world(), loc.x(), loc.y(), loc.z(), loc.yaw(), loc.pitch(), loc.timestamp()));
    };

    public static final PanelCollaborators.Permissions PERMISSIONS = (playerId, node) -> {
        if (!LuckPermsBridge.isPresent()) return true;
        return LuckPermsBridge.hasPermission(playerId, node);
    };

    public static PanelCollaborators.OnlinePlayers players(Supplier<MinecraftServer> server) {
        return new OnlinePlayers(server);
    }

    public static PanelCollaborators.TeleportExecutor teleports(Supplier<MinecraftServer> server) {
        return new Teleports(server);
    }

    public static PanelCollaborators.ServerThreads threads(Supplier<MinecraftServer> server) {
        return new Threads(server);
    }

    static String itemId(ItemStack stack) {
        ResourceLocation id = Registry.ITEM.getKey(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static final class OnlinePlayers implements PanelCollaborators.OnlinePlayers {
        private final Supplier<MinecraftServer> server;

        private OnlinePlayers(Supplier<MinecraftServer> server) {
            this.server = server;
        }

        private ServerPlayer player(UUID playerId) {
            MinecraftServer s = server.get();
            return s == null ? null : s.getPlayerList().getPlayer(playerId);
        }

        @Override
        public boolean isOnline(UUID playerId) {
            return player(playerId) != null;
        }

        @Override
        public Optional<String> username(UUID playerId) {
            ServerPlayer p = player(playerId);
            return p == null ? Optional.empty() : Optional.of(p.getName().getString());
        }

        @Override
        public Optional<PlayerStateSnapshot> state(UUID playerId) {
            ServerPlayer p = player(playerId);
            if (p == null) return Optional.empty();
            long playtime = p.getStats().getValue(Stats.CUSTOM, Stats.PLAY_TIME);
            return Optional.of(new PlayerStateSnapshot(
                    playerId,
                    p.getName().getString(),
                    true,
                    Optional.of(p.getLevel().dimension().location().toString()),
                    OptionalDouble.of(p.getHealth()),
                    OptionalDouble.of(p.getMaxHealth()),
                    OptionalInt.of(p.getFoodData().getFoodLevel()),
                    OptionalInt.of(p.getArmorValue()),
                    OptionalInt.of(p.experienceLevel),
                    OptionalLong.of(playtime),
                    OptionalLong.empty()));
        }

        @Override
        public List<InventorySnapshot> inventory(UUID playerId) {
            ServerPlayer p = player(playerId);
            if (p == null) return List.of();
            List<InventorySnapshot> out = new ArrayList<>();
            out.add(copyVanilla(p));
            for (InventoryProvider provider : InventoryProviderRegistry.providers()) {
                Optional<InventoryView> view = provider.resolve(p, -1);
                if (view.isEmpty()) continue;
                out.add(copyView(p.getUUID(), provider.id(), view.get()));
            }
            return List.copyOf(out);
        }

        @Override
        public ServerSnapshot server(String vsuVersion, String minecraftVersion) {
            MinecraftServer s = this.server.get();
            if (s == null) return new ServerSnapshot(vsuVersion, minecraftVersion, 0, 0);
            return new ServerSnapshot(
                    vsuVersion, minecraftVersion,
                    s.getPlayerList().getPlayerCount(),
                    s.getMaxPlayers());
        }
    }

    static InventorySnapshot copyVanilla(ServerPlayer player) {
        List<InventorySlotSnapshot> slots = new ArrayList<>();
        var inv = player.getInventory();
        int n = inv.getContainerSize();
        for (int i = 0; i < n; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack == null || stack.isEmpty()) continue;
            slots.add(new InventorySlotSnapshot(i, itemId(stack), stack.getCount()));
        }
        return new InventorySnapshot(player.getUUID(), "minecraft:player", "Player Inventory", slots);
    }

    static InventorySnapshot copyView(UUID playerId, String sourceId, InventoryView view) {
        List<InventorySlotSnapshot> slots = new ArrayList<>();
        int n = view.getSlots();
        for (int i = 0; i < n; i++) {
            ItemStack stack = view.getStackInSlot(i);
            if (stack == null || stack.isEmpty()) continue;
            slots.add(new InventorySlotSnapshot(i, itemId(stack), stack.getCount()));
        }
        String title = view.getTitle() == null ? sourceId : view.getTitle();
        return new InventorySnapshot(playerId, sourceId, title, slots);
    }

    private static final class Teleports implements PanelCollaborators.TeleportExecutor {
        private final Supplier<MinecraftServer> server;

        private Teleports(Supplier<MinecraftServer> server) {
            this.server = server;
        }

        @Override
        public boolean teleport(UUID playerId, PanelTeleportTarget target, Optional<HomeSnapshot> home) {
            MinecraftServer s = server.get();
            if (s == null) return false;
            ServerPlayer player = s.getPlayerList().getPlayer(playerId);
            if (player == null) return false;
            if (target instanceof PanelTeleportTarget.Spawn) {
                var spawnPos = s.overworld().getSharedSpawnPos();
                TeleportManager.getInstance().teleportPlayer(
                        player, s.overworld(),
                        spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0f, 0f,
                        "spawn");
                return true;
            }
            if (target instanceof PanelTeleportTarget.Home named) {
                HomeSnapshot dest = home.orElse(null);
                if (dest == null) return false;
                for (ServerLevel level : s.getAllLevels()) {
                    if (worldId(level).equals(dest.world())) {
                        TeleportManager.getInstance().teleportPlayer(
                                player, level, dest.x(), dest.y(), dest.z(), dest.yaw(), dest.pitch(),
                                "home:" + named.name());
                        return true;
                    }
                }
                return false;
            }
            return false;
        }
    }

    private static String worldId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private static final class Threads implements PanelCollaborators.ServerThreads {
        private final Supplier<MinecraftServer> server;

        private Threads(Supplier<MinecraftServer> server) {
            this.server = server;
        }

        @Override
        public boolean isServerThread() {
            MinecraftServer s = server.get();
            return s != null && s.isSameThread();
        }

        @Override
        public boolean canSchedule() {
            return server.get() != null;
        }

        @Override
        public void execute(Runnable task) {
            MinecraftServer s = server.get();
            if (s != null) s.execute(task);
        }
    }
}
