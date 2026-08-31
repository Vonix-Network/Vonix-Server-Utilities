package network.vonix.serverutilities.listener;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.api.PanelFeatureKeys;
import network.vonix.serverutilities.features.FeatureRegistry;
import network.vonix.serverutilities.listener.PanelMinecraftAdapters;
import network.vonix.serverutilities.teleport.TeleportManager;

import java.util.Optional;

/**
 * Wires the outside-mod SPI to live VSU managers. No network, no companion dependency.
 */
final class PanelBootstrap {

    private static volatile MinecraftServer server;
    private static volatile PanelService service;

    private PanelBootstrap() {}

    static void start(MinecraftServer minecraftServer) {
        server = minecraftServer;
        FeatureRegistry registry = FeatureRegistry.getInstance();
        PanelEventBus bus = new PanelEventBus(() -> registry.isEnabled(PanelFeatureKeys.EVENTS));
        PanelService created = new PanelService(
                registry,
                bus,
                PanelMinecraftAdapters.HOMES,
                PanelMinecraftAdapters.DEATHS,
                PanelMinecraftAdapters.players(() -> server),
                PanelMinecraftAdapters.teleports(() -> server),
                PanelMinecraftAdapters.PERMISSIONS,
                PanelMinecraftAdapters.threads(() -> server));
        created.bind();
        service = created;
        TeleportManager.getInstance().setCompletionListener(PanelBootstrap::onTeleportCompleted);
        VonixServerUtilities.LOGGER.info("[VonixSU] Outside-mod SPI bound (local gates, no remote).");
    }

    static void stop() {
        TeleportManager.getInstance().setCompletionListener(null);
        PanelService current = service;
        if (current != null) current.unbind();
        service = null;
        server = null;
    }

    static Optional<PanelService> service() {
        return Optional.ofNullable(service);
    }

    static void onJoin(ServerPlayer player) {
        PanelService current = service;
        if (current == null) return;
        current.publishJoin(player.getUUID(), player.getName().getString());
    }

    static void onQuit(ServerPlayer player) {
        PanelService current = service;
        if (current == null) return;
        current.publishQuit(player.getUUID(), player.getName().getString());
    }

    static void onDeath(ServerPlayer player) {
        PanelService current = service;
        if (current == null) return;
        current.publishDeath(
                player.getUUID(),
                player.getName().getString(),
                Optional.of(player.level().dimension().identifier().toString()));
    }

    private static void onTeleportCompleted(ServerPlayer player, String detail) {
        PanelService current = service;
        if (current == null) return;
        current.publishTeleportCompleted(player.getUUID(), player.getName().getString(), detail);
    }
}
