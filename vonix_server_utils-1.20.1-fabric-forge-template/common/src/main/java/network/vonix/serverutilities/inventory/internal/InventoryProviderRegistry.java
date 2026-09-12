package network.vonix.serverutilities.inventory.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Internal registry for VSU's built-in {@link InventoryProvider} implementations used by
 * {@code /backsee}.
 *
 * <p>Providers are registered only by VSU at mod init. This is not a public API, not a
 * ServiceLoader SPI, and not a third-party extension contract.
 *
 * <p>De-duplication: {@link #register} is last-write-wins by {@link InventoryProvider#id()}.
 * Order: providers are sorted ascending by {@link InventoryProvider#priority()}, so lower
 * runs first.
 */
public final class InventoryProviderRegistry {

    private static final List<InventoryProvider> PROVIDERS = new ArrayList<>();

    private InventoryProviderRegistry() {}

    /** Register a built-in provider. Last-write-wins by {@link InventoryProvider#id()}. */
    public static synchronized void register(InventoryProvider p) {
        if (p == null) return;
        PROVIDERS.removeIf(existing -> existing.id().equals(p.id()));
        PROVIDERS.add(p);
        PROVIDERS.sort(Comparator.comparingInt(InventoryProvider::priority));
    }

    /** Unmodifiable, priority-sorted view of registered built-in providers. */
    public static synchronized List<InventoryProvider> providers() {
        return Collections.unmodifiableList(new ArrayList<>(PROVIDERS));
    }
}
