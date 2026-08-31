package network.vonix.serverutilities.features.panel;

import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.api.HomeSnapshot;
import network.vonix.serverutilities.api.InventorySnapshot;
import network.vonix.serverutilities.api.LastDeathSnapshot;
import network.vonix.serverutilities.api.PanelCapabilities;
import network.vonix.serverutilities.api.PanelDelivery;
import network.vonix.serverutilities.api.PanelEvent;
import network.vonix.serverutilities.api.PanelEventKind;
import network.vonix.serverutilities.api.PanelFeatureKeys;
import network.vonix.serverutilities.api.PanelListener;
import network.vonix.serverutilities.api.PanelSubscription;
import network.vonix.serverutilities.api.PanelTeleportRequest;
import network.vonix.serverutilities.api.PanelTeleportResult;
import network.vonix.serverutilities.api.PanelTeleportRules;
import network.vonix.serverutilities.api.PanelTeleportStatus;
import network.vonix.serverutilities.api.PanelTeleportTarget;
import network.vonix.serverutilities.api.PlayerStateSnapshot;
import network.vonix.serverutilities.api.ServerSnapshot;
import network.vonix.serverutilities.api.VonixPanel;
import network.vonix.serverutilities.api.VonixPanels;
import network.vonix.serverutilities.features.FeatureRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal outside-mod SPI adapter. Uses existing managers through
 * {@link PanelCollaborators}; never exposes JDBC or command execution.
 */
public final class PanelService implements VonixPanel {

    public static final String MINECRAFT_VERSION = "1.20.1";
    public static final long TELEPORT_COOLDOWN_MS = 3000L;

    private final FeatureRegistry features;
    private final PanelEventBus events;
    private final PanelCollaborators.HomeDirectory homes;
    private final PanelCollaborators.DeathDirectory deaths;
    private final PanelCollaborators.OnlinePlayers players;
    private final PanelCollaborators.TeleportExecutor teleports;
    private final PanelCollaborators.Permissions permissions;
    private final PanelCollaborators.ServerThreads threads;
    private final ConcurrentHashMap<UUID, Long> lastTeleport = new ConcurrentHashMap<>();

    public PanelService(
            FeatureRegistry features,
            PanelEventBus events,
            PanelCollaborators.HomeDirectory homes,
            PanelCollaborators.DeathDirectory deaths,
            PanelCollaborators.OnlinePlayers players,
            PanelCollaborators.TeleportExecutor teleports,
            PanelCollaborators.Permissions permissions,
            PanelCollaborators.ServerThreads threads) {
        this.features = Objects.requireNonNull(features, "features");
        this.events = Objects.requireNonNull(events, "events");
        this.homes = Objects.requireNonNull(homes, "homes");
        this.deaths = Objects.requireNonNull(deaths, "deaths");
        this.players = Objects.requireNonNull(players, "players");
        this.teleports = Objects.requireNonNull(teleports, "teleports");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    void bind() {
        PanelRegistry.bind(this);
    }

    void unbind() {
        if (PanelRegistry.current().orElse(null) == this) {
            PanelRegistry.unbind(this);
        }
        events.reset();
        lastTeleport.clear();
    }

    public PanelEventBus eventBus() {
        return events;
    }

    @Override
    public PanelCapabilities capabilities() {
        return new PanelCapabilities(
                features.isEnabled(PanelFeatureKeys.EVENTS),
                features.isEnabled(PanelFeatureKeys.STATS),
                features.isEnabled(PanelFeatureKeys.DEATH_HISTORY),
                features.isEnabled(PanelFeatureKeys.INVENTORY_READ),
                features.isEnabled(PanelFeatureKeys.TELEPORT_ACTIONS),
                features.isEnabled("homes"),
                features.isEnabled("spawn"),
                false,
                VonixServerUtilities.VERSION,
                MINECRAFT_VERSION);
    }

    @Override
    public ServerSnapshot server() {
        return players.server(VonixServerUtilities.VERSION, MINECRAFT_VERSION);
    }

    @Override
    public List<HomeSnapshot> listHomes(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!features.isEnabled("homes")) return List.of();
        return List.copyOf(homes.list(playerId));
    }

