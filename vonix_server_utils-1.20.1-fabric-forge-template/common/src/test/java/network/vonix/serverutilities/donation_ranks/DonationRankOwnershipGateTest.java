package network.vonix.serverutilities.donation_ranks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.data.DataMutateResult;
import net.luckperms.api.node.NodeType;
import network.vonix.serverutilities.features.FeatureRegistry;
import network.vonix.serverutilities.features.ServerConfigClient;
import network.vonix.serverutilities.venary.VenaryClient;
import network.vonix.serverutilities.venary.VenaryConfig;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * Proves a disabled {@code donation_ranks} feature performs no LuckPerms group
 * metadata sync and no player rank sync. The enabled cases are the negative
 * control: they must observe real LuckPerms calls, so a detector that never
 * fires cannot make the disabled assertions pass.
 *
 * <p>Group coverage calls {@link RankGroupSyncer#syncAll()} and
 * {@link ServerConfigClient#onTick} directly. Player coverage calls
 * {@link RankSyncTask#onJoin(UUID, String)} and the public
 * {@link RankSyncTask#onJoin(net.minecraft.server.level.ServerPlayer)} hook.
 * {@code USER_CALLS} counts {@code LuckPerms#getUserManager()}, which
 * {@code setUserGroups} invokes before it mutates a user. An enabled held
 * response must reach that call, and the same response released after an
 * explicit disable must not.
 *
 * <p>Later scenarios hold the real {@code loadUser}, {@code loadGroup},
 * {@code createAndLoadGroup}, and {@code saveUser}/{@code saveGroup} futures.
 * The enabled control completes that future while {@code donation_ranks} is
 * still on and must observe node edits, a save, and the matching audit. The
 * disabled twin flips the flag after the future is held and must observe none
 * of those side effects. A separate registry harness blocks inside feature
 * parsing and requires readers to see the previous explicit flags, not a
 * cleared fail-open map. Another reader samples the one published feature
 * snapshot while {@code update} is at its single write: every sample is the
 * complete previous state or the complete next state, never a mix.
 *
 * <p>The public hook uses a {@code ServerPlayer} subclass whose {@code getUUID}
 * and {@code getName} count calls. The disabled case fails unless the outer
 * gate returns before those reads. A swallowed {@code LinkageError} or
 * {@code RuntimeException} is counted from the onJoin warning and fails the
 * scenario instead of looking like a quiet success.
 */
public final class DonationRankOwnershipGateTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final List<String> FAILURES = new ArrayList<>();
    private static final AtomicInteger GROUP_CALLS = new AtomicInteger();
    private static final AtomicInteger USER_CALLS = new AtomicInteger();
    private static final AtomicInteger RANK_HTTP = new AtomicInteger();
    private static final AtomicInteger LOAD_USER_CALLS = new AtomicInteger();
    private static final AtomicInteger LOAD_GROUP_CALLS = new AtomicInteger();
    private static final AtomicInteger CREATE_GROUP_CALLS = new AtomicInteger();
    private static final AtomicInteger SAVE_USER_CALLS = new AtomicInteger();
    private static final AtomicInteger SAVE_GROUP_CALLS = new AtomicInteger();
    private static final AtomicInteger NODE_MUTATIONS = new AtomicInteger();
    private static final AtomicInteger AUDIT_HTTP = new AtomicInteger();
    private static final ConcurrentLinkedQueue<String> AUDIT_BODIES = new ConcurrentLinkedQueue<>();
    private static final ConcurrentLinkedQueue<String> PATHS = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> CONFIG_BODY = new AtomicReference<>("{}");
    private static volatile AsyncScript async = new AsyncScript();

    private static final Object RANK_GATE = new Object();
    private static final AtomicInteger SWALLOWED_ON_JOIN = new AtomicInteger();
    private static final String PROBE_CLASS = "network.vonix.serverutilities.donation_ranks.probe.DonationRankProbePlayer";
    private static int port;
    private static AtomicBoolean inFlight;
    private static long enabledPlayerNanos = TimeUnit.SECONDS.toNanos(1);
    private static long heldReleaseToUserNanos = TimeUnit.SECONDS.toNanos(1);
    private static boolean holdRanks;
    private static int rankArrivals;
    private static boolean rankHandlerWaiting;
    private static Object probePlayer;

    private DonationRankOwnershipGateTest() {}

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] requestBody;
            try (InputStream in = exchange.getRequestBody()) {
                requestBody = in.readAllBytes();
            }
            String path = exchange.getRequestURI().getPath();
            PATHS.add(path);
            if (path.contains("/audit")) {
                AUDIT_BODIES.add(new String(requestBody, StandardCharsets.UTF_8));
                AUDIT_HTTP.incrementAndGet();
            }
            byte[] body;
            if (path.contains("/ranks")) {
                try {
                    awaitRankGate();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("rank response hold interrupted", interrupted);
                }
                RANK_HTTP.incrementAndGet();
                body = "{\"linked\":true,\"ranks\":[{\"luckperms_group\":\"vip\"}]}".getBytes(StandardCharsets.UTF_8);
            } else if ("/minecraft/server-config".equals(path)) {
                body = CONFIG_BODY.get().getBytes(StandardCharsets.UTF_8);
            } else {
                body = "{}".getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "vsu-donation-rank-gate-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        port = server.getAddress().getPort();
        try {
            installSwallowedOnJoinCounter();
            installLuckPerms();
            check(LuckPermsBridge.isPresent(),
                    "LuckPerms API must be visible so a missing feature gate still calls group sync");
            VenaryClient.init(new VenaryConfig(true, "http://127.0.0.1:" + port, "test-key", false, false, 5, 0));
            inFlight = inFlightFlag();

            run("enabled-syncAll", DonationRankOwnershipGateTest::enabledDirectSyncIsObservable);
            run("disabled-syncAll", DonationRankOwnershipGateTest::disabledDirectSyncDoesNotTouchGroups);
            bootstrapMinecraft();
            run("enabled-hydration", DonationRankOwnershipGateTest::enabledHydrationSyncsGroups);
            run("disabled-hydration", DonationRankOwnershipGateTest::disabledHydrationDoesNotTouchGroups);
            run("enabled-player", DonationRankOwnershipGateTest::enabledPlayerSyncFetchesRanks);
            run("disabled-player", DonationRankOwnershipGateTest::disabledPlayerSyncDoesNotFetchRanks);
            run("enabled-held-player", DonationRankOwnershipGateTest::enabledHeldResponseReachesUserSync);
            run("disabled-held-player", DonationRankOwnershipGateTest::disabledHeldResponseDoesNotMutate);
            run("enabled-public-onJoin", DonationRankOwnershipGateTest::enabledPublicOnJoinReachesUserSync);
            run("disabled-public-onJoin", DonationRankOwnershipGateTest::disabledPublicOnJoinDoesNotSync);
            run("enabled-held-loadUser", () -> heldUserLoad(false));
            run("disabled-held-loadUser", () -> heldUserLoad(true));
            run("enabled-held-loadGroup", () -> heldGroupLoad(false));
            run("disabled-held-loadGroup", () -> heldGroupLoad(true));
            run("enabled-held-createGroup", () -> heldGroupCreate(false));
            run("disabled-held-createGroup", () -> heldGroupCreate(true));
            run("enabled-held-existingGroup", () -> heldExistingGroup(false));
            run("disabled-held-existingGroup", () -> heldExistingGroup(true));
            run("enabled-held-saveUser", () -> heldUserSave(false));
            run("disabled-held-saveUser", () -> heldUserSave(true));
            run("enabled-held-saveGroup", () -> heldGroupSave(false));
            run("disabled-held-saveGroup", () -> heldGroupSave(true));
            run("feature-snapshot-no-fail-open", DonationRankOwnershipGateTest::explicitDisableIsNeverTransientlyMissing);
            run("feature-missing-flag-fail-open", DonationRankOwnershipGateTest::missingFlagStaysFailOpen);
            run("feature-state-one-snapshot", DonationRankOwnershipGateTest::featureStatePublicationIsOneSnapshot);

            if (!FAILURES.isEmpty()) {
                for (String failure : FAILURES) System.err.println("FAIL: " + failure);
                throw new AssertionError(String.join("\n", FAILURES));
            }
            System.out.println("DonationRankOwnershipGateTest: PASS");
        } finally {
            try {
                VenaryClient client = VenaryClient.get();
                if (client != null) client.shutdown();
            } catch (Throwable ignored) {
                // The assertion above is the test result; shutdown is best-effort.
            }
            server.stop(0);
        }
    }

    private static void enabledDirectSyncIsObservable() {
        FeatureRegistry.getInstance().update(config(1, true));
        check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "enabled fixture must report the feature on");
        check(FeatureRegistry.getInstance().getDonationRanks().size() == 1, "enabled fixture must cache a rank");
        int before = GROUP_CALLS.get();
        RankGroupSyncer.syncAll();
        trace("enabled-syncAll", before, USER_CALLS.get(), RANK_HTTP.get());
        check(GROUP_CALLS.get() > before,
                "enabled RankGroupSyncer.syncAll() must call LuckPerms group sync (detector negative control)");
    }

    private static void disabledDirectSyncDoesNotTouchGroups() {
        FeatureRegistry.getInstance().setEnabledLocal("donation_ranks", false);
        check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "local disable must clear the feature gate");
        check(FeatureRegistry.getInstance().getDonationRanks().size() == 1,
                "disabling the feature must leave cached ranks in place");
        int before = GROUP_CALLS.get();
        RankGroupSyncer.syncAll();
        trace("disabled-syncAll", before, USER_CALLS.get(), RANK_HTTP.get());
        check(GROUP_CALLS.get() == before,
                "disabled RankGroupSyncer.syncAll() must not call LuckPerms group sync, calls "
                        + before + " -> " + GROUP_CALLS.get());
    }

    private static void enabledHydrationSyncsGroups() throws Exception {
        CONFIG_BODY.set(config(2, true).toString());
        int before = GROUP_CALLS.get();
        awaitHydration(2);
        trace("enabled-hydration", before, USER_CALLS.get(), RANK_HTTP.get());
        check(FeatureRegistry.getInstance().getConfigVersion() == 2, "enabled hydration must apply config_version 2");
        check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "enabled hydration must leave the feature on");
        check(GROUP_CALLS.get() > before,
                "enabled server-config hydration must call LuckPerms group sync (caller negative control)");
    }

    private static void disabledHydrationDoesNotTouchGroups() throws Exception {
        CONFIG_BODY.set(config(3, false).toString());
        int before = GROUP_CALLS.get();
        awaitHydration(3);
        trace("disabled-hydration", before, USER_CALLS.get(), RANK_HTTP.get());
        check(FeatureRegistry.getInstance().getConfigVersion() == 3, "disabled hydration must still apply the config");
        check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "disabled hydration must turn the feature off");
        check(FeatureRegistry.getInstance().getDonationRanks().size() == 1,
                "disabled hydration must keep the rank catalog");
        check(GROUP_CALLS.get() == before,
                "disabled server-config hydration must not call LuckPerms group sync, calls "
                        + before + " -> " + GROUP_CALLS.get());
    }

    private static void enabledPlayerSyncFetchesRanks() throws Exception {
        FeatureRegistry.getInstance().update(config(4, true));
        check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "player fixture must enable donation_ranks");
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        long started = System.nanoTime();
        RankSyncTask.onJoin(PLAYER, "Steve");
        awaitIncrease(USER_CALLS, users, TimeUnit.SECONDS.toNanos(5),
                "enabled RankSyncTask.onJoin must reach LuckPerms user sync");
        enabledPlayerNanos = Math.max(1, System.nanoTime() - started);
        trace("enabled-player", GROUP_CALLS.get(), users, http);
        check(RANK_HTTP.get() > http, "enabled player sync must GET /ranks");
        check(USER_CALLS.get() > users, "enabled player sync must call LuckPerms user sync");
    }

    private static void disabledPlayerSyncDoesNotFetchRanks() throws Exception {
        FeatureRegistry.getInstance().update(config(5, false));
        check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "player fixture must disable donation_ranks");
        check(FeatureRegistry.getInstance().getDonationRanks().size() == 1, "disabled player fixture must keep ranks");
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        RankSyncTask.onJoin(PLAYER, "Steve");
        long quiet = Math.min(TimeUnit.SECONDS.toNanos(5),
                Math.max(TimeUnit.SECONDS.toNanos(1), enabledPlayerNanos * 3));
        awaitQuiet(users, http, quiet, "disabled RankSyncTask.onJoin");
        trace("disabled-player", GROUP_CALLS.get(), users, http);
    }

    private static void enabledHeldResponseReachesUserSync() throws Exception {
        FeatureRegistry.getInstance().update(config(6, true));
        check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "held enabled fixture must enable donation_ranks");
        check(!FeatureRegistry.getInstance().getDonationRanks().isEmpty(), "held enabled fixture must cache ranks");
        int arrivals = rankArrivals();
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        holdRankResponses();
        try {
            RankSyncTask.onJoin(PLAYER, "Steve");
            if (!awaitRankHeld(arrivals)) return;
            check(USER_CALLS.get() == users,
                    "enabled held response must not reach LuckPerms setUserGroups before release");
            long started = System.nanoTime();
            releaseRankResponses();
            awaitIncrease(USER_CALLS, users, TimeUnit.SECONDS.toNanos(5),
                    "enabled held rank response must reach LuckPerms setUserGroups after release");
            if (USER_CALLS.get() > users) {
                heldReleaseToUserNanos = Math.max(1, System.nanoTime() - started);
            }
            check(RANK_HTTP.get() > http, "enabled held rank response must be delivered");
            check(USER_CALLS.get() > users, "enabled held rank response must call LuckPerms user sync");
        } finally {
            releaseRankResponses();
        }
        trace("enabled-held-player", GROUP_CALLS.get(), users, http);
    }

    private static void disabledHeldResponseDoesNotMutate() throws Exception {
        FeatureRegistry.getInstance().update(config(7, true));
        check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "in-flight fixture must start enabled");
        check(!FeatureRegistry.getInstance().getDonationRanks().isEmpty(), "in-flight fixture must cache ranks");
        int arrivals = rankArrivals();
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        holdRankResponses();
        try {
            RankSyncTask.onJoin(PLAYER, "Steve");
            if (!awaitRankHeld(arrivals)) return;
            check(USER_CALLS.get() == users, "in-flight response must not mutate LuckPerms before release");
            check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(),
                    "feature must still be enabled when the held request is observed");
            FeatureRegistry.getInstance().setEnabledLocal("donation_ranks", false);
            check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(),
                    "in-flight fixture must disable donation_ranks before the response is released");
            check(!FeatureRegistry.getInstance().getDonationRanks().isEmpty(),
                    "disabling donation_ranks must keep the cached rank catalog");
            releaseRankResponses();
            awaitIncrease(RANK_HTTP, http, TimeUnit.SECONDS.toNanos(5),
                    "disabled in-flight rank response must still be delivered");
            long quiet = Math.min(TimeUnit.SECONDS.toNanos(5),
                    Math.max(TimeUnit.MILLISECONDS.toNanos(300), heldReleaseToUserNanos * 3));
            awaitUserQuiet(users, quiet, "donation_ranks disabled before an in-flight rank response");
        } finally {
            releaseRankResponses();
        }
        trace("disabled-held-player", GROUP_CALLS.get(), users, http);
    }

    private static void enabledPublicOnJoinReachesUserSync() throws Exception {
        FeatureRegistry.getInstance().update(config(8, true));
        check(FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "public onJoin control must enable donation_ranks");
        Object player = createProbePlayer();
        Class<?> type = player.getClass();
        Object uuid = type.getMethod("getUUID").invoke(player);
        Object name = type.getMethod("getName").invoke(player);
        String text = name == null ? null : (String) name.getClass().getMethod("getString").invoke(name);
        check(PLAYER.equals(uuid), "probe player getUUID must return the fixture UUID, got " + uuid);
        check("Steve".equals(text), "probe player getName().getString() must return Steve, got " + text);
        if (!PLAYER.equals(uuid) || !"Steve".equals(text)) return;
        probePlayer = player;
        int uuidReads = counter(type, "UUID_READS");
        int nameReads = counter(type, "NAME_READS");
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        int swallowed = SWALLOWED_ON_JOIN.get();
        publicOnJoin().invoke(null, player);
        awaitIncrease(USER_CALLS, users, TimeUnit.SECONDS.toNanos(5),
                "enabled RankSyncTask.onJoin(ServerPlayer) must reach LuckPerms user sync");
        check(counter(type, "UUID_READS") > uuidReads, "enabled public onJoin must read the player UUID");
        check(counter(type, "NAME_READS") > nameReads, "enabled public onJoin must read the player name");
        check(RANK_HTTP.get() > http, "enabled public onJoin must GET /ranks");
        check(USER_CALLS.get() > users, "enabled public onJoin must call LuckPerms user sync");
        check(SWALLOWED_ON_JOIN.get() == swallowed,
                "enabled public onJoin swallowed LinkageError/RuntimeException, count "
                        + swallowed + " -> " + SWALLOWED_ON_JOIN.get());
        trace("enabled-public-onJoin", GROUP_CALLS.get(), users, http);
    }

    private static void disabledPublicOnJoinDoesNotSync() throws Exception {
        if (probePlayer == null) {
            check(false, "disabled public onJoin has no proven ServerPlayer probe; refusing a quiet pass");
            return;
        }
        FeatureRegistry.getInstance().update(config(9, false));
        check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "public onJoin fixture must be disabled");
        Class<?> type = probePlayer.getClass();
        int uuidReads = counter(type, "UUID_READS");
        int nameReads = counter(type, "NAME_READS");
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        int swallowed = SWALLOWED_ON_JOIN.get();
        publicOnJoin().invoke(null, probePlayer);
        long quiet = Math.min(TimeUnit.SECONDS.toNanos(5),
                Math.max(TimeUnit.SECONDS.toNanos(1), enabledPlayerNanos * 3));
        awaitQuiet(users, http, quiet, "disabled RankSyncTask.onJoin(ServerPlayer)");
        check(counter(type, "UUID_READS") == uuidReads,
                "disabled public onJoin must return before ServerPlayer.getUUID (outer gate), reads "
                        + uuidReads + " -> " + counter(type, "UUID_READS"));
        check(counter(type, "NAME_READS") == nameReads,
                "disabled public onJoin must return before ServerPlayer.getName (outer gate), reads "
                        + nameReads + " -> " + counter(type, "NAME_READS"));
        check(SWALLOWED_ON_JOIN.get() == swallowed,
                "disabled public onJoin swallowed LinkageError/RuntimeException instead of proving the outer gate, count "
                        + swallowed + " -> " + SWALLOWED_ON_JOIN.get());
        trace("disabled-public-onJoin", GROUP_CALLS.get(), users, http);
    }

    private static Method publicOnJoin() throws Exception {
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        return RankSyncTask.class.getMethod("onJoin", serverPlayer);
    }

    private static int counter(Class<?> type, String field) throws Exception {
        return ((AtomicInteger) type.getField(field).get(null)).get();
    }

    private static void awaitHydration(int expectedVersion) throws Exception {
        ServerConfigClient.reset();
        ServerConfigClient.requestImmediateFetch();
        int before = FeatureRegistry.getInstance().getConfigVersion();
        ServerConfigClient.onTick(null);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (FeatureRegistry.getInstance().getConfigVersion() == before || inFlight.get()) {
            if (System.nanoTime() > deadline) {
                check(false, "hydration timed out before=" + before
                        + " now=" + FeatureRegistry.getInstance().getConfigVersion()
                        + " inFlight=" + inFlight.get()
                        + " expected=" + expectedVersion
                        + " paths=" + PATHS);
                return;
            }
            Thread.sleep(10);
        }
    }

    private static void awaitIncrease(AtomicInteger counter, int baseline, long timeoutNanos, String label)
            throws Exception {
        long deadline = System.nanoTime() + timeoutNanos;
        while (counter.get() == baseline) {
            if (System.nanoTime() > deadline) {
                check(false, label + " (stayed " + baseline + ", paths=" + PATHS + ")");
                return;
            }
            Thread.sleep(10);
        }
    }

    private static void awaitQuiet(int users, int http, long quietNanos, String label) throws Exception {
        long deadline = System.nanoTime() + quietNanos;
        while (System.nanoTime() < deadline) {
            if (USER_CALLS.get() != users || RANK_HTTP.get() != http) {
                check(false, label + " performed player sync, user " + users + " -> " + USER_CALLS.get()
                        + ", rankHttp " + http + " -> " + RANK_HTTP.get());
                return;
            }
            Thread.sleep(10);
        }
        check(USER_CALLS.get() == users && RANK_HTTP.get() == http, label + " must stay quiet");
    }

    private static void awaitUserQuiet(int users, long quietNanos, String label) throws Exception {
        long deadline = System.nanoTime() + quietNanos;
        while (System.nanoTime() < deadline) {
            if (USER_CALLS.get() != users) {
                check(false, label + " mutated LuckPerms user groups, user "
                        + users + " -> " + USER_CALLS.get());
                return;
            }
            Thread.sleep(10);
        }
        check(USER_CALLS.get() == users, label + " must not call LuckPerms setUserGroups");
    }

    private static int rankArrivals() {
        synchronized (RANK_GATE) {
            return rankArrivals;
        }
    }

    private static void holdRankResponses() {
        synchronized (RANK_GATE) {
            holdRanks = true;
            rankHandlerWaiting = false;
        }
    }

    private static void releaseRankResponses() {
        synchronized (RANK_GATE) {
            holdRanks = false;
            rankHandlerWaiting = false;
            RANK_GATE.notifyAll();
        }
    }

    private static void awaitRankGate() throws InterruptedException {
        synchronized (RANK_GATE) {
            rankArrivals++;
            RANK_GATE.notifyAll();
            while (holdRanks) {
                rankHandlerWaiting = true;
                RANK_GATE.notifyAll();
                RANK_GATE.wait(200);
            }
            rankHandlerWaiting = false;
        }
    }

    private static boolean awaitRankHeld(int baseline) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        synchronized (RANK_GATE) {
            while (rankArrivals <= baseline || !rankHandlerWaiting) {
                long remainingMs = (deadline - System.nanoTime()) / 1_000_000L;
                if (remainingMs <= 0) {
                    check(false, "rank request was not held in flight, arrivals=" + rankArrivals
                            + " baseline=" + baseline + " waiting=" + rankHandlerWaiting
                            + " paths=" + PATHS);
                    return false;
                }
                RANK_GATE.wait(remainingMs);
            }
            return true;
        }
    }

    private static JsonObject config(int version, boolean donationRanks) {
        JsonObject root = new JsonObject();
        root.addProperty("config_version", version);
        JsonObject features = new JsonObject();
        features.addProperty("donation_ranks", donationRanks);
        features.addProperty("homes", true);
        root.add("features", features);
        JsonObject rank = new JsonObject();
        rank.addProperty("slug", "vip");
        rank.addProperty("display_name", "VIP");
        rank.addProperty("luckperms_group", "vip");
        rank.addProperty("weight", 10);
        rank.addProperty("prefix", "[VIP]");
        JsonArray ranks = new JsonArray();
        ranks.add(rank);
        root.add("donation_ranks", ranks);
        return root;
    }

    private static void installLuckPerms() throws Exception {
        Class<?> groupManager = Class.forName("net.luckperms.api.model.group.GroupManager");
        Class<?> userManager = Class.forName("net.luckperms.api.model.user.UserManager");
        Object nodeBuilders = newNodeBuilders();
        Object groups = Proxy.newProxyInstance(groupManager.getClassLoader(), new Class<?>[]{groupManager},
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) return objectCall(proxy, method, args);
                    if ("loadGroup".equals(method.getName())) {
                        LOAD_GROUP_CALLS.incrementAndGet();
                        if (async.groupLoad != null) return async.groupLoad;
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    if ("createAndLoadGroup".equals(method.getName())) {
                        CREATE_GROUP_CALLS.incrementAndGet();
                        if (async.groupCreate != null) return async.groupCreate;
                        if (async.realGroups) {
                            return CompletableFuture.completedFuture(newGroup(String.valueOf(args[0])));
                        }
                        return CompletableFuture.completedFuture(null);
                    }
                    if ("saveGroup".equals(method.getName())) {
                        SAVE_GROUP_CALLS.incrementAndGet();
                        if (async.groupSave != null) return async.groupSave;
                        return CompletableFuture.completedFuture(null);
                    }
                    return zero(method);
                });
        Object users = Proxy.newProxyInstance(userManager.getClassLoader(), new Class<?>[]{userManager},
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) return objectCall(proxy, method, args);
                    if ("loadUser".equals(method.getName())) {
                        LOAD_USER_CALLS.incrementAndGet();
                        if (async.userLoad != null) return async.userLoad;
                        if (async.realUserOnLoad) return CompletableFuture.completedFuture(newUser());
                        return CompletableFuture.completedFuture(null);
                    }
                    if ("saveUser".equals(method.getName())) {
                        SAVE_USER_CALLS.incrementAndGet();
                        if (async.userSave != null) return async.userSave;
                        return CompletableFuture.completedFuture(null);
                    }
                    return zero(method);
                });
        LuckPerms api = (LuckPerms) Proxy.newProxyInstance(LuckPerms.class.getClassLoader(),
                new Class<?>[]{LuckPerms.class}, (proxy, method, args) -> {
                    if (isObjectMethod(method)) return objectCall(proxy, method, args);
                    if ("getGroupManager".equals(method.getName())) {
                        GROUP_CALLS.incrementAndGet();
                        return groups;
                    }
                    if ("getUserManager".equals(method.getName())) {
                        USER_CALLS.incrementAndGet();
                        return users;
                    }
                    if ("getNodeBuilderRegistry".equals(method.getName())) return nodeBuilders;
                    return zero(method);
                });
        Method register = LuckPermsProvider.class.getDeclaredMethod("register", LuckPerms.class);
        register.setAccessible(true);
        register.invoke(null, api);
    }

    private static boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    private static Object objectCall(Object proxy, Method method, Object[] args) {
        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
        if ("equals".equals(method.getName())) return proxy == args[0];
        if ("toString".equals(method.getName())) return "DonationRankOwnershipGateStub";
        return null;
    }

    private static Object zero(Method method) {
        Class<?> type = method.getReturnType();
        if (type == void.class || type == Void.class) return null;
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == Optional.class) return Optional.empty();
        if (CompletableFuture.class.isAssignableFrom(type)) return CompletableFuture.completedFuture(null);
        if (type == String.class) return "";
        if (Set.class.isAssignableFrom(type)) return Set.of();
        if (List.class.isAssignableFrom(type)) return List.of();
        return null;
    }

    private static AtomicBoolean inFlightFlag() throws Exception {
        java.lang.reflect.Field field = ServerConfigClient.class.getDeclaredField("inFlight");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(null);
    }

    private static void bootstrapMinecraft() {
        try {
            Class<?> shared = Class.forName("net.minecraft.SharedConstants");
            for (Method method : shared.getMethods()) {
                if ("tryDetectVersion".equals(method.getName()) && method.getParameterCount() == 0) {
                    method.invoke(null);
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("SharedConstants.tryDetectVersion skipped: " + t);
        }
        try {
            Class<?> bootstrap = Class.forName("net.minecraft.server.Bootstrap");
            Method chosen = null;
            for (Method method : bootstrap.getMethods()) {
                if (method.getParameterCount() == 0
                        && ("bootStrap".equals(method.getName()) || "bootstrap".equals(method.getName()))) {
                    chosen = method;
                    break;
                }
            }
            if (chosen == null) {
                System.out.println("Minecraft bootstrap method not found");
                return;
            }
            chosen.invoke(null);
            System.out.println("Minecraft bootstrap: " + chosen.getName());
        } catch (Throwable t) {
            System.out.println("Minecraft bootstrap failed: " + describe(t));
        }
    }

    private static void run(String name, Step step) {
        try {
            step.run();
        } catch (Throwable t) {
            FAILURES.add(name + " crashed: " + describe(t));
            t.printStackTrace(System.err);
        }
    }

    private static void trace(String scenario, int groupBefore, int userBefore, int httpBefore) {
        System.out.println(scenario
                + " groupDelta=" + (GROUP_CALLS.get() - groupBefore)
                + " userDelta=" + (USER_CALLS.get() - userBefore)
                + " rankHttpDelta=" + (RANK_HTTP.get() - httpBefore)
                + " feature=" + FeatureRegistry.getInstance().isDonationRankSyncEnabled()
                + " ranks=" + FeatureRegistry.getInstance().getDonationRanks().size()
                + " version=" + FeatureRegistry.getInstance().getConfigVersion());
    }

    private static void check(boolean condition, String message) {
        if (!condition) FAILURES.add(message);
    }

    private static String describe(Throwable t) {
        Throwable cause = t;
        if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) cause = t.getCause();
        return cause.getClass().getName() + ": " + cause.getMessage();
    }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private static void installSwallowedOnJoinCounter() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        java.lang.reflect.Field loggerField = Class.forName("network.vonix.serverutilities.VonixServerUtilities")
                .getDeclaredField("LOGGER");
        Object original = loggerField.get(null);
        Class<?> loggerType = loggerField.getType();
        Object wrapped = Proxy.newProxyInstance(loggerType.getClassLoader(), new Class<?>[]{loggerType},
                (proxy, method, args) -> {
                    if (args != null && args.length > 0
                            && ("warn".equals(method.getName()) || "error".equals(method.getName()))
                            && String.valueOf(args[0]).contains("onJoin disabled")) {
                        SWALLOWED_ON_JOIN.incrementAndGet();
                    }
                    if (isObjectMethod(method)) return objectCall(proxy, method, args);
                    try {
                        return method.invoke(original, args);
                    } catch (java.lang.reflect.InvocationTargetException ex) {
                        Throwable cause = ex.getCause();
                        if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                        if (cause instanceof Error) throw (Error) cause;
                        throw ex;
                    }
                });
        Method staticBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field.class);
        Method staticOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field.class);
        Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
        putObject.invoke(unsafe, staticBase.invoke(unsafe, loggerField), staticOffset.invoke(unsafe, loggerField), wrapped);
        if (loggerField.get(null) != wrapped) {
            throw new IllegalStateException("could not observe swallowed RankSyncTask.onJoin warnings");
        }
    }

    private static Object createProbePlayer() throws Exception {
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        byte[] bytes = probeClassBytes(serverPlayer);
        Class<?> type = new ProbeLoader(serverPlayer.getClassLoader()).define(bytes);
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Object player = unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        type.getField("uuidValue").set(null, PLAYER);
        type.getField("nameValue").set(null, playerNameComponent());
        if (!serverPlayer.isInstance(player)) {
            throw new IllegalStateException("probe player is not a ServerPlayer");
        }
        return player;
    }

    private static Object playerNameComponent() throws Exception {
        Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        try {
            Object literal = component.getMethod("literal", String.class).invoke(null, "Steve");
            if (literal != null && "Steve".equals(literal.getClass().getMethod("getString").invoke(literal))) {
                return literal;
            }
        } catch (Throwable ignored) {
            // Component.literal is preferred, but a getString proxy still exercises the hook.
        }
        Object proxy = Proxy.newProxyInstance(component.getClassLoader(), new Class<?>[]{component},
                (proxyObject, method, args) -> {
                    if (isObjectMethod(method)) return objectCall(proxyObject, method, args);
                    if ("getString".equals(method.getName()) && (args == null || args.length == 0)) return "Steve";
                    return zero(method);
                });
        Object text = proxy.getClass().getMethod("getString").invoke(proxy);
        if (!"Steve".equals(text)) {
            throw new IllegalStateException("probe Component getString returned " + text);
        }
        return proxy;
    }

    private static void heldUserLoad(boolean disableBeforeRelease) throws Exception {
        resetAsyncScript();
        if (!prepareEnabledRanks(50)) return;
        CompletableFuture<Object> gate = new CompletableFuture<>();
        async.userLoad = gate;
        int loads = LOAD_USER_CALLS.get();
        int nodes = NODE_MUTATIONS.get();
        int saves = SAVE_USER_CALLS.get();
        int audits = AUDIT_HTTP.get();
        int http = RANK_HTTP.get();
        try {
            RankSyncTask.onJoin(PLAYER, "Steve");
            awaitIncrease(LOAD_USER_CALLS, loads, TimeUnit.SECONDS.toNanos(5),
                    "held loadUser must reach LuckPerms user load before the flag changes");
            awaitIncrease(RANK_HTTP, http, TimeUnit.SECONDS.toNanos(5),
                    "held loadUser must deliver the rank response before the flag changes");
            check(NODE_MUTATIONS.get() == nodes, "loadUser future must still be held: nodes already changed");
            check(SAVE_USER_CALLS.get() == saves, "loadUser future must still be held: saveUser already called");
            check(AUDIT_HTTP.get() == audits, "loadUser future must still be held: audit already posted");
            if (LOAD_USER_CALLS.get() == loads) return;
            if (disableBeforeRelease) disableDonationRanks();
            gate.complete(newUser());
            assertUserContinuation(disableBeforeRelease, nodes, saves, audits, "loadUser");
        } finally {
            release(gate);
            resetAsyncScript();
        }
    }

    private static void heldGroupLoad(boolean disableBeforeRelease) throws Exception {
        resetAsyncScript();
        if (!prepareEnabledRanks(51)) return;
        CompletableFuture<Object> gate = new CompletableFuture<>();
        async.groupLoad = gate;
        async.realGroups = true;
        int loads = LOAD_GROUP_CALLS.get();
        int creates = CREATE_GROUP_CALLS.get();
        int nodes = NODE_MUTATIONS.get();
        int saves = SAVE_GROUP_CALLS.get();
        int audits = AUDIT_HTTP.get();
        try {
            RankGroupSyncer.syncAll();
            check(LOAD_GROUP_CALLS.get() > loads, "held loadGroup must reach LuckPerms group load");
            check(CREATE_GROUP_CALLS.get() == creates, "loadGroup future must still be held: group already created");
            check(NODE_MUTATIONS.get() == nodes, "loadGroup future must still be held: nodes already changed");
            check(SAVE_GROUP_CALLS.get() == saves, "loadGroup future must still be held: saveGroup already called");
            if (LOAD_GROUP_CALLS.get() == loads) return;
            if (disableBeforeRelease) disableDonationRanks();
            gate.complete(Optional.empty());
            assertGroupContinuation(disableBeforeRelease, creates, nodes, saves, audits,
                    "loadGroup", "lp_group_create", true);
        } finally {
            release(gate);
            resetAsyncScript();
        }
    }

    private static void heldGroupCreate(boolean disableBeforeRelease) throws Exception {
        resetAsyncScript();
        if (!prepareEnabledRanks(52)) return;
        CompletableFuture<Object> gate = new CompletableFuture<>();
        async.groupCreate = gate;
        int creates = CREATE_GROUP_CALLS.get();
        int nodes = NODE_MUTATIONS.get();
        int saves = SAVE_GROUP_CALLS.get();
        int audits = AUDIT_HTTP.get();
        try {
            RankGroupSyncer.syncAll();
            check(CREATE_GROUP_CALLS.get() > creates, "held createAndLoadGroup must be scheduled while enabled");
            check(NODE_MUTATIONS.get() == nodes, "create future must still be held: attributes already written");
            check(SAVE_GROUP_CALLS.get() == saves, "create future must still be held: saveGroup already called");
            if (CREATE_GROUP_CALLS.get() == creates) return;
            if (disableBeforeRelease) disableDonationRanks();
            gate.complete(newGroup("vip"));
            assertGroupContinuation(disableBeforeRelease, creates, nodes, saves, audits,
                    "createGroup", "lp_group_create", false);
        } finally {
            release(gate);
            resetAsyncScript();
        }
    }

    private static void heldExistingGroup(boolean disableBeforeRelease) throws Exception {
        resetAsyncScript();
        if (!prepareEnabledRanks(53)) return;
        CompletableFuture<Object> gate = new CompletableFuture<>();
        async.groupLoad = gate;
        int loads = LOAD_GROUP_CALLS.get();
        int creates = CREATE_GROUP_CALLS.get();
        int nodes = NODE_MUTATIONS.get();
        int saves = SAVE_GROUP_CALLS.get();
        int audits = AUDIT_HTTP.get();
        try {
            RankGroupSyncer.syncAll();
            check(LOAD_GROUP_CALLS.get() > loads, "existing-group fixture must reach loadGroup");
            if (LOAD_GROUP_CALLS.get() == loads) return;
            check(NODE_MUTATIONS.get() == nodes, "existing group future must still be held: attributes already written");
            check(SAVE_GROUP_CALLS.get() == saves, "existing group future must still be held: saveGroup already called");
            if (disableBeforeRelease) disableDonationRanks();
            gate.complete(Optional.of(newGroup("vip")));
            check(CREATE_GROUP_CALLS.get() == creates,
                    "existing group continuation must not create a group, creates "
                            + creates + " -> " + CREATE_GROUP_CALLS.get());
            assertGroupContinuation(disableBeforeRelease, creates, nodes, saves, audits,
                    "existingGroup", "lp_group_update", false);
        } finally {
            release(gate);
            resetAsyncScript();
        }
    }

    private static void heldUserSave(boolean disableBeforeRelease) throws Exception {
        resetAsyncScript();
        if (!prepareEnabledRanks(54)) return;
        CompletableFuture<Object> gate = new CompletableFuture<>();
        async.realUserOnLoad = true;
        async.userSave = gate;
        int saves = SAVE_USER_CALLS.get();
        int nodes = NODE_MUTATIONS.get();
        int audits = AUDIT_HTTP.get();
        try {
            RankSyncTask.onJoin(PLAYER, "Steve");
            awaitIncrease(SAVE_USER_CALLS, saves, TimeUnit.SECONDS.toNanos(5),
                    "held saveUser must be reached while donation_ranks is still enabled");
            check(NODE_MUTATIONS.get() > nodes,
                    "enabled path must mutate nodes before saveUser returns its future");
            if (SAVE_USER_CALLS.get() == saves) return;
            if (disableBeforeRelease) disableDonationRanks();
            gate.complete(null);
            if (disableBeforeRelease) {
                awaitAuditQuiet(audits, "disabled saveUser completion");
                check(!auditsAfter(audits).contains("rank_apply") && !auditsAfter(audits).contains("rank_remove"),
                        "disabled saveUser completion posted a player audit: " + auditsAfter(audits));
            } else {
                awaitAudit(audits, "rank_apply", "enabled saveUser completion");
            }
        } finally {
            release(gate);
            resetAsyncScript();
        }
    }

    private static void heldGroupSave(boolean disableBeforeRelease) throws Exception {
        resetAsyncScript();
        if (!prepareEnabledRanks(55)) return;
        CompletableFuture<Object> gate = new CompletableFuture<>();
        async.realGroups = true;
        async.groupSave = gate;
        int saves = SAVE_GROUP_CALLS.get();
        int nodes = NODE_MUTATIONS.get();
        int audits = AUDIT_HTTP.get();
        try {
            RankGroupSyncer.syncAll();
            check(SAVE_GROUP_CALLS.get() > saves, "held saveGroup must be reached while donation_ranks is still enabled");
            check(NODE_MUTATIONS.get() > nodes, "enabled path must mutate group nodes before saveGroup returns its future");
            if (SAVE_GROUP_CALLS.get() == saves) return;
            if (disableBeforeRelease) disableDonationRanks();
            gate.complete(null);
            if (disableBeforeRelease) {
                awaitAuditQuiet(audits, "disabled saveGroup completion");
                check(!auditsAfter(audits).contains("lp_group_create") && !auditsAfter(audits).contains("lp_group_update"),
                        "disabled saveGroup completion posted a group audit: " + auditsAfter(audits));
            } else {
                awaitAudit(audits, "lp_group_create", "enabled saveGroup completion");
            }
        } finally {
            release(gate);
            resetAsyncScript();
        }
    }

    private static void explicitDisableIsNeverTransientlyMissing() throws Exception {
        FeatureRegistry fresh = newFeatureRegistry();
        JsonObject seed = new JsonObject();
        seed.addProperty("config_version", 40);
        JsonObject seedFeatures = new JsonObject();
        seedFeatures.addProperty("donation_ranks", false);
        seedFeatures.addProperty("homes", false);
        seed.add("features", seedFeatures);
        JsonObject settings = new JsonObject();
        JsonObject homes = new JsonObject();
        homes.addProperty("max_homes", 5);
        settings.add("homes", homes);
        seed.add("feature_settings", settings);
        seed.add("donation_ranks", rankCatalog());
        fresh.update(seed);
        check(!fresh.isDonationRankSyncEnabled(), "snapshot seed must store explicit donation_ranks=false");
        check(!fresh.isEnabled("homes"), "snapshot seed must store explicit homes=false");

        BlockingBoolean ranks = new BlockingBoolean(false);
        JsonObject next = new JsonObject();
        next.addProperty("config_version", 41);
        JsonObject nextFeatures = new JsonObject();
        nextFeatures.add("donation_ranks", ranks);
        nextFeatures.addProperty("homes", true);
        next.add("features", nextFeatures);
        Thread updater = new Thread(() -> fresh.update(next), "donation-rank-feature-snapshot");
        updater.setDaemon(true);
        try {
            updater.start();
            check(ranks.entered.await(5, TimeUnit.SECONDS),
                    "feature replacement must block inside feature-value parsing");
            check(updater.isAlive(), "feature replacement must still be running when flags are sampled");
            boolean sawRanks = fresh.isDonationRankSyncEnabled();
            boolean sawHomes = fresh.isEnabled("homes");
            int versionDuring = fresh.getConfigVersion();
            int ranksDuring = fresh.getDonationRanks().size();
            int homesSetting = fresh.getSetting("homes", "max_homes", -1);
            check(!sawRanks,
                    "explicit donation_ranks=false was visible as enabled during replacement (fail-open window)");
            check(!sawHomes,
                    "explicit homes=false was visible as enabled during replacement (cleared feature snapshot)");
            check(versionDuring == 40, "config version changed during feature parsing, saw " + versionDuring);
            check(ranksDuring == 1, "donation rank list changed during feature parsing, size " + ranksDuring);
            check(homesSetting == 5, "feature settings changed during feature parsing, saw " + homesSetting);
        } finally {
            ranks.release.countDown();
            updater.join(5000);
        }
        check(!updater.isAlive(), "feature replacement did not finish after the boolean was released");
        check(!fresh.isDonationRankSyncEnabled(), "published snapshot must keep donation_ranks disabled");
        check(fresh.isEnabled("homes"), "published snapshot must contain homes=true");
        check(fresh.getConfigVersion() == 41, "published snapshot must apply config_version 41");
        check(fresh.isHydratedFromBackend(), "published snapshot must stay hydrated");
        check(fresh.getDonationRanks().size() == 1, "omitted donation rank list must stay cached");
        check(fresh.getSetting("homes", "max_homes", -1) == 5, "omitted feature settings must stay cached");
    }

    private static void missingFlagStaysFailOpen() throws Exception {
        FeatureRegistry fresh = newFeatureRegistry();
        check(fresh.isDonationRankSyncEnabled(), "unhydrated donation_ranks flag must fail open");
        JsonObject omitted = new JsonObject();
        omitted.addProperty("config_version", 1);
        JsonObject features = new JsonObject();
        features.addProperty("homes", false);
        omitted.add("features", features);
        fresh.update(omitted);
        check(fresh.isDonationRankSyncEnabled(), "a config that omits donation_ranks must still fail open");
        check(!fresh.isEnabled("homes"), "an explicit homes=false in that config must fail closed");
        check(fresh.getConfigVersion() == 1, "omitted-flag config must still apply config_version");
        check(fresh.isHydratedFromBackend(), "omitted-flag config must still hydrate");
        check(fresh.getDonationRanks().isEmpty(), "omitted-flag config must not invent donation ranks");
    }

    /**
     * Holds {@link FeatureRegistry#update} immediately before its one state
     * write. A second thread may observe only the complete previous snapshot
     * during that hold, and only that snapshot or the complete next snapshot
     * afterward.
     */
    private static void featureStatePublicationIsOneSnapshot() throws Exception {
        assertSingleFeatureStateField();
        FeatureRegistry fresh = newFeatureRegistry();
        FeatureRegistry.FeatureState initial = fresh.currentState();
        if (!fresh.isDonationRankSyncEnabled()
                || initial.hydratedFromBackend()
                || initial.configVersion() != -1
                || !initial.features().isEmpty()
                || !initial.donationRanks().isEmpty()
                || fresh.getDonationRanks() != initial.donationRanks()) {
            throw new IllegalStateException(
                    "unhydrated donation_ranks must fail open from the initial snapshot: " + describeState(initial));
        }

        fresh.update(generationConfig(40, false));
        FeatureRegistry.FeatureState oldSnapshot = fresh.currentState();
        if (!matchesGeneration(oldSnapshot, 40, false)) {
            throw new IllegalStateException("seed snapshot was not complete: " + describeState(oldSnapshot));
        }

        CountDownLatch atPublish = new CountDownLatch(1);
        CountDownLatch releasePublish = new CountDownLatch(1);
        AtomicInteger publishCalls = new AtomicInteger();
        AtomicInteger oldSeen = new AtomicInteger();
        AtomicInteger newSeen = new AtomicInteger();
        AtomicReference<String> probeError = new AtomicReference<>();
        AtomicReference<String> mixed = new AtomicReference<>();
        AtomicReference<Throwable> readerError = new AtomicReference<>();
        AtomicReference<FeatureRegistry.FeatureState> published = new AtomicReference<>();
        Set<FeatureRegistry.FeatureState> seen = ConcurrentHashMap.newKeySet();

        setPublicationProbe(() -> {
            publishCalls.incrementAndGet();
            try {
                FeatureRegistry.FeatureState visible = fresh.currentState();
                FeatureRegistry.FeatureState next = pendingPublication();
                if (visible != oldSnapshot || !matchesGeneration(visible, 40, false)) {
                    probeError.compareAndSet(null,
                            "state visible at publication was not the complete old snapshot: " + describeState(visible));
                }
                if (!matchesGeneration(next, 41, true)) {
                    probeError.compareAndSet(null,
                            "snapshot about to be published is mixed or incomplete: " + describeState(next));
                }
                if (next == visible) {
                    probeError.compareAndSet(null, "publication reused the visible snapshot object");
                }
                published.set(next);
                atPublish.countDown();
                if (!releasePublish.await(5, TimeUnit.SECONDS)) {
                    probeError.compareAndSet(null, "publication hold was not released");
                }
            } catch (Throwable thrown) {
                probeError.compareAndSet(null, describe(thrown));
                atPublish.countDown();
            }
        });

        Thread reader = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    FeatureRegistry.FeatureState sample = fresh.currentState();
                    FeatureRegistry.FeatureState next = published.get();
                    seen.add(sample);
                    if (sample == oldSnapshot) {
                        oldSeen.incrementAndGet();
                        if (!matchesGeneration(sample, 40, false)) {
                            mixed.compareAndSet(null, "old snapshot changed: " + describeState(sample));
                            return;
                        }
                    } else if (next != null && sample == next) {
                        newSeen.incrementAndGet();
                        if (!matchesGeneration(sample, 41, true)) {
                            mixed.compareAndSet(null, "published snapshot is mixed: " + describeState(sample));
                            return;
                        }
                    } else {
                        mixed.compareAndSet(null, "unexpected snapshot: " + describeState(sample));
                        return;
                    }
                }
            } catch (Throwable thrown) {
                readerError.set(thrown);
            }
        }, "feature-state-reader");
        reader.setDaemon(true);

        Thread updater = new Thread(() -> fresh.update(generationConfig(41, true)), "feature-state-publish");
        updater.setDaemon(true);
        try {
            reader.start();
            awaitCondition(() -> oldSeen.get() > 0,
                    TimeUnit.SECONDS.toNanos(5),
                    "reader never observed the seeded snapshot");
            updater.start();
            if (!atPublish.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "update did not reach the single publication point: " + probeError.get());
            }
            if (probeError.get() != null) {
                throw new IllegalStateException(probeError.get());
            }
            if (publishCalls.get() != 1 || oldSeen.get() < 1 || newSeen.get() != 0 || seen.size() != 1) {
                throw new IllegalStateException(
                        "publication window was not the complete old snapshot: publishes="
                                + publishCalls.get() + " old=" + oldSeen.get()
                                + " new=" + newSeen.get() + " identities=" + seen.size()
                                + " mixed=" + mixed.get());
            }
            assertAccessorsMatch(fresh, oldSnapshot, "pre-publication");
            releasePublish.countDown();
            updater.join(5000);
            if (updater.isAlive()) {
                throw new IllegalStateException("feature publication did not finish after release");
            }
            if (probeError.get() != null) {
                throw new IllegalStateException(probeError.get());
            }
            awaitCondition(() -> newSeen.get() > 0 && seen.contains(published.get()),
                    TimeUnit.SECONDS.toNanos(5),
                    "reader never observed the published snapshot; old=" + oldSeen.get()
                            + " new=" + newSeen.get() + " mixed=" + mixed.get());
        } finally {
            releasePublish.countDown();
            setPublicationProbe(null);
            updater.join(5000);
            reader.interrupt();
            reader.join(5000);
        }
        if (readerError.get() != null) {
            Throwable thrown = readerError.get();
            if (thrown instanceof Error) throw (Error) thrown;
            if (thrown instanceof Exception) throw (Exception) thrown;
            throw new IllegalStateException(thrown);
        }
        if (mixed.get() != null) {
            throw new IllegalStateException(mixed.get());
        }
        if (publishCalls.get() != 1 || oldSeen.get() < 1 || newSeen.get() < 1 || seen.size() != 2
                || fresh.currentState() != published.get()) {
            throw new IllegalStateException(
                    "reader did not see exactly the old and new snapshots: publishes="
                            + publishCalls.get() + " old=" + oldSeen.get()
                            + " new=" + newSeen.get() + " identities=" + seen.size());
        }
        if (!matchesGeneration(published.get(), 41, true)) {
            throw new IllegalStateException("published snapshot is not the enabled generation: "
                    + describeState(published.get()));
        }
        assertAccessorsMatch(fresh, published.get(), "post-publication");
        if (!fresh.isDonationRankSyncEnabled() || fresh.getDonationRanks().size() != 2) {
            throw new IllegalStateException(
                    "enabled donation-rank behavior must remain reachable after publication");
        }

        AtomicInteger localPublishes = new AtomicInteger();
        AtomicReference<FeatureRegistry.FeatureState> localNext = new AtomicReference<>();
        AtomicReference<String> localError = new AtomicReference<>();
        FeatureRegistry.FeatureState enabledSnapshot = published.get();
        setPublicationProbe(() -> {
            localPublishes.incrementAndGet();
            try {
                FeatureRegistry.FeatureState next = pendingPublication();
                localNext.set(next);
                if (fresh.currentState() != enabledSnapshot) {
                    localError.compareAndSet(null,
                            "local toggle became visible before its snapshot was published");
                }
                if (!matchesLocalDisable(next)) {
                    localError.compareAndSet(null,
                            "local toggle snapshot is mixed: " + describeState(next));
                }
            } catch (Throwable thrown) {
                localError.compareAndSet(null, describe(thrown));
            }
        });
        boolean changed;
        try {
            changed = fresh.setEnabledLocal("donation_ranks", false);
        } finally {
            setPublicationProbe(null);
        }
        if (!changed || localPublishes.get() != 1 || localError.get() != null
                || fresh.currentState() != localNext.get()) {
            throw new IllegalStateException(
                    "local disable must publish one coherent snapshot: changed=" + changed
                            + " publishes=" + localPublishes.get()
                            + " error=" + localError.get());
        }
        assertAccessorsMatch(fresh, localNext.get(), "local disable");
        if (fresh.setEnabledLocal("donation_ranks", false)) {
            throw new IllegalStateException("repeat local disable must report no change");
        }
        FeatureRegistry.FeatureState afterRepeat = fresh.currentState();
        if (!matchesLocalDisable(afterRepeat)) {
            throw new IllegalStateException(
                    "repeat local disable disturbed the snapshot: " + describeState(afterRepeat));
        }
        System.out.println("feature-state-one-snapshot oldReads=" + oldSeen.get()
                + " newReads=" + newSeen.get()
                + " publishes=" + publishCalls.get()
                + " localPublishes=" + localPublishes.get());
    }

    private static boolean prepareEnabledRanks(int version) {
        FeatureRegistry.getInstance().update(config(version, true));
        boolean enabled = FeatureRegistry.getInstance().isDonationRankSyncEnabled();
        boolean ranked = FeatureRegistry.getInstance().getDonationRanks().size() == 1;
        check(enabled, "continuation fixture must enable donation_ranks");
        check(ranked, "continuation fixture must cache the vip rank");
        return enabled && ranked;
    }

    private static void disableDonationRanks() {
        FeatureRegistry.getInstance().setEnabledLocal("donation_ranks", false);
        check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(),
                "donation_ranks must be explicitly disabled before the held LuckPerms future is released");
        check(!FeatureRegistry.getInstance().getDonationRanks().isEmpty(),
                "disabling donation_ranks must keep the cached rank catalog");
    }

    private static void assertUserContinuation(boolean disabled, int nodes, int saves, int audits, String label)
            throws Exception {
        if (disabled) {
            check(NODE_MUTATIONS.get() == nodes,
                    "disabled " + label + " continuation mutated nodes " + nodes + " -> " + NODE_MUTATIONS.get());
            check(SAVE_USER_CALLS.get() == saves,
                    "disabled " + label + " continuation called saveUser " + saves + " -> " + SAVE_USER_CALLS.get());
            awaitAuditQuiet(audits, "disabled " + label + " continuation");
            check(!auditsAfter(audits).contains("rank_apply") && !auditsAfter(audits).contains("rank_remove"),
                    "disabled " + label + " continuation posted a player audit: " + auditsAfter(audits));
        } else {
            check(NODE_MUTATIONS.get() > nodes,
                    "enabled " + label + " continuation must mutate inheritance nodes");
            check(SAVE_USER_CALLS.get() > saves,
                    "enabled " + label + " continuation must save the user");
            awaitAudit(audits, "rank_apply", "enabled " + label + " continuation");
        }
        System.out.println(label + (disabled ? " disabled" : " enabled")
                + " nodes " + nodes + "->" + NODE_MUTATIONS.get()
                + " userSaves " + saves + "->" + SAVE_USER_CALLS.get()
                + " audits " + audits + "->" + AUDIT_HTTP.get());
    }

    private static void assertGroupContinuation(boolean disabled, int creates, int nodes, int saves, int audits,
                                                 String label, String event, boolean expectCreate) throws Exception {
        if (disabled) {
            if (expectCreate) {
                check(CREATE_GROUP_CALLS.get() == creates,
                        "disabled " + label + " continuation created a group "
                                + creates + " -> " + CREATE_GROUP_CALLS.get());
            }
            check(NODE_MUTATIONS.get() == nodes,
                    "disabled " + label + " continuation mutated nodes " + nodes + " -> " + NODE_MUTATIONS.get());
            check(SAVE_GROUP_CALLS.get() == saves,
                    "disabled " + label + " continuation called saveGroup " + saves + " -> " + SAVE_GROUP_CALLS.get());
            awaitAuditQuiet(audits, "disabled " + label + " continuation");
            check(!auditsAfter(audits).contains("lp_group_"),
                    "disabled " + label + " continuation posted a group audit: " + auditsAfter(audits));
        } else {
            if (expectCreate) {
                check(CREATE_GROUP_CALLS.get() > creates,
                        "enabled " + label + " continuation must create the missing group");
            }
            check(NODE_MUTATIONS.get() > nodes,
                    "enabled " + label + " continuation must mutate group nodes");
            check(SAVE_GROUP_CALLS.get() > saves,
                    "enabled " + label + " continuation must save the group");
            awaitAudit(audits, event, "enabled " + label + " continuation");
        }
        System.out.println(label + (disabled ? " disabled" : " enabled")
                + " creates " + creates + "->" + CREATE_GROUP_CALLS.get()
                + " nodes " + nodes + "->" + NODE_MUTATIONS.get()
                + " groupSaves " + saves + "->" + SAVE_GROUP_CALLS.get()
                + " audits " + audits + "->" + AUDIT_HTTP.get());
    }

    private static void awaitAudit(int baseline, String event, String label) throws Exception {
        awaitIncrease(AUDIT_HTTP, baseline, TimeUnit.SECONDS.toNanos(5), label + " must post an audit");
        String body = auditsAfter(baseline);
        check(body.contains("\"event_type\":\"" + event + "\""),
                label + " must audit " + event + ", saw " + body);
    }

    private static void awaitAuditQuiet(int baseline, String label) throws Exception {
        long quietNanos = TimeUnit.MILLISECONDS.toNanos(400);
        long deadline = System.nanoTime() + quietNanos;
        while (System.nanoTime() < deadline) {
            if (AUDIT_HTTP.get() != baseline) {
                check(false, label + " posted an audit: " + auditsAfter(baseline));
                return;
            }
            Thread.sleep(10);
        }
        check(AUDIT_HTTP.get() == baseline, label + " must not post an audit");
    }

    private static String auditsAfter(int baseline) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (String body : AUDIT_BODIES) {
            if (index++ >= baseline) out.append(body).append('\n');
        }
        return out.toString();
    }

    private static void release(CompletableFuture<Object> gate) {
        if (gate != null && !gate.isDone()) {
            gate.completeExceptionally(new IllegalStateException("held LuckPerms future was not released by the test"));
        }
    }

    private static void resetAsyncScript() {
        async = new AsyncScript();
    }

    private static JsonArray rankCatalog() {
        JsonObject rank = new JsonObject();
        rank.addProperty("slug", "vip");
        rank.addProperty("display_name", "VIP");
        rank.addProperty("luckperms_group", "vip");
        rank.addProperty("weight", 10);
        rank.addProperty("prefix", "[VIP]");
        JsonArray ranks = new JsonArray();
        ranks.add(rank);
        return ranks;
    }

    private static void assertSingleFeatureStateField() throws Exception {
        List<String> instance = new ArrayList<>();
        Field state = null;
        for (Field field : FeatureRegistry.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            instance.add(field.getName() + ":" + field.getType().getSimpleName()
                    + (Modifier.isVolatile(field.getModifiers()) ? ":volatile" : ""));
            state = field;
        }
        if (instance.size() != 1 || state == null
                || !Modifier.isVolatile(state.getModifiers())
                || !"state".equals(state.getName())
                || !"FeatureState".equals(state.getType().getSimpleName())) {
            throw new IllegalStateException(
                    "runtime feature state must be one volatile FeatureState reference, found " + instance);
        }
    }

    private static void setPublicationProbe(Runnable probe) throws Exception {
        Field field = FeatureRegistry.class.getDeclaredField("publicationProbe");
        field.setAccessible(true);
        field.set(null, probe);
    }

    private static FeatureRegistry.FeatureState pendingPublication() throws Exception {
        Field field = FeatureRegistry.class.getDeclaredField("pendingPublication");
        field.setAccessible(true);
        return (FeatureRegistry.FeatureState) field.get(null);
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition, long timeoutNanos, String failure) {
        long deadline = System.nanoTime() + timeoutNanos;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(failure);
            }
            Thread.onSpinWait();
        }
    }

    private static void assertAccessorsMatch(FeatureRegistry registry, FeatureRegistry.FeatureState expected, String label) {
        if (registry.currentState() != expected) {
            throw new IllegalStateException(label + " currentState drifted from the sampled snapshot");
        }
        if (registry.isDonationRankSyncEnabled() != Boolean.TRUE.equals(expected.features().get("donation_ranks"))) {
            throw new IllegalStateException(label + " donation_ranks accessor drifted from the snapshot");
        }
        if (registry.isEnabled("homes") != Boolean.TRUE.equals(expected.features().get("homes"))) {
            throw new IllegalStateException(label + " homes accessor drifted from the snapshot");
        }
        if (registry.isEnabled("warps") != Boolean.TRUE.equals(expected.features().get("warps"))) {
            throw new IllegalStateException(label + " warps accessor drifted from the snapshot");
        }
        if (registry.getConfigVersion() != expected.configVersion()) {
            throw new IllegalStateException(label + " config version accessor drifted from the snapshot");
        }
        if (registry.isHydratedFromBackend() != expected.hydratedFromBackend()) {
            throw new IllegalStateException(label + " hydration accessor drifted from the snapshot");
        }
        if (registry.getDonationRanks() != expected.donationRanks()) {
            throw new IllegalStateException(label + " donation rank accessor did not return the snapshot list");
        }
        if (registry.getSettings("homes") != expected.settings().get("homes")) {
            throw new IllegalStateException(label + " settings accessor did not return the snapshot object");
        }
        int maxHomes = expected.settings().get("homes").get("max_homes").getAsInt();
        if (registry.getSetting("homes", "max_homes", -1) != maxHomes) {
            throw new IllegalStateException(label + " max_homes accessor drifted from the snapshot");
        }
        if (!registry.snapshot().equals(expected.features())) {
            throw new IllegalStateException(label + " feature snapshot accessor drifted");
        }
        if (!registry.knownFeatureKeys().equals(expected.features().keySet())) {
            throw new IllegalStateException(label + " known feature keys drifted from the snapshot");
        }
    }

    private static boolean matchesGeneration(FeatureRegistry.FeatureState state, int version, boolean enabled) {
        if (state == null || state.features() == null || state.settings() == null || state.donationRanks() == null) {
            return false;
        }
        if (state.configVersion() != version || !state.hydratedFromBackend() || state.features().size() != 3) {
            return false;
        }
        if (!Boolean.valueOf(enabled).equals(state.features().get("donation_ranks"))
                || !Boolean.valueOf(enabled).equals(state.features().get("homes"))
                || !Boolean.valueOf(!enabled).equals(state.features().get("warps"))) {
            return false;
        }
        JsonObject homes = state.settings().get("homes");
        if (homes == null || !homes.has("max_homes")) return false;
        try {
            if (homes.get("max_homes").getAsInt() != version) return false;
        } catch (RuntimeException ex) {
            return false;
        }
        if (enabled) {
            if (state.settings().size() != 1 || state.settings().containsKey("warps")) return false;
        } else {
            JsonObject warps = state.settings().get("warps");
            if (state.settings().size() != 2 || warps == null || !warps.has("max_warps")) return false;
            try {
                if (warps.get("max_warps").getAsInt() != version) return false;
            } catch (RuntimeException ex) {
                return false;
            }
        }
        List<FeatureRegistry.DonationRank> ranks = state.donationRanks();
        if (ranks.size() != (enabled ? 2 : 1)) return false;
        if (ranks.get(0).lpWeight != version || !"vip".equals(ranks.get(0).luckPermsGroup)) return false;
        return !enabled || (ranks.get(1).lpWeight == version && "mvp".equals(ranks.get(1).luckPermsGroup));
    }

    private static boolean matchesLocalDisable(FeatureRegistry.FeatureState state) {
        if (state == null || state.features() == null || state.settings() == null || state.donationRanks() == null) {
            return false;
        }
        if (state.configVersion() != 41 || !state.hydratedFromBackend() || state.features().size() != 3) {
            return false;
        }
        if (!Boolean.FALSE.equals(state.features().get("donation_ranks"))
                || !Boolean.TRUE.equals(state.features().get("homes"))
                || !Boolean.FALSE.equals(state.features().get("warps"))) {
            return false;
        }
        JsonObject homes = state.settings().get("homes");
        if (state.settings().size() != 1 || homes == null || !homes.has("max_homes")) return false;
        try {
            if (homes.get("max_homes").getAsInt() != 41) return false;
        } catch (RuntimeException ex) {
            return false;
        }
        List<FeatureRegistry.DonationRank> ranks = state.donationRanks();
        return ranks.size() == 2
                && ranks.get(0).lpWeight == 41
                && "vip".equals(ranks.get(0).luckPermsGroup)
                && ranks.get(1).lpWeight == 41
                && "mvp".equals(ranks.get(1).luckPermsGroup);
    }

    private static JsonObject generationConfig(int version, boolean enabled) {
        JsonObject config = new JsonObject();
        config.addProperty("config_version", version);
        JsonObject features = new JsonObject();
        features.addProperty("donation_ranks", enabled);
        features.addProperty("homes", enabled);
        features.addProperty("warps", !enabled);
        config.add("features", features);
        JsonObject settings = new JsonObject();
        JsonObject homes = new JsonObject();
        homes.addProperty("max_homes", version);
        settings.add("homes", homes);
        if (!enabled) {
            JsonObject warps = new JsonObject();
            warps.addProperty("max_warps", version);
            settings.add("warps", warps);
        }
        config.add("feature_settings", settings);
        JsonArray ranks = new JsonArray();
        ranks.add(rankEntry("vip", "VIP", version));
        if (enabled) ranks.add(rankEntry("mvp", "MVP", version));
        config.add("donation_ranks", ranks);
        return config;
    }

    private static JsonObject rankEntry(String slug, String name, int weight) {
        JsonObject rank = new JsonObject();
        rank.addProperty("slug", slug);
        rank.addProperty("display_name", name);
        rank.addProperty("luckperms_group", slug);
        rank.addProperty("weight", weight);
        rank.addProperty("prefix", "[" + name + "]");
        return rank;
    }

    private static String describeState(FeatureRegistry.FeatureState state) {
        if (state == null) return "null";
        return "version=" + state.configVersion()
                + " hydrated=" + state.hydratedFromBackend()
                + " features=" + state.features()
                + " settings=" + state.settings()
                + " ranks=" + state.donationRanks().size();
    }

    private static FeatureRegistry newFeatureRegistry() throws Exception {
        Constructor<FeatureRegistry> constructor = FeatureRegistry.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static Object newUser() throws Exception {
        Object nodes = newNodeMap();
        Class<?> type = Class.forName("net.luckperms.api.model.user.User");
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (isObjectMethod(method)) return objectCall(proxy, method, args);
            if ("data".equals(method.getName()) && (args == null || args.length == 0)) return nodes;
            if ("getUniqueId".equals(method.getName())) return PLAYER;
            return zero(method);
        });
    }

    private static Object newGroup(String name) throws Exception {
        Object nodes = newNodeMap();
        Class<?> type = Class.forName("net.luckperms.api.model.group.Group");
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (isObjectMethod(method)) return objectCall(proxy, method, args);
            if ("data".equals(method.getName()) && (args == null || args.length == 0)) return nodes;
            if ("getName".equals(method.getName())) return name;
            if ("getWeight".equals(method.getName())) return OptionalInt.empty();
            return zero(method);
        });
    }

    private static Object newNodeMap() throws Exception {
        Class<?> type = Class.forName("net.luckperms.api.model.data.NodeMap");
        List<Object> nodes = new ArrayList<>();
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            if (isObjectMethod(method)) return objectCall(proxy, method, args);
            if ("toCollection".equals(method.getName())) return List.copyOf(nodes);
            if ("add".equals(method.getName()) && args != null && args.length > 0 && args[0] != null) {
                nodes.add(args[0]);
                NODE_MUTATIONS.incrementAndGet();
                return DataMutateResult.SUCCESS;
            }
            if ("remove".equals(method.getName())) {
                if (args != null && args.length > 0) nodes.remove(args[0]);
                NODE_MUTATIONS.incrementAndGet();
                return DataMutateResult.SUCCESS;
            }
            return zero(method);
        });
    }

    private static Object newNodeBuilders() throws Exception {
        Class<?> registry = Class.forName("net.luckperms.api.node.NodeBuilderRegistry");
        Class<?> inheritanceBuilder = Class.forName("net.luckperms.api.node.types.InheritanceNode$Builder");
        Class<?> inheritanceNode = Class.forName("net.luckperms.api.node.types.InheritanceNode");
        Class<?> weightBuilder = Class.forName("net.luckperms.api.node.types.WeightNode$Builder");
        Class<?> weightNode = Class.forName("net.luckperms.api.node.types.WeightNode");
        Class<?> prefixBuilder = Class.forName("net.luckperms.api.node.types.PrefixNode$Builder");
        Class<?> prefixNode = Class.forName("net.luckperms.api.node.types.PrefixNode");
        Class<?> permissionBuilder = Class.forName("net.luckperms.api.node.types.PermissionNode$Builder");
        Class<?> permissionNode = Class.forName("net.luckperms.api.node.types.PermissionNode");
        Class<?> metaBuilder = Class.forName("net.luckperms.api.node.types.MetaNode$Builder");
        Class<?> metaNode = Class.forName("net.luckperms.api.node.types.MetaNode");
        return Proxy.newProxyInstance(registry.getClassLoader(), new Class<?>[]{registry}, (proxy, method, args) -> {
            if (isObjectMethod(method)) return objectCall(proxy, method, args);
            if ("forInheritance".equals(method.getName())) {
                return newNodeBuilder(inheritanceBuilder, inheritanceNode, NodeType.INHERITANCE);
            }
            if ("forWeight".equals(method.getName())) return newNodeBuilder(weightBuilder, weightNode, NodeType.WEIGHT);
            if ("forPrefix".equals(method.getName())) return newNodeBuilder(prefixBuilder, prefixNode, NodeType.PREFIX);
            if ("forPermission".equals(method.getName())) {
                return newNodeBuilder(permissionBuilder, permissionNode, NodeType.PERMISSION);
            }
            if ("forMeta".equals(method.getName())) return newNodeBuilder(metaBuilder, metaNode, NodeType.META);
            return zero(method);
        });
    }

    private static Object newNodeBuilder(Class<?> builderType, Class<?> nodeType, NodeType<?> kind) {
        String[] key = {""};
        String[] value = {""};
        int[] number = {0};
        return Proxy.newProxyInstance(builderType.getClassLoader(), new Class<?>[]{builderType}, (proxy, method, args) -> {
            if (isObjectMethod(method)) return objectCall(proxy, method, args);
            String name = method.getName();
            if ("build".equals(name)) return newRecordedNode(nodeType, kind, key[0], value[0], number[0]);
            if (args != null && args.length > 0 && args[0] instanceof String) {
                if ("value".equals(name) || "prefix".equals(name)) value[0] = (String) args[0];
                else key[0] = (String) args[0];
            } else if (args != null && args.length > 0 && args[0] instanceof Integer
                    && ("weight".equals(name) || "priority".equals(name))) {
                number[0] = (Integer) args[0];
            }
            if (method.getReturnType() == void.class) return null;
            if (method.getReturnType().isPrimitive()) return zero(method);
            return proxy;
        });
    }

    private static Object newRecordedNode(Class<?> nodeType, NodeType<?> kind, String key, String value, int number) {
        return Proxy.newProxyInstance(nodeType.getClassLoader(), new Class<?>[]{nodeType}, (proxy, method, args) -> {
            if (isObjectMethod(method)) return objectCall(proxy, method, args);
            String name = method.getName();
            if ("getType".equals(name)) return kind;
            if ("getGroupName".equals(name) || "getPermission".equals(name) || "getMetaKey".equals(name)
                    || "getKey".equals(name)) {
                return key;
            }
            if ("getMetaValue".equals(name)) return value.isEmpty() ? key : value;
            if ("getWeight".equals(name) && method.getReturnType() == int.class) return number;
            if ("getPriority".equals(name)) return number;
            if ("getValue".equals(name) && method.getReturnType() == boolean.class) return true;
            return zero(method);
        });
    }

    private static final class AsyncScript {
        private CompletableFuture<Object> userLoad;
        private CompletableFuture<Object> groupLoad;
        private CompletableFuture<Object> groupCreate;
        private CompletableFuture<Object> userSave;
        private CompletableFuture<Object> groupSave;
        private boolean realGroups;
        private boolean realUserOnLoad;
    }

    private static final class BlockingBoolean extends JsonElement {
        private final boolean value;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingBoolean(boolean value) {
            this.value = value;
        }

        @Override
        public JsonElement deepCopy() {
            return this;
        }

        @Override
        public boolean getAsBoolean() {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("feature replacement block was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("feature replacement block interrupted", interrupted);
            }
            return value;
        }
    }

    private static final class ProbeLoader extends ClassLoader {
        ProbeLoader(ClassLoader parent) { super(parent); }

        Class<?> define(byte[] bytes) {
            return defineClass(PROBE_CLASS, bytes, 0, bytes.length);
        }
    }

    private static byte[] probeClassBytes(Class<?> superType) throws Exception {
        Constructor<?> constructor = null;
        for (Constructor<?> candidate : superType.getDeclaredConstructors()) {
            if (java.lang.reflect.Modifier.isPublic(candidate.getModifiers())
                    && (constructor == null || candidate.getParameterCount() > constructor.getParameterCount())) {
                constructor = candidate;
            }
        }
        if (constructor == null) throw new IllegalStateException("ServerPlayer has no public constructor");
        String internal = PROBE_CLASS.replace('.', '/');
        String superInternal = superType.getName().replace('.', '/');
        String uuidDesc = "()Ljava/util/UUID;";
        String nameDesc = "()Lnet/minecraft/network/chat/Component;";
        String uuidField = "Ljava/util/UUID;";
        String nameField = "Lnet/minecraft/network/chat/Component;";
        String atomicDesc = "Ljava/util/concurrent/atomic/AtomicInteger;";
        Pool pool = new Pool();
        int thisClass = pool.cls(internal);
        int superClass = pool.cls(superInternal);
        int atomicClass = pool.cls("java/util/concurrent/atomic/AtomicInteger");
        int uuidReads = pool.field(internal, "UUID_READS", atomicDesc);
        int nameReads = pool.field(internal, "NAME_READS", atomicDesc);
        int uuidValue = pool.field(internal, "uuidValue", uuidField);
        int nameValue = pool.field(internal, "nameValue", nameField);
        int atomicInit = pool.method("java/util/concurrent/atomic/AtomicInteger", "<init>", "()V");
        int increment = pool.method("java/util/concurrent/atomic/AtomicInteger", "incrementAndGet", "()I");
        int superInit = pool.method(superInternal, "<init>", constructorDescriptor(constructor));
        int codeName = pool.utf("Code");
        pool.utf("<init>");
        pool.utf("()V");
        pool.utf("getUUID");
        pool.utf(uuidDesc);
        pool.utf("getName");
        pool.utf(nameDesc);
        pool.utf("<clinit>");
        pool.utf("UUID_READS");
        pool.utf("NAME_READS");
        pool.utf("uuidValue");
        pool.utf("nameValue");
        pool.utf(atomicDesc);
        pool.utf(uuidField);
        pool.utf(nameField);

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeInt(0xCAFEBABE);
        out.writeShort(0);
        out.writeShort(52);
        pool.write(out);
        out.writeShort(0x0021);
        out.writeShort(thisClass);
        out.writeShort(superClass);
        out.writeShort(0);
        out.writeShort(4);
        writeField(out, pool, 0x0009, "UUID_READS", atomicDesc);
        writeField(out, pool, 0x0009, "NAME_READS", atomicDesc);
        writeField(out, pool, 0x0049, "uuidValue", uuidField);
        writeField(out, pool, 0x0049, "nameValue", nameField);
        out.writeShort(4);
        writeMethod(out, pool, codeName, 0x0001, "<init>", "()V", constructorCode(constructor, superInit));
        writeMethod(out, pool, codeName, 0x0001, "getUUID", uuidDesc, readCode(uuidReads, increment, uuidValue));
        writeMethod(out, pool, codeName, 0x0001, "getName", nameDesc, readCode(nameReads, increment, nameValue));
        writeMethod(out, pool, codeName, 0x0008, "<clinit>", "()V", clinit(atomicClass, uuidReads, nameReads, atomicInit));
        out.writeShort(0);
        out.flush();
        return raw.toByteArray();
    }

    private static void writeField(DataOutputStream out, Pool pool, int access, String name, String desc) throws Exception {
        out.writeShort(access);
        out.writeShort(pool.utf(name));
        out.writeShort(pool.utf(desc));
        out.writeShort(0);
    }

    private static void writeMethod(DataOutputStream out, Pool pool, int codeName, int access,
                                    String name, String desc, byte[] code) throws Exception {
        out.writeShort(access);
        out.writeShort(pool.utf(name));
        out.writeShort(pool.utf(desc));
        out.writeShort(1);
        out.writeShort(codeName);
        out.writeInt(code.length);
        out.write(code);
    }

    private static byte[] constructorCode(Constructor<?> constructor, int superInit) throws Exception {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0x2A);
        int stack = 1;
        int max = 1;
        for (Class<?> parameter : constructor.getParameterTypes()) {
            int width = (parameter == long.class || parameter == double.class) ? 2 : 1;
            if (parameter == long.class) code.write(0x09);
            else if (parameter == double.class) code.write(0x0E);
            else if (parameter == float.class) code.write(0x0B);
            else if (parameter.isPrimitive()) code.write(0x03);
            else code.write(0x01);
            stack += width;
            max = Math.max(max, stack);
        }
        code.write(0xB7);
        code.write((superInit >> 8) & 0xFF);
        code.write(superInit & 0xFF);
        code.write(0xB1);
        return codeAttribute(max, 1, code.toByteArray());
    }

    private static byte[] readCode(int counterField, int increment, int valueField) throws Exception {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        code.write(0xB2);
        code.write((counterField >> 8) & 0xFF);
        code.write(counterField & 0xFF);
        code.write(0xB6);
        code.write((increment >> 8) & 0xFF);
        code.write(increment & 0xFF);
        code.write(0x57);
        code.write(0xB2);
        code.write((valueField >> 8) & 0xFF);
        code.write(valueField & 0xFF);
        code.write(0xB0);
        return codeAttribute(1, 1, code.toByteArray());
    }

    private static byte[] clinit(int atomicClass, int uuidReads, int nameReads, int atomicInit) throws Exception {
        ByteArrayOutputStream code = new ByteArrayOutputStream();
        writeNewInitPut(code, atomicClass, atomicInit, uuidReads);
        writeNewInitPut(code, atomicClass, atomicInit, nameReads);
        code.write(0xB1);
        return codeAttribute(2, 0, code.toByteArray());
    }

    private static void writeNewInitPut(ByteArrayOutputStream code, int classIndex, int init, int field) {
        code.write(0xBB);
        code.write((classIndex >> 8) & 0xFF);
        code.write(classIndex & 0xFF);
        code.write(0x59);
        code.write(0xB7);
        code.write((init >> 8) & 0xFF);
        code.write(init & 0xFF);
        code.write(0xB3);
        code.write((field >> 8) & 0xFF);
        code.write(field & 0xFF);
    }

    private static byte[] codeAttribute(int maxStack, int maxLocals, byte[] code) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(raw);
        out.writeShort(maxStack);
        out.writeShort(maxLocals);
        out.writeInt(code.length);
        out.write(code);
        out.writeShort(0);
        out.writeShort(0);
        out.flush();
        return raw.toByteArray();
    }

    private static String constructorDescriptor(Constructor<?> constructor) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : constructor.getParameterTypes()) descriptor.append(fieldDescriptor(parameter));
        descriptor.append(")V");
        return descriptor.toString();
    }

    private static String fieldDescriptor(Class<?> type) {
        if (type == int.class) return "I";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == short.class) return "S";
        if (type == char.class) return "C";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type == void.class) return "V";
        if (type.isArray()) return type.getName().replace('.', '/');
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static final class Pool {
        private final List<byte[]> entries = new ArrayList<>();
        private final Map<String, Integer> utf8 = new LinkedHashMap<>();
        private final Map<String, Integer> classes = new LinkedHashMap<>();
        private final Map<String, Integer> nameAndTypes = new LinkedHashMap<>();
        private final Map<String, Integer> refs = new LinkedHashMap<>();

        int utf(String value) {
            Integer existing = utf8.get(value);
            if (existing != null) return existing;
            try {
                ByteArrayOutputStream raw = new ByteArrayOutputStream();
                DataOutputStream out = new DataOutputStream(raw);
                out.writeByte(1);
                out.writeUTF(value);
                entries.add(raw.toByteArray());
                int index = entries.size();
                utf8.put(value, index);
                return index;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        int cls(String internal) {
            Integer existing = classes.get(internal);
            if (existing != null) return existing;
            int index = add(7, u2(utf(internal)));
            classes.put(internal, index);
            return index;
        }

        int field(String owner, String name, String desc) {
            return ref(9, owner, name, desc);
        }

        int method(String owner, String name, String desc) {
            return ref(10, owner, name, desc);
        }

        void write(DataOutputStream out) throws Exception {
            out.writeShort(entries.size() + 1);
            for (byte[] entry : entries) out.write(entry);
        }

        private int ref(int tag, String owner, String name, String desc) {
            String key = tag + ":" + owner + "." + name + desc;
            Integer existing = refs.get(key);
            if (existing != null) return existing;
            int index = add(tag, concat(u2(cls(owner)), u2(nameAndType(name, desc))));
            refs.put(key, index);
            return index;
        }

        private int nameAndType(String name, String desc) {
            String key = name + ":" + desc;
            Integer existing = nameAndTypes.get(key);
            if (existing != null) return existing;
            int index = add(12, concat(u2(utf(name)), u2(utf(desc))));
            nameAndTypes.put(key, index);
            return index;
        }

        private int add(int tag, byte[] rest) {
            byte[] bytes = new byte[rest.length + 1];
            bytes[0] = (byte) tag;
            System.arraycopy(rest, 0, bytes, 1, rest.length);
            entries.add(bytes);
            return entries.size();
        }

        private static byte[] u2(int value) {
            return new byte[] {(byte) ((value >> 8) & 0xFF), (byte) (value & 0xFF)};
        }

        private static byte[] concat(byte[] left, byte[] right) {
            byte[] out = new byte[left.length + right.length];
            System.arraycopy(left, 0, out, 0, left.length);
            System.arraycopy(right, 0, out, left.length, right.length);
            return out;
        }
    }
}
