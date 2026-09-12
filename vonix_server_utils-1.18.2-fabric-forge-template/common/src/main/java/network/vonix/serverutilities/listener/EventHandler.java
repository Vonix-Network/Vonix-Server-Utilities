package network.vonix.serverutilities.listener;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.admin.AdminManager;
import network.vonix.serverutilities.command.*;
import network.vonix.serverutilities.config.ModConfig;
import network.vonix.serverutilities.crates.CratePlaytimeTask;
import network.vonix.serverutilities.moderation.ModerationBootstrap;
import network.vonix.serverutilities.platform.PlatformEvents;
import network.vonix.serverutilities.teleport.TeleportManager;

/** Shared behavior; Fabric and Forge modules provide event delivery. */
public final class EventHandler {
    private EventHandler() {}

    public static void init() {
        ModerationBootstrap.init();
        PlatformEvents.Holder.get().register(new PlatformEvents.Callbacks(
                EventHandler::registerCommands, EventHandler::serverStarting, EventHandler::serverStarted,
                EventHandler::serverStopping, EventHandler::serverStopped, EventHandler::serverTick,
                EventHandler::playerJoin, EventHandler::playerQuit, EventHandler::livingDeath));
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> d) {
        ModCommands.register(d); CrateCommands.register(d); UtilityCommands.register(d); WorldCommands.register(d);
        ModerationBootstrap.registerCommands(d);
        VonixServerUtilities.LOGGER.info("[VSU] All commands registered.");
    }

    private static void serverStarting(MinecraftServer s) {
        ModConfig.INSTANCE.load(s.getServerDirectory().toPath().resolve("config"));
        VonixServerUtilities.getInstance().getDatabase().init(s);
        VonixServerUtilities.dbAsync(() -> { try {
            TeleportManager.getInstance().hydrateFromDb(); UtilityCommands.hydrateFromDb();
            var r = network.vonix.serverutilities.crates.CrateRepository.getInstance();
            r.ensureSchema(VonixServerUtilities.getInstance().getDatabase().getConnection());
            r.createCrate("playtime", "playtime"); r.createCrate("event", "event");
            int recovered = r.recoverPendingClaims();
            if (recovered > 0) VonixServerUtilities.LOGGER.warn("[VSU] Refunded {} pending crate claims after startup.", recovered);
        } catch (Exception e) { VonixServerUtilities.LOGGER.error("[VSU] startup initialization failed", e); } });
        VonixServerUtilities.dbAsync(() -> network.vonix.serverutilities.kits.KitManager.getInstance().loadFromJson(s));
        CratePlaytimeTask.register();
    }

    private static void serverStarted(MinecraftServer s) { ModerationBootstrap.serverStarted(s); }
    private static void serverStopping(MinecraftServer s) { ModerationBootstrap.serverStopping(s); }
    private static void serverStopped(MinecraftServer s) {
        TeleportManager.getInstance().clear(); AdminManager.getInstance().clear(); CratePlaytimeTask.clear();
        VonixServerUtilities.getInstance().shutdown(); VonixServerUtilities.getInstance().getDatabase().close();
    }
    private static void serverTick(MinecraftServer s) { CratePlaytimeTask.onServerTick(s); }
    private static void playerJoin(ServerPlayer p) { UtilityCommands.onPlayerJoin(p); }
    private static void playerQuit(ServerPlayer p) {
        UtilityCommands.onPlayerLeave(p.getUUID());
        TeleportManager.getInstance().clearPlayer(p.getUUID());
    }
    private static void livingDeath(LivingEntity e, DamageSource s) {
        if (e instanceof ServerPlayer p) TeleportManager.getInstance().saveDeathLocation(p);
    }
}
