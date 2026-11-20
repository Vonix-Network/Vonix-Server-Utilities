package network.vonix.serverutilities.moderation;

import net.minecraft.server.MinecraftServer;
import network.vonix.serverutilities.VonixServerUtilities;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs {@link PunishmentService#runExpirySweep} every 60 seconds.
 *
 * Lifecycle:
 *   {@link #start(MinecraftServer)}  on SERVER_STARTED
 *   {@link #stop()}                  on SERVER_STOPPING / SERVER_STOPPED
 *
 * The sweep itself queues onto the DB executor; this scheduler thread is
 * only responsible for the cadence.
 */
public final class ExpirySweeper {

    private static ScheduledExecutorService scheduler;

    private ExpirySweeper() {}

    public static synchronized void start(MinecraftServer server) {
        if (scheduler != null) return;
        try {
            ScheduledExecutorService created = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "VonixSU-ModExpirySweeper");
                t.setDaemon(true);
                return t;
            });
            created.scheduleAtFixedRate(() -> {
                try {
                    VonixServerUtilities.dbAsync(() -> PunishmentService.runExpirySweep(server));
                } catch (Throwable t) {
                    VonixServerUtilities.LOGGER.warn("[VonixSU/mod] ExpirySweeper tick failed: {}", t.getMessage());
                }
            }, 60L, 60L, TimeUnit.SECONDS);
            scheduler = created;
            VonixServerUtilities.LOGGER.info("[VonixSU/mod] ExpirySweeper started (60s cadence)");
        } catch (Throwable t) {
            // SERVER_STARTED runs on the server thread inside NeoForge's EventBus.
            // Letting a Throwable escape here is fatal to the whole server: it surfaces as
            // "Encountered an unexpected exception" and forces a shutdown. A thread-budget
            // failure ("OutOfMemoryError: unable to create native thread") is a resource
            // condition, not a reason to kill an otherwise healthy world. Contain it and
            // leave the sweeper disabled; the next boot retries.
            scheduler = null;
            VonixServerUtilities.LOGGER.error(
                    "[VonixSU/mod] ExpirySweeper could not start ({}: {}). "
                            + "Expiry sweep disabled for this session; punishment expiry will not run until restart.",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    public static synchronized void stop() {
        if (scheduler == null) return;
        scheduler.shutdownNow();
        scheduler = null;
        VonixServerUtilities.LOGGER.info("[VonixSU/mod] ExpirySweeper stopped");
    }
}
