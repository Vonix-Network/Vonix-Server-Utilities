package network.vonix.serverutilities.features.panel;

import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.api.PanelDelivery;
import network.vonix.serverutilities.api.PanelEvent;
import network.vonix.serverutilities.api.PanelListener;
import network.vonix.serverutilities.api.PanelSubscription;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Bounded in-memory outside-mod event bus. No HTTP or storage work.
 */
public final class PanelEventBus {

    public static final int MAX_LISTENERS = 8;
    public static final int MAX_BUFFER = 64;

    private final BooleanSupplier eventsEnabled;
    private final List<BoundListener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<PanelEvent> buffer = new ArrayDeque<>();
    private int droppedOldest;

    public PanelEventBus(BooleanSupplier eventsEnabled) {
        this.eventsEnabled = Objects.requireNonNull(eventsEnabled, "eventsEnabled");
    }

    public Optional<PanelSubscription> register(PanelListener listener) {
        Objects.requireNonNull(listener, "listener");
        synchronized (this) {
            if (activeListenerCount() >= MAX_LISTENERS) {
                return Optional.empty();
            }
            BoundListener bound = new BoundListener(listener);
            listeners.add(bound);
            return Optional.of(bound);
        }
    }

    public PanelDelivery publish(PanelEvent event) {
        Objects.requireNonNull(event, "event");
        if (!eventsEnabled.getAsBoolean()) {
            return PanelDelivery.DISABLED;
        }
        PanelDelivery delivery;
        List<PanelListener> snapshot;
        synchronized (this) {
            if (buffer.size() >= MAX_BUFFER) {
                buffer.removeFirst();
                droppedOldest++;
                delivery = PanelDelivery.DROPPED_OLDEST;
            } else {
                delivery = PanelDelivery.ACCEPTED;
            }
            buffer.addLast(event);
            snapshot = new ArrayList<>(listeners.size());
            for (BoundListener bound : listeners) {
                if (bound.isActive()) snapshot.add(bound.listener);
            }
        }
        for (PanelListener listener : snapshot) {
            try {
                listener.onPanelEvent(event);
            } catch (RuntimeException ex) {
                VonixServerUtilities.LOGGER.debug(
                        "[VonixSU/SPI] listener threw: {}", ex.getMessage());
            }
        }
        return delivery;
    }

    public synchronized List<PanelEvent> snapshot() {
        return List.copyOf(buffer);
    }

    public synchronized int droppedOldestCount() {
        return droppedOldest;
    }

    public int listenerCount() {
        return activeListenerCount();
    }

    public synchronized void reset() {
        listeners.clear();
        buffer.clear();
        droppedOldest = 0;
    }

    private int activeListenerCount() {
        int n = 0;
        for (BoundListener bound : listeners) {
            if (bound.isActive()) n++;
        }
        return n;
    }

    private final class BoundListener implements PanelSubscription {
        private final PanelListener listener;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private BoundListener(PanelListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) {
                listeners.remove(this);
            }
        }
    }
}
