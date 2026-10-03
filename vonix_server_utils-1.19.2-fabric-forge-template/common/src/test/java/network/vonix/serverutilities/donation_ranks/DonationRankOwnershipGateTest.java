package network.vonix.serverutilities.donation_ranks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import network.vonix.serverutilities.features.FeatureRegistry;
import network.vonix.serverutilities.features.ServerConfigClient;
import network.vonix.serverutilities.venary.VenaryClient;
import network.vonix.serverutilities.venary.VenaryConfig;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
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
 * {@link RankSyncTask#onJoin(UUID, String)}, the sync body used by
 * {@link RankSyncTask#onJoin(net.minecraft.server.level.ServerPlayer)}, and
 * also that public join hook when a stand-in player can be allocated.
 */
public final class DonationRankOwnershipGateTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final List<String> FAILURES = new ArrayList<>();
    private static final AtomicInteger GROUP_CALLS = new AtomicInteger();
    private static final AtomicInteger USER_CALLS = new AtomicInteger();
    private static final AtomicInteger RANK_HTTP = new AtomicInteger();
    private static final ConcurrentLinkedQueue<String> PATHS = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> CONFIG_BODY = new AtomicReference<>("{}");

    private static int port;
    private static AtomicBoolean inFlight;
    private static long enabledPlayerNanos = TimeUnit.SECONDS.toNanos(1);

    private DonationRankOwnershipGateTest() {}

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            PATHS.add(path);
            byte[] body;
            if (path.contains("/ranks")) {
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
        server.start();
        port = server.getAddress().getPort();
        try {
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
            run("disabled-public-onJoin", DonationRankOwnershipGateTest::disabledPublicOnJoinDoesNotSync);

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

    private static void disabledPublicOnJoinDoesNotSync() throws Exception {
        check(!FeatureRegistry.getInstance().isDonationRankSyncEnabled(), "public onJoin fixture must be disabled");
        int users = USER_CALLS.get();
        int http = RANK_HTTP.get();
        Object player = standInPlayer();
        Method onJoin = RankSyncTask.class.getMethod("onJoin", player.getClass());
        onJoin.invoke(null, player);
        long quiet = Math.min(TimeUnit.SECONDS.toNanos(5),
                Math.max(TimeUnit.SECONDS.toNanos(1), enabledPlayerNanos * 3));
        awaitQuiet(users, http, quiet, "disabled RankSyncTask.onJoin(ServerPlayer)");
        trace("disabled-public-onJoin", GROUP_CALLS.get(), users, http);
    }

    private static Object standInPlayer() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        Class<?> serverPlayer = Class.forName("net.minecraft.server.level.ServerPlayer");
        Object player = allocate.invoke(unsafe, serverPlayer);
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", java.lang.reflect.Field.class);
        Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class, Object.class);
        java.lang.reflect.Field uuid = Class.forName("net.minecraft.world.entity.Entity").getDeclaredField("uuid");
        putObject.invoke(unsafe, player, objectFieldOffset.invoke(unsafe, uuid), PLAYER);
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        Object profile = profileClass.getConstructor(UUID.class, String.class).newInstance(PLAYER, "Steve");
        java.lang.reflect.Field gameProfile = Class.forName("net.minecraft.world.entity.player.Player")
                .getDeclaredField("gameProfile");
        putObject.invoke(unsafe, player, objectFieldOffset.invoke(unsafe, gameProfile), profile);
        return player;
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
        Object groups = Proxy.newProxyInstance(groupManager.getClassLoader(), new Class<?>[]{groupManager},
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) return objectCall(proxy, method, args);
                    if ("loadGroup".equals(method.getName())) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    }
                    if ("createAndLoadGroup".equals(method.getName())) {
                        return CompletableFuture.completedFuture(null);
                    }
                    return zero(method);
                });
        Object users = Proxy.newProxyInstance(userManager.getClassLoader(), new Class<?>[]{userManager},
                (proxy, method, args) -> {
                    if (isObjectMethod(method)) return objectCall(proxy, method, args);
                    if ("loadUser".equals(method.getName())) {
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
}
