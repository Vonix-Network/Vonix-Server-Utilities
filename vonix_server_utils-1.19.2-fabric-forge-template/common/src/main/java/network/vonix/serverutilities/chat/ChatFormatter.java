package network.vonix.serverutilities.chat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import net.minecraft.server.level.ServerPlayer;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.donation_ranks.LuckPermsBridge;

import java.util.Optional;

/** Standalone optional LuckPerms prefix/suffix/name-color chat formatter. */
public final class ChatFormatter {
    private ChatFormatter() {}

    public static Optional<Component> format(ServerPlayer player, String rawMessage) {
        try {
            Optional<LuckPermsBridge.UserPrefixInfo> info = LuckPermsBridge.getUserPrefixInfo(player.getUUID());
            if (!info.isPresent()) return Optional.empty();
            String prefix = info.get().prefix;
            String suffix = info.get().suffix;
            String nameColor = info.get().nameColor;

            MutableComponent out = Component.literal("");
            if (prefix != null && !prefix.isEmpty()) out.append(parseLegacy(prefix)).append(Component.literal(" "));
            out.append(Component.literal("<"));
            MutableComponent name = Component.literal(player.getName().getString());
            if (nameColor != null && !nameColor.isEmpty()) applyColor(name, nameColor);
            out.append(name).append(Component.literal("> "));
            if (suffix != null && !suffix.isEmpty()) out.append(parseLegacy(suffix)).append(Component.literal(" "));
            out.append(parseLegacy(rawMessage == null ? "" : rawMessage));
            return Optional.of(out);
        } catch (LinkageError | RuntimeException t) {
            VonixServerUtilities.LOGGER.warn("[VonixSU] ChatFormatter failed", t);
            return Optional.empty();
        }
    }

    public static MutableComponent parseLegacy(String input) {
        if (input == null || input.isEmpty()) return Component.literal("");
        String text = input.replace('&', '\u00a7');
        MutableComponent root = Component.literal("");
        Style current = Style.EMPTY;
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == '#' && i + 7 < text.length()) {
                    flush(root, buf, current);
                    try { current = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(text.substring(i + 2, i + 8), 16))); }
                    catch (NumberFormatException ignored) { }
                    i += 8;
                    continue;
                }
                ChatFormatting fmt = ChatFormatting.getByCode(code);
                if (fmt != null) {
                    flush(root, buf, current);
                    if (fmt == ChatFormatting.RESET) current = Style.EMPTY;
                    else if (fmt.isColor()) current = Style.EMPTY.withColor(fmt);
                    else current = current.applyFormat(fmt);
                    i += 2;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flush(root, buf, current);
        return root;
    }

    private static void flush(MutableComponent root, StringBuilder buf, Style style) {
        if (buf.length() == 0) return;
        root.append(Component.literal(buf.toString()).setStyle(style));
        buf.setLength(0);
    }

    private static void applyColor(MutableComponent component, String color) {
        try {
            if (color.startsWith("#")) component.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(color.substring(1), 16))));
            else if (color.startsWith("&#")) component.setStyle(Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(color.substring(2), 16))));
            else {
                ChatFormatting fmt = ChatFormatting.getByName(color.toUpperCase());
                if (fmt != null && fmt.isColor()) component.setStyle(Style.EMPTY.withColor(fmt));
            }
        } catch (RuntimeException ignored) { }
    }
}
