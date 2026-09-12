package network.vonix.serverutilities.donation_ranks;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import network.vonix.serverutilities.VonixServerUtilities;

import java.util.Optional;
import java.util.UUID;

/** Package-private LuckPerms-typed implementation, linked only when LP exists. */
final class LuckPermsBridgeImpl {
    private static volatile LuckPerms cachedApi;

    private LuckPermsBridgeImpl() {}

    private static Optional<LuckPerms> api() {
        LuckPerms api = cachedApi;
        if (api != null) return Optional.of(api);
        try {
            api = LuckPermsProvider.get();
            cachedApi = api;
            return Optional.of(api);
        } catch (IllegalStateException notReady) {
            return Optional.empty();
        }
    }

    static Optional<LuckPermsBridge.UserPrefixInfo> getUserPrefixInfo(UUID player) {
        Optional<LuckPerms> lp = api();
        if (lp.isEmpty()) return Optional.empty();
        User user = lp.get().getUserManager().getUser(player);
        if (user == null) return Optional.empty();
        CachedMetaData meta = user.getCachedData().getMetaData();
        return Optional.of(new LuckPermsBridge.UserPrefixInfo(
                meta.getPrefix(), meta.getSuffix(), meta.getMetaValue("name-color")));
    }

    static boolean hasPermission(UUID player, String node) {
        Optional<LuckPerms> lp = api();
        if (lp.isEmpty()) return false;
        User user = lp.get().getUserManager().getUser(player);
        if (user == null) return false;
        return user.getCachedData().getPermissionData(QueryOptions.defaultContextualOptions())
                .checkPermission(node).asBoolean();
    }
}
