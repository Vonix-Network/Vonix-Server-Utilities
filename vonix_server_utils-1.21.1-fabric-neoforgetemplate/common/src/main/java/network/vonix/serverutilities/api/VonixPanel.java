package network.vonix.serverutilities.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Narrow public outside-mod SPI. VSU remains usable with no companion bound.
 *
 * <p>Writes, raw JDBC, arbitrary commands, free coordinates, credentials,
 * HTTP, and website transport are intentionally absent.
 *
 * @since 2.0.2
 */
public interface VonixPanel {

    /** Fail-closed capability snapshot. Safe on any thread. */
    PanelCapabilities capabilities();

    /** Immutable server identity/counts. Safe on any thread after bind. */
    ServerSnapshot server();

    /**
     * All homes for {@code playerId}, sorted by name.
     * Live storage must be read from the VSU database executor.
     */
    List<HomeSnapshot> listHomes(UUID playerId);

    /** One named home, or empty when missing/blank. */
    Optional<HomeSnapshot> getHome(UUID playerId, String name);

    /**
     * Latest death location only. Empty when none is stored or
     * {@link PanelFeatureKeys#DEATH_HISTORY} is disabled.
     */
    Optional<LastDeathSnapshot> lastDeath(UUID playerId);

    /**
     * Live player vitals. Empty when offline or stats are disabled.
     * Missing numeric fields stay empty. Server thread for live players.
     */
    Optional<PlayerStateSnapshot> playerState(UUID playerId);

    /**
     * Read-only inventory copies. Empty when disabled or offline.
     * Never writes. Server thread for live players.
     */
    List<InventorySnapshot> inventory(UUID playerId);

    /**
     * Restricted teleport: spawn or a named existing home.
     * Actor must be the target player. Permission-checked.
     * Off-thread calls are handed to the server thread when a server is bound.
     */
    PanelTeleportResult requestTeleport(PanelTeleportRequest request);

    /**
     * Bounded listener registration. Empty when the listener cap is reached.
     * Disabled event production still allows registration.
     */
    Optional<PanelSubscription> registerListener(PanelListener listener);
}
