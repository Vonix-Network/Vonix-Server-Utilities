package network.vonix.serverutilities;

import network.vonix.serverutilities.api.HomeSnapshot;
import network.vonix.serverutilities.api.InventorySlotSnapshot;
import network.vonix.serverutilities.api.InventorySnapshot;
import network.vonix.serverutilities.api.LastDeathSnapshot;
import network.vonix.serverutilities.api.PanelCapabilities;
import network.vonix.serverutilities.api.PanelDelivery;
import network.vonix.serverutilities.api.PanelEvent;
import network.vonix.serverutilities.api.PanelFeatureKeys;
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
import network.vonix.serverutilities.features.panel.PanelCollaborators;
import network.vonix.serverutilities.features.panel.PanelEventBus;
import network.vonix.serverutilities.features.panel.PanelService;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Outside-mod SPI projections, capabilities, teleport rules, and event bounds. */
public final class CompanionSpiContractTest {
    private CompanionSpiContractTest() {}

    private static final UUID PLAYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    public static void main(String[] args) throws Exception {
        projections();
        teleportRules();
        serviceContract();
        System.out.println("CompanionSpiContractTest: PASS");
    }

    private static void projections() {
        requireThrows(() -> new HomeSnapshot(" ", "minecraft:overworld", 0, 0, 0, 0, 0));
        HomeSnapshot home = new HomeSnapshot("Base", "minecraft:overworld", 1.5, 64, -2, 90f, 0f);
        require(home.name().equals("base"), "home name normalized");
        LastDeathSnapshot death = LastDeathSnapshot.locationOnly("minecraft:overworld", 8, 64, 8, 0, 0, 123L);
        require(death.cause().isEmpty() && death.killer().isEmpty(), "last death is not history");
        PlayerStateSnapshot snap = PlayerStateSnapshot.unknown(PLAYER);
        require(!snap.online() && snap.health().isEmpty(), "unknown state does not zero-fill");
        List<InventorySlotSnapshot> mutable = new ArrayList<>();
        mutable.add(new InventorySlotSnapshot(0, "minecraft:stone", 3));
        InventorySnapshot inv = new InventorySnapshot(PLAYER, "minecraft:player", "inv", mutable);
        mutable.clear();
        require(inv.slots().size() == 1, "inventory snapshot copies");
        requireThrows(() -> inv.slots().add(new InventorySlotSnapshot(1, "minecraft:dirt", 1)));
        for (Method method : InventorySnapshot.class.getMethods()) {
            String name = method.getName().toLowerCase();
            require(!(name.contains("set") || name.contains("write") || name.contains("persist")),
                    "snapshot write API: " + method.getName());
        }
        Class<?>[] permitted = PanelTeleportTarget.class.getPermittedSubclasses();
        require(permitted.length == 2, "only spawn and home");
        PanelCapabilities caps = new PanelCapabilities(
                false, false, true, false, false, true, true, true, "2.0.2", "test");
        require(!caps.lastDeathIsHistory(), "lastDeathIsHistory forced false");
        require(!caps.featureEnabled(PanelFeatureKeys.TELEPORT_ACTIONS), "unknown/disabled false");
        ServerSnapshot server = new ServerSnapshot("2.0.2", "test", 0, 20);
        require(server.onlinePlayers() == 0, "server snapshot");
        require(VonixPanels.current().isEmpty(), "unbound SPI is empty");
        for (Class<?> type : new Class<?>[] {
                VonixPanel.class, HomeSnapshot.class, ServerSnapshot.class, PanelTeleportRequest.class}) {
            String src = String.valueOf(type);
            require(!src.contains("java.sql"), "no sql in type name");
        }
    }

