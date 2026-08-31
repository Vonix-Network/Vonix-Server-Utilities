package network.vonix.serverutilities.command;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.config.ModConfig;
import network.vonix.serverutilities.features.PermissionGate;
import network.vonix.serverutilities.features.FeatureRegistry;

import java.util.Map;
import java.util.TreeMap;

/**
 * Sub-tree under {@code /vonixsu feature}:
 * <pre>
 *   /vonixsu feature list
 *   /vonixsu feature enable  &lt;key&gt;
 *   /vonixsu feature disable &lt;key&gt;
 *   /vonixsu feature reload
 *   /vonixsu feature status  &lt;key&gt;
 * </pre>
 *
 * <p>All operations require permission level 3 (server-op) or the console.
 *
 * Local toggles persist in memory until restart or config reload.
 */

public final class FeatureCommand {

    private FeatureCommand() {}

    /** Returns a builder that can be {@code .then()}-attached to /vonixsu. */
    public static LiteralArgumentBuilder<CommandSourceStack> tree() {
        return Commands.literal("feature")
                .requires(PermissionGate.requires("vsu.admin.manage", 3))
                .then(Commands.literal("list").executes(FeatureCommand::list))
                .then(Commands.literal("reload").executes(FeatureCommand::reload))
                .then(Commands.literal("enable")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> setEnabled(ctx, StringArgumentType.getString(ctx, "key"), true))))
                .then(Commands.literal("disable")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> setEnabled(ctx, StringArgumentType.getString(ctx, "key"), false))))
                .then(Commands.literal("status")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "key")))))
                .executes(FeatureCommand::list);
    }

    // ── list ────────────────────────────────────────────────────────────────

    private static int list(CommandContext<CommandSourceStack> ctx) {
        FeatureRegistry reg = FeatureRegistry.getInstance();
        Map<String, Boolean> snap = new TreeMap<>(reg.snapshot());
        ctx.getSource().sendSuccess(Component.literal(
                "§6[VSU] §fFeatures §7(config v" + reg.getConfigVersion()
                        + (reg.isHydratedFromBackend() ? "" : "") + "§7):"), false);
        if (snap.isEmpty()) {
            ctx.getSource().sendSuccess(Component.literal("  §7(none known yet — none known — enable features locally)"), false);
            return 1;
        }
        for (Map.Entry<String, Boolean> e : snap.entrySet()) {
            String tag = e.getValue() ? "§a[ON] " : "§c[OFF]";
            ctx.getSource().sendSuccess(Component.literal("  " + tag + " §f" + e.getKey()), false);
        }
        return snap.size();
    }

    // ── enable / disable ────────────────────────────────────────────────────

    private static int setEnabled(CommandContext<CommandSourceStack> ctx, String key, boolean enable) {
        FeatureRegistry reg = FeatureRegistry.getInstance();
        boolean changed = reg.setEnabledLocal(key, enable);
        String label = enable ? "enabled" : "disabled";

        ctx.getSource().sendSuccess(Component.literal(
                "§a[VSU] Feature §e" + key + "§a " + label + " §7(local)."), true);
        if (!changed) {
            ctx.getSource().sendSuccess(Component.literal(
                    "§7    (already " + label + ", no change)"), false);
        }

        // Refresh command trees on the main thread so the change takes effect immediately.
        var server = ctx.getSource().getServer();
        if (server != null) {
            server.execute(() -> {
                try {
                    for (var p : server.getPlayerList().getPlayers()) server.getCommands().sendCommands(p);
                } catch (Exception ignored) {}
            });
        }

        return 1;
    }

    // ── reload ──────────────────────────────────────────────────────────────

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(Component.literal(
                "§a[VSU] Reloading local configuration."), true);
        ModConfig.INSTANCE.reload();
        FeatureRegistry.applyPanelGatesIfPresent(ModConfig.INSTANCE.panelGates());
        VonixServerUtilities.LOGGER.info("[VonixSU] /vonixsu feature reload requested by {}",
                ctx.getSource().getTextName());
        return 1;
    }

    // ── status ──────────────────────────────────────────────────────────────

    private static int status(CommandContext<CommandSourceStack> ctx, String key) {
        FeatureRegistry reg = FeatureRegistry.getInstance();
        boolean enabled = reg.isEnabled(key);
        JsonObject settings = reg.getSettings(key);
        ctx.getSource().sendSuccess(Component.literal(
                "§6[VSU] Feature §e" + key + "§6: " + (enabled ? "§aON" : "§cOFF")), false);
        if (settings == null || settings.size() == 0) {
            ctx.getSource().sendSuccess(Component.literal("  §7(no settings)"), false);
        } else {
            String pretty = new GsonBuilder().setPrettyPrinting().create().toJson(settings);
            for (String line : pretty.split("\n")) {
                ctx.getSource().sendSuccess(Component.literal("  §7" + line), false);
            }
        }
        return 1;
    }

    /** Used by /vonixsu status — short, one-line summary. */
    public static String summaryLine() {
        FeatureRegistry reg = FeatureRegistry.getInstance();
        Map<String, Boolean> snap = reg.snapshot();
        int on = 0, off = 0;
        for (boolean v : snap.values()) { if (v) on++; else off++; }
        return "§7Features: §a" + on + " on§7 / §c" + off + " off§7 (config v" + reg.getConfigVersion()
                + (reg.isHydratedFromBackend() ? "" : "") + ")";
    }
}
