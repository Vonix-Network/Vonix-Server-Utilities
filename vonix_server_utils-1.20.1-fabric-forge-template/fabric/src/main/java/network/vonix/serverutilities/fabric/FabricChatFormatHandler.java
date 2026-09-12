package network.vonix.serverutilities.fabric;

import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import network.vonix.serverutilities.VonixServerUtilities;
import network.vonix.serverutilities.chat.ChatFormatter;

import java.util.Optional;

/** Fabric 1.20.1 chat formatter using the server message allow hook. */
public final class FabricChatFormatHandler {

    private FabricChatFormatHandler() {}

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((message, sender, params) -> {
            if (sender == null) return true;
            try {
                Optional<Component> formatted = ChatFormatter.format(sender, message.decoratedContent());
                if (formatted.isEmpty()) return true;
                sender.server.getPlayerList().broadcastSystemMessage(formatted.get(), false);
                return false;
            } catch (Throwable t) {
                VonixServerUtilities.LOGGER.error("[VonixSU] Fabric chat formatting failed", t);
                return true;
            }
        });
        VonixServerUtilities.LOGGER.info("[VonixSU/chat] Fabric LuckPerms chat formatter registered.");
    }
}