    @Override
    public Optional<HomeSnapshot> getHome(UUID playerId, String name) {
        Objects.requireNonNull(playerId, "playerId");
        if (!features.isEnabled("homes") || name == null || name.isBlank()) return Optional.empty();
        return homes.find(playerId, name);
    }

    @Override
    public Optional<LastDeathSnapshot> lastDeath(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!features.isEnabled(PanelFeatureKeys.DEATH_HISTORY)) return Optional.empty();
        return deaths.lastDeath(playerId);
    }

    @Override
    public Optional<PlayerStateSnapshot> playerState(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!features.isEnabled(PanelFeatureKeys.STATS)) return Optional.empty();
        if (!players.isOnline(playerId)) return Optional.empty();
        return players.state(playerId);
    }

    @Override
    public List<InventorySnapshot> inventory(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        if (!features.isEnabled(PanelFeatureKeys.INVENTORY_READ)) return List.of();
        if (!players.isOnline(playerId)) return List.of();
        return List.copyOf(players.inventory(playerId));
    }

    @Override
    public PanelTeleportResult requestTeleport(PanelTeleportRequest request) {
        Objects.requireNonNull(request, "request");
        if (!threads.isServerThread()) {
            if (!threads.canSchedule()) {
                return PanelTeleportResult.of(
                        PanelTeleportStatus.NOT_ON_SERVER_THREAD,
                        "teleport requests require the Minecraft server thread");
            }
            threads.execute(() -> requestTeleport(request));
            return PanelTeleportResult.of(PanelTeleportStatus.QUEUED, "handed off to the server thread");
        }
        boolean homeExists = false;
        Optional<HomeSnapshot> home = Optional.empty();
        if (request.target() instanceof PanelTeleportTarget.Home named) {
            home = homes.find(request.targetPlayerId(), named.name());
            homeExists = home.isPresent();
        }
        String node = request.target() instanceof PanelTeleportTarget.Spawn
                ? "vsu.command.spawn" : "vsu.command.home";
        boolean permitted = permissions.has(request.actorPlayerId(), node);
        boolean cooling = inCooldown(request.actorPlayerId());
        PanelTeleportResult decision = PanelTeleportRules.evaluate(
                request,
                features.isEnabled(PanelFeatureKeys.TELEPORT_ACTIONS),
                players.isOnline(request.targetPlayerId()),
                features.isEnabled("spawn"),
                features.isEnabled("homes"),
                homeExists,
                permitted,
                cooling);
        if (!decision.accepted()) return decision;
        boolean ok = teleports.teleport(request.targetPlayerId(), request.target(), home);
        if (!ok) {
            return PanelTeleportResult.of(PanelTeleportStatus.WORLD_UNAVAILABLE, "destination world is unavailable");
        }
        markCooldown(request.actorPlayerId());
        return decision;
    }

    @Override
    public Optional<PanelSubscription> registerListener(PanelListener listener) {
        return events.register(listener);
    }

    public PanelDelivery publishJoin(UUID playerId, String username) {
        return publish(PanelEventKind.PLAYER_JOIN, playerId, username, Optional.empty());
    }

    public PanelDelivery publishQuit(UUID playerId, String username) {
        return publish(PanelEventKind.PLAYER_QUIT, playerId, username, Optional.empty());
    }

    public PanelDelivery publishDeath(UUID playerId, String username, Optional<String> world) {
        return publish(PanelEventKind.PLAYER_DEATH, playerId, username, world);
    }

    public PanelDelivery publishTeleportCompleted(UUID playerId, String username, String detail) {
        return publish(PanelEventKind.TELEPORT_COMPLETED, playerId, username, Optional.ofNullable(detail));
    }

    private PanelDelivery publish(PanelEventKind kind, UUID playerId, String username, Optional<String> detail) {
        return events.publish(new PanelEvent(
                kind, playerId, username, System.currentTimeMillis(), detail));
    }

    private boolean inCooldown(UUID playerId) {
        Long last = lastTeleport.get(playerId);
        long window = Math.max(0, network.vonix.serverutilities.config.ModConfig.INSTANCE.getSpiTeleportCooldownSeconds()) * 1000L;
        return last != null && System.currentTimeMillis() - last < window;
    }

    private void markCooldown(UUID playerId) {
        lastTeleport.put(playerId, System.currentTimeMillis());
    }
}
