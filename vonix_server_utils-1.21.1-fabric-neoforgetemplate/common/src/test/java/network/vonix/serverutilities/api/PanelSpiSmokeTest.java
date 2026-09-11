package network.vonix.serverutilities.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Compilation and contract smoke checks for the companion panel SPI types.
 */
public final class PanelSpiSmokeTest {
    private PanelSpiSmokeTest() {}

    public static void main(String[] args) {
        UUID player = UUID.fromString("00000000-0000-0000-0000-000000000001");

        HomeSnapshot home = new HomeSnapshot("  Home-A  ", "minecraft:overworld", 1, 2, 3, 90f, 0f);
        require(home.name().equals("home-a"), "home name is trimmed and lower-cased");
        requireThrows(() -> new HomeSnapshot("  ", "minecraft:overworld", 0, 0, 0, 0, 0), "blank home name rejected");

        InventorySlotSnapshot slot = new InventorySlotSnapshot(0, "minecraft:stone", 64);
        InventorySnapshot inventory = new InventorySnapshot(player, "main", "Inventory", List.of(slot));
        require(inventory.slots().size() == 1, "inventory snapshot copies slots");
        requireThrows(() -> new InventorySlotSnapshot(-1, "minecraft:stone", 1), "negative slot index rejected");

        LastDeathSnapshot death = LastDeathSnapshot.locationOnly("minecraft:overworld", 0, 64, 0, 0, 0, 123L);
        require(death.cause().isEmpty() && death.killer().isEmpty(), "locationOnly omits cause/killer");
        require(death.occurredAtEpochMs().orElse(-1L) == 123L, "locationOnly records timestamp");

        PanelCapabilities caps = new PanelCapabilities(
                true, true, true, true, true, true, true, true, "2.1.1", "1.21.1");
        require(!caps.lastDeathIsHistory(), "lastDeathIsHistory is always false");
        require(caps.featureEnabled(PanelFeatureKeys.EVENTS), "events key maps to eventsEnabled");
        require(caps.featureEnabled("homes"), "homes key maps to homesEnabled");
        require(!caps.featureEnabled("unknown"), "unknown feature keys are disabled");

        require(PanelDelivery.values().length == 4, "PanelDelivery has 4 values");
        require(PanelEventKind.values().length == 4, "PanelEventKind has 4 values");
        require(PanelTeleportStatus.values().length == 12, "PanelTeleportStatus has 12 values");

        PanelEvent event = new PanelEvent(PanelEventKind.PLAYER_JOIN, player, null, 1L, null);
        require(event.username().equals(""), "null username becomes empty string");
        require(event.detail().isEmpty(), "null detail becomes empty optional");

        PlayerStateSnapshot unknown = PlayerStateSnapshot.unknown(player);
        require(!unknown.online() && unknown.username().isEmpty(), "unknown player is offline with empty name");
        require(unknown.health().equals(OptionalDouble.empty()), "unknown player has empty vitals");
        require(unknown.hunger().equals(OptionalInt.empty()), "unknown player has empty hunger");
        require(unknown.lastSeenEpochMs().equals(OptionalLong.empty()), "unknown player has empty last-seen");

        ServerSnapshot server = new ServerSnapshot("2.1.1", "1.21.1", 0, 20);
        require(server.maxPlayers() == 20, "server snapshot stores max players");
        requireThrows(() -> new ServerSnapshot("2.1.1", "1.21.1", -1, 20), "negative onlinePlayers rejected");

        PanelTeleportRequest spawn = PanelTeleportRequest.spawn(player);
        require(spawn.target() instanceof PanelTeleportTarget.Spawn, "spawn factory uses Spawn target");
        PanelTeleportRequest homeReq = PanelTeleportRequest.home(player, "Home-A");
        require(((PanelTeleportTarget.Home) homeReq.target()).name().equals("home-a"), "home factory normalizes name");

        PanelTeleportResult accepted = PanelTeleportRules.evaluate(spawn, true, true, true, true, true);
        require(accepted.accepted() && accepted.status() == PanelTeleportStatus.ACCEPTED, "valid spawn is accepted");
        require(accepted.message().equals("spawn"), "accepted spawn message");

        PanelTeleportResult disabled = PanelTeleportRules.evaluate(spawn, false, true, true, true, true);
        require(disabled.status() == PanelTeleportStatus.DISABLED, "disabled teleport actions");

        UUID other = UUID.fromString("00000000-0000-0000-0000-000000000002");
        PanelTeleportRequest cross = new PanelTeleportRequest(player, other, new PanelTeleportTarget.Spawn());
        require(PanelTeleportRules.evaluate(cross, true, true, true, true, true).status()
                == PanelTeleportStatus.WRONG_IDENTITY, "cross-player requests are wrong identity");

        require(PanelTeleportRules.evaluate(spawn, true, false, true, true, true).status()
                == PanelTeleportStatus.PLAYER_OFFLINE, "offline player rejected");
        require(PanelTeleportRules.evaluate(spawn, true, true, true, true, true, false, false).status()
                == PanelTeleportStatus.PERMISSION_DENIED, "missing permission rejected");
        require(PanelTeleportRules.evaluate(spawn, true, true, true, true, true, true, true).status()
                == PanelTeleportStatus.COOLDOWN, "cooldown rejected");
        require(PanelTeleportRules.evaluate(spawn, true, true, false, true, true).status()
                == PanelTeleportStatus.FEATURE_DISABLED, "disabled spawn feature");
        require(PanelTeleportRules.evaluate(homeReq, true, true, true, false, true).status()
                == PanelTeleportStatus.FEATURE_DISABLED, "disabled homes feature");
        require(PanelTeleportRules.evaluate(homeReq, true, true, true, true, false).status()
                == PanelTeleportStatus.HOME_MISSING, "missing home rejected");
        require(PanelTeleportRules.evaluate(homeReq, true, true, true, true, true).message().equals("home"),
                "accepted home message");

        require(PanelTeleportRules.rejectArbitraryCoordinates().status() == PanelTeleportStatus.REJECTED,
                "arbitrary coordinates rejected");
        require(PanelTeleportRules.rejectArbitraryCommand().status() == PanelTeleportStatus.REJECTED,
                "arbitrary commands rejected");
        require(PanelTeleportRules.rejectUnsupportedTarget().status() == PanelTeleportStatus.REJECTED,
                "unsupported target rejected");

        PanelTeleportResult queued = PanelTeleportResult.of(PanelTeleportStatus.QUEUED, "queued");
        require(queued.accepted(), "QUEUED counts as accepted");
        require(!PanelTeleportResult.of(PanelTeleportStatus.REJECTED, null).accepted(), "REJECTED is not accepted");
        require(PanelTeleportResult.of(PanelTeleportStatus.REJECTED, null).message().equals(""),
                "null result message becomes empty");

        require(VonixPanels.current().equals(Optional.empty()), "no panel is bound by default");
        require(PanelFeatureKeys.EVENTS.equals("panel_events_enabled"), "EVENTS key");
        require(PanelFeatureKeys.STATS.equals("panel_stats_enabled"), "STATS key");
        require(PanelFeatureKeys.DEATH_HISTORY.equals("panel_death_history_enabled"), "DEATH_HISTORY key");
        require(PanelFeatureKeys.INVENTORY_READ.equals("panel_inventory_read_enabled"), "INVENTORY_READ key");
        require(PanelFeatureKeys.TELEPORT_ACTIONS.equals("panel_teleport_actions_enabled"), "TELEPORT_ACTIONS key");

        PanelListener listener = e -> {};
        require(listener != null, "PanelListener is a functional interface");

        System.out.println("PanelSpiSmokeTest: PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException | NullPointerException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
