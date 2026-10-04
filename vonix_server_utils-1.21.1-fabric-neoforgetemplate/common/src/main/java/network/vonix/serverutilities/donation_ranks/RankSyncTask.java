package network.vonix.serverutilities.donation_ranks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.features.FeatureRegistry;
import network.vonix.serverutilities.venary.VenaryClient;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player donation-rank reconciliation, fired on player join.
 *
 * <p>Flow:
 * <ol>
 *   <li>Short-circuit if {@code donation_ranks} feature is disabled or
 *       LuckPerms isn't installed.</li>
 *   <li>Async {@code GET /minecraft/players/&lt;uuid&gt;/ranks} via VenaryClient.
 *       The completion path re-checks the feature gate, so a fetch that
 *       started while enabled does not mutate LuckPerms if the feature was
 *       explicitly disabled before the response is applied. The LuckPerms
 *       user-load, node-edit, and save continuations check again, and a
 *       late disable does not audit a rank change.</li>
 *   <li>Build {@code shouldHaveGroups} from the response's {@code ranks[*]
 *       .luckperms_group}, filtered to non-expired entries.</li>
 *   <li>Build {@code allManagedGroups} from the cached
 *       {@link FeatureRegistry#getDonationRanks()} list — this IS the
 *       whitelist that protects non-donation groups from being touched.</li>
 *   <li>{@link LuckPermsBridge#setUserGroups} reconciles + saves.</li>
 *   <li>For each added/removed group post a {@code rank_apply} /
 *       {@code rank_remove} audit row.</li>
 * </ol>
 *
 * <p>PORT-NOTE: ServerPlayer.getUUID() / .getName().getString() are stable
 * across all four MC targets.
 */
public final class RankSyncTask {

    private RankSyncTask() {}

    /** Hook from EventHandler PLAYER_JOIN. */
    public static void onJoin(ServerPlayer player) {
        // Defensive belt-and-braces: even with the holder-class probe inside
        // LuckPermsBridge, wrap the whole entry point so any LinkageError /
        // RuntimeException coming out of any LP-touching code cannot escape
        // and crash player join.
        try {
            if (player == null) return;
            // Gate before identity reads or LuckPerms so a disabled feature
            // performs no player donation-rank synchronization.
            if (!FeatureRegistry.getInstance().isDonationRankSyncEnabled()) return;
            if (!LuckPermsBridge.isPresent()) return;
            VenaryClient client = VenaryClient.get();
            if (client == null) return;
            onJoin(player.getUUID(), player.getName().getString());
        } catch (LinkageError | RuntimeException t) {
            VonixServerUtilities.LOGGER.warn(
                    "[VonixSU/Ranks] onJoin disabled this tick due to {}: {}",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /**
     * Real player-sync entry used by {@link #onJoin(ServerPlayer)}.
     * Repeats the authoritative feature gate so every caller fails closed
     * when {@code donation_ranks} is disabled.
     */
    static void onJoin(UUID uuid, String username) {
        if (!FeatureRegistry.getInstance().isDonationRankSyncEnabled()) return;
        if (!LuckPermsBridge.isPresent()) return;

        VenaryClient client = VenaryClient.get();
        if (client == null) return;

        client.getPlayerRanks(uuid).handle((resp, err) -> {
            try {
                // Stale in-flight responses must not apply after a later disable.
                if (!FeatureRegistry.getInstance().isDonationRankSyncEnabled()) return null;
                applyResponse(uuid, username, resp);
            } catch (Exception e) {
                VonixServerUtilities.LOGGER.debug(
                        "[VonixSU/Ranks] sync for {} threw: {}", username, e.getMessage());
            }
            return null;
        });
    }

    private static void applyResponse(UUID uuid, String username, JsonObject resp) {
        if (resp == null) return;
        // If the account isn't linked, leave LP state alone.
        boolean linked = resp.has("linked") && resp.get("linked").getAsBoolean();
        if (!linked) return;

        // Build desired group set from the live response.
        Set<String> shouldHave = new LinkedHashSet<>();
        if (resp.has("ranks") && resp.get("ranks").isJsonArray()) {
            JsonArray arr = resp.getAsJsonArray("ranks");
            long now = System.currentTimeMillis();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if (!o.has("luckperms_group")) continue;
                String group;
                try { group = o.get("luckperms_group").getAsString(); }
                catch (Exception e) { continue; }
                if (group == null || group.isBlank()) continue;
                if (isExpired(o, now)) continue;
                shouldHave.add(group.toLowerCase(Locale.ROOT));
            }
        }

        // Build the managed-group whitelist from the cached donation_ranks
        // table — NEVER refetched here; the poller owns that.
        List<FeatureRegistry.DonationRank> ranks = FeatureRegistry.getInstance().getDonationRanks();
        Set<String> managed = new LinkedHashSet<>();
        for (FeatureRegistry.DonationRank r : ranks) {
            if (r.luckPermsGroup != null && !r.luckPermsGroup.isBlank()) {
                managed.add(r.luckPermsGroup.toLowerCase(Locale.ROOT));
            }
        }
        if (managed.isEmpty()) return; // nothing to sync against

        // Check again immediately before mutation. Parsing the response can
        // race a disable that landed after the completion-path check above.
        if (!FeatureRegistry.getInstance().isDonationRankSyncEnabled()) return;
        LuckPermsBridge.setUserGroups(uuid, shouldHave, managed).thenAccept(diff -> {
            // Save completion can outlive the pre-call gate. Do not log or audit
            // a mutation after donation_ranks has been explicitly disabled.
            if (!FeatureRegistry.getInstance().isDonationRankSyncEnabled()) return;
            if (diff == null || diff.isEmpty()) return;
            VonixServerUtilities.LOGGER.info(
                    "[VonixSU/Ranks] {} synced — added {} removed {}",
                    username, diff.added, diff.removed);
            VenaryClient v = VenaryClient.get();
            if (v == null) return;
            for (String g : diff.added)   v.postAudit("rank_apply",  "mod", "rank_sync", uuid, oneFieldPayload("luckperms_group", g));
            for (String g : diff.removed) v.postAudit("rank_remove", "mod", "rank_sync", uuid, oneFieldPayload("luckperms_group", g));
        });
    }

    private static boolean isExpired(JsonObject o, long nowMillis) {
        if (!o.has("expires_at") || o.get("expires_at").isJsonNull()) return false;
        try {
            String iso = o.get("expires_at").getAsString();
            long t = java.time.Instant.parse(iso).toEpochMilli();
            return t < nowMillis;
        } catch (Exception e) {
            // Unparseable timestamps are treated as "no expiry" — fail-open.
            return false;
        }
    }

    private static JsonObject oneFieldPayload(String k, String v) {
        JsonObject p = new JsonObject();
        p.addProperty(k, v);
        return p;
    }
}
