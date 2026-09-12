package network.vonix.serverutilities.listener;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.admin.AdminManager;
import network.vonix.serverutilities.command.CrateCommands;
import network.vonix.serverutilities.command.ModCommands;
import network.vonix.serverutilities.command.UtilityCommands;
import network.vonix.serverutilities.command.WorldCommands;
import network.vonix.serverutilities.config.ModConfig;
import network.vonix.serverutilities.crates.CratePlaytimeTask;
import network.vonix.serverutilities.moderation.ModerationBootstrap;
import network.vonix.serverutilities.teleport.TeleportManager;

/** Native NeoForge adapter for the 1.21.1 EventHandler lifecycle sequence. */
public final class EventHandler {
    private EventHandler() {}

    public static void init() {
        ModerationBootstrap.init();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.register(EventHandler.class);
    }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    @SubscribeEvent
    public static void starting(ServerStartingEvent event) {
        serverStarting(event.getServer());
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        serverStarted(event.getServer());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        serverStopping(event.getServer());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        serverStopped(event.getServer());
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        serverTick(event.getServer());
    }

    @SubscribeEvent
    public static void join(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) playerJoin(player);
    }

    @SubscribeEvent
    public static void quit(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) playerQuit(player);
    }

    @SubscribeEvent
    public static void death(LivingDeathEvent event) {
        livingDeath(event.getEntity(), event.getSource());
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        ModCommands.register(dispatcher); CrateCommands.register(dispatcher);
        UtilityCommands.register(dispatcher); WorldCommands.register(dispatcher);
        ModerationBootstrap.registerCommands(dispatcher);
        VonixServerUtilities.LOGGER.info("[VSU] All commands registered.");
    }

    private static void serverStarting(MinecraftServer server) {
        ModConfig.INSTANCE.load(server.getServerDirectory().resolve("config"));
        VonixServerUtilities.getInstance().getDatabase().init(server);
        VonixServerUtilities.dbAsync(() -> {
            try {
                TeleportManager.getInstance().hydrateFromDb(); UtilityCommands.hydrateFromDb();
                var manager = network.vonix.serverutilities.crates.CrateRepository.getInstance();
                manager.ensureSchema(VonixServerUtilities.getInstance().getDatabase().getConnection());
                manager.createCrate("playtime", "playtime"); manager.createCrate("event", "event");
                int recovered = manager.recoverPendingClaims();
                if (recovered > 0) VonixServerUtilities.LOGGER.warn("[VSU] Refunded {} pending crate claims after startup.", recovered);
            } catch (Exception exception) { VonixServerUtilities.LOGGER.error("[VSU] Crate/key startup initialisation failed", exception); }
        });
        VonixServerUtilities.dbAsync(() -> network.vonix.serverutilities.kits.KitManager.getInstance().loadFromJson(server));
        CratePlaytimeTask.register();
    }

    private static void serverStarted(MinecraftServer server) {
        ModerationBootstrap.serverStarted(server);
    }
    private static void serverStopping(MinecraftServer server) { ModerationBootstrap.serverStopping(server); }
    private static void serverStopped(MinecraftServer server) {
        TeleportManager.getInstance().clear(); AdminManager.getInstance().clear(); CratePlaytimeTask.clear();
        VonixServerUtilities.getInstance().shutdown(); VonixServerUtilities.getInstance().getDatabase().close();
    }
    private static void serverTick(MinecraftServer server) { CratePlaytimeTask.onServerTick(server); }
    private static void playerJoin(ServerPlayer player) { UtilityCommands.onPlayerJoin(player); }
    private static void playerQuit(ServerPlayer player) { UtilityCommands.onPlayerLeave(player.getUUID()); TeleportManager.getInstance().clearPlayer(player.getUUID()); }
    private static void livingDeath(LivingEntity entity, DamageSource source) { if (entity instanceof ServerPlayer player) TeleportManager.getInstance().saveDeathLocation(player); }
}
