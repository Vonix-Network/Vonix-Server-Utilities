package network.vonix.serverutilities.inventory.internal;

import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;

/**
 * Internal resolver for an openable inventory attached to an item on a target player.
 *
 * <p>Used only by VSU's {@code /backsee} command through a priority-ordered chain of
 * built-in providers registered at mod init. This is not a public API or ServiceLoader SPI.
 */
public interface InventoryProvider {

    /** Stable identifier — e.g. {@code "vonix:capability"}, {@code "vonix:curios"}. */
    String id();

    /**
     * Lower runs first. Built-in providers use:
     * <ul>
     *   <li>CURIOS = 100</li>
     *   <li>DATA_COMPONENTS = 150 (1.21+)</li>
     *   <li>CAPABILITY = 200</li>
     *   <li>LEGACY_NBT = 300</li>
     * </ul>
     */
    int priority();

    /**
     * Attempt to resolve an inventory view on {@code target}.
     *
     * @param target    the player whose items are being inspected.
     * @param slotHint  {@code -1} if the user passed no slot; {@code >= 0} if {@code /backsee <p> <slot>}.
     *                  Providers should respect the hint — when {@code >= 0}, scan only that main-inventory slot.
     * @return Empty if this provider can't handle anything on the target; present = caller opens it.
     */
    Optional<InventoryView> resolve(ServerPlayer target, int slotHint);
}