    private static void teleportRules() {
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.spawn(PLAYER), true, true, true, true, false).accepted());
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.home(PLAYER, "base"), true, true, true, true, true).accepted());
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.spawn(PLAYER), false, true, true, true, false).status()
                == PanelTeleportStatus.DISABLED);
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.spawn(PLAYER), true, false, true, true, false).status()
                == PanelTeleportStatus.PLAYER_OFFLINE);
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.home(PLAYER, "nowhere"), true, true, true, true, false).status()
                == PanelTeleportStatus.HOME_MISSING);
        require(PanelTeleportRules.evaluate(
                new PanelTeleportRequest(PLAYER, OTHER, new PanelTeleportTarget.Spawn()),
                true, true, true, true, false).status() == PanelTeleportStatus.WRONG_IDENTITY);
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.spawn(PLAYER), true, true, true, true, false, false, false).status()
                == PanelTeleportStatus.PERMISSION_DENIED);
        require(PanelTeleportRules.evaluate(
                PanelTeleportRequest.spawn(PLAYER), true, true, true, true, false, true, true).status()
                == PanelTeleportStatus.COOLDOWN);
        require(PanelTeleportRules.rejectArbitraryCoordinates().status() == PanelTeleportStatus.REJECTED);
        require(PanelTeleportRules.rejectArbitraryCommand().status() == PanelTeleportStatus.REJECTED);
    }

    private static void serviceContract() {
        network.vonix.serverutilities.config.ModConfig.INSTANCE.resetForTesting();
        FeatureRegistry.resetForTesting();
        FeatureRegistry registry = FeatureRegistry.getInstance();
        List<HomeSnapshot> homes = new ArrayList<>();
        homes.add(new HomeSnapshot("base", "minecraft:overworld", 10, 64, 10, 0, 0));
        AtomicBoolean online = new AtomicBoolean(true);
        AtomicBoolean permitted = new AtomicBoolean(true);
        AtomicBoolean serverThread = new AtomicBoolean(true);
        AtomicBoolean canSchedule = new AtomicBoolean(false);
        AtomicInteger handed = new AtomicInteger();
        List<PanelTeleportTarget> executed = new ArrayList<>();
        PanelEventBus bus = new PanelEventBus(() -> registry.isEnabled(PanelFeatureKeys.EVENTS));
        PanelService service = new PanelService(
                registry, bus,
                id -> List.copyOf(homes),
                id -> Optional.of(LastDeathSnapshot.locationOnly("minecraft:overworld", 1, 64, 1, 0, 0, 1L)),
                new PanelCollaborators.OnlinePlayers() {
                    @Override public boolean isOnline(UUID playerId) { return online.get() && PLAYER.equals(playerId); }
                    @Override public Optional<String> username(UUID playerId) {
                        return isOnline(playerId) ? Optional.of("Steve") : Optional.empty();
                    }
                    @Override public Optional<PlayerStateSnapshot> state(UUID playerId) {
                        if (!isOnline(playerId)) return Optional.empty();
                        return Optional.of(new PlayerStateSnapshot(
                                playerId, "Steve", true, Optional.of("minecraft:overworld"),
                                OptionalDouble.empty(), OptionalDouble.empty(), OptionalInt.empty(),
                                OptionalInt.empty(), OptionalInt.empty(), OptionalLong.empty(), OptionalLong.empty()));
                    }
                    @Override public List<InventorySnapshot> inventory(UUID playerId) {
                        if (!isOnline(playerId)) return List.of();
                        return List.of(new InventorySnapshot(playerId, "minecraft:player", "inv",
                                List.of(new InventorySlotSnapshot(0, "minecraft:stone", 1))));
                    }
                },
                (id, target, home) -> { executed.add(target); return true; },
                (id, node) -> permitted.get(),
                new PanelCollaborators.ServerThreads() {
                    @Override public boolean isServerThread() { return serverThread.get(); }
                    @Override public boolean canSchedule() { return canSchedule.get(); }
                    @Override public void execute(Runnable task) {
                        handed.incrementAndGet();
                        boolean previous = serverThread.get();
                        serverThread.set(true);
                        try { task.run(); }
                        finally { serverThread.set(previous); }
                    }
                });
        invokeLifecycle(service, "bind");
        require(service.listHomes(PLAYER).size() == 1, "homes readable");
        require(service.lastDeath(PLAYER).isPresent(), "last death readable");
        registry.setEnabledLocal(PanelFeatureKeys.DEATH_HISTORY, false);
        require(service.lastDeath(PLAYER).isEmpty(), "last death gated");
        require(service.playerState(PLAYER).isEmpty(), "stats fail-closed");
        require(service.inventory(PLAYER).isEmpty(), "inventory fail-closed");
        require(service.requestTeleport(PanelTeleportRequest.spawn(PLAYER)).status()
                == PanelTeleportStatus.DISABLED, "teleport gated");
        registry.setEnabledLocal(PanelFeatureKeys.TELEPORT_ACTIONS, true);
        require(service.requestTeleport(PanelTeleportRequest.spawn(PLAYER)).status()
                == PanelTeleportStatus.ACCEPTED, "spawn accepted");
        permitted.set(false);
        require(service.requestTeleport(PanelTeleportRequest.home(PLAYER, "base")).status()
                == PanelTeleportStatus.PERMISSION_DENIED, "permission checked");
        permitted.set(true);
        require(service.requestTeleport(new PanelTeleportRequest(PLAYER, OTHER, new PanelTeleportTarget.Spawn())).status()
                == PanelTeleportStatus.WRONG_IDENTITY, "foreign player rejected");
        serverThread.set(false);
        canSchedule.set(false);
        require(service.requestTeleport(PanelTeleportRequest.spawn(PLAYER)).status()
                == PanelTeleportStatus.NOT_ON_SERVER_THREAD, "off-thread rejected when unbound");
        canSchedule.set(true);
        require(service.requestTeleport(PanelTeleportRequest.home(PLAYER, "base")).status()
                == PanelTeleportStatus.QUEUED, "off-thread handoff");
        require(handed.get() >= 1, "handoff executed");
        serverThread.set(true);

        List<PanelEvent> received = new ArrayList<>();
        Optional<PanelSubscription> sub = service.registerListener(received::add);
        require(sub.isPresent(), "listener registered while events disabled");
        require(service.publishJoin(PLAYER, "Steve") == PanelDelivery.DISABLED, "events silent until enabled");
        require(received.isEmpty(), "no events while disabled");
        registry.setEnabledLocal(PanelFeatureKeys.EVENTS, true);
        require(service.publishJoin(PLAYER, "Steve") == PanelDelivery.ACCEPTED, "event accepted");
        require(received.size() == 1, "listener received join");
        sub.get().close();
        require(!sub.get().isActive(), "unsubscribe");
        for (int i = 0; i < PanelEventBus.MAX_LISTENERS; i++) service.registerListener(event -> {});
        require(service.registerListener(event -> {}).isEmpty(), "listener cap");
        service.eventBus().reset();
        registry.setEnabledLocal(PanelFeatureKeys.EVENTS, true);
        service.registerListener(event -> {});
        PanelDelivery last = PanelDelivery.ACCEPTED;
        for (int i = 0; i < PanelEventBus.MAX_BUFFER + 5; i++) last = service.publishQuit(PLAYER, "Steve");
        require(last == PanelDelivery.DROPPED_OLDEST, "backpressure drops oldest");
        require(service.eventBus().snapshot().size() == PanelEventBus.MAX_BUFFER, "buffer bounded");
        invokeLifecycle(service, "unbind");
        require(VonixPanels.current().isEmpty(), "unbind clears SPI");
        FeatureRegistry.resetForTesting();
    }

    private static void invokeLifecycle(PanelService service, String methodName) {
        try {
            Method method = PanelService.class.getDeclaredMethod(methodName);
            method.setAccessible(true);
            method.invoke(service);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("internal lifecycle invocation failed: " + methodName, e);
        }
    }

    private static void require(boolean ok) {
        require(ok, "failed");
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void requireThrows(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected throw");
        } catch (RuntimeException expected) {
            // ok
        }
    }
}
