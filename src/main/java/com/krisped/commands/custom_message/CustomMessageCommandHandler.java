package com.krisped.commands.custom_message;

import com.krisped.KPWebhookPlugin;
import com.krisped.KPWebhookPreset;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Locale;
import java.util.Map;

/**
 * Handles CUSTOM_MESSAGE command.
 * Syntax: CUSTOM_MESSAGE <ChatType|id> <text>
 * If chat type token cannot be resolved, entire remainder is treated as message using default GAMEMESSAGE.
 * Does NOT trigger RuneLite notifier (reserved for NOTIFY command).
 */
@Singleton
public class CustomMessageCommandHandler {
    private final Client client;
    private final ClientThread clientThread;

    @Inject
    public CustomMessageCommandHandler(Client client, ClientThread clientThread) {
        this.client = client;
        this.clientThread = clientThread;
    }

    public boolean handle(String line, KPWebhookPreset rule, Map<String,String> ctx) {
        if(line == null) return false;
        String trim = line.trim();
        if(!trim.toUpperCase(Locale.ROOT).startsWith("CUSTOM_MESSAGE")) return false;
        String body = trim.length() > "CUSTOM_MESSAGE".length() ? trim.substring("CUSTOM_MESSAGE".length()).trim() : "";
        if(body.isEmpty()) return true; // consumed but nothing to do
        String firstToken;
        String remainder;
        int sp = body.indexOf(' ');
        if(sp > 0){
            firstToken = body.substring(0, sp).trim();
            remainder = body.substring(sp+1).trim();
        } else {
            firstToken = body.trim();
            remainder = null; // maybe only message text without explicit type
        }
        ChatMessageType resolved = null;
        if(firstToken != null){
            resolved = KPWebhookPlugin.resolveChatType(firstToken);
        }
        String message;
        if(resolved != null && remainder != null && !remainder.isEmpty()){
            message = remainder;
        } else if(resolved != null && (remainder == null || remainder.isEmpty())){
            // Only type provided, ignore
            return true;
        } else {
            // first token wasn't a type; treat whole body as message
            resolved = ChatMessageType.GAMEMESSAGE;
            message = body;
        }
        final ChatMessageType chatType = resolved != null ? resolved : ChatMessageType.GAMEMESSAGE;
        final String msg = message;
        try {
            clientThread.invokeLater(() -> {
                try { client.addChatMessage(chatType, "", msg, null); } catch(Exception ignored){}
            });
        } catch(Exception ignored){}
        return true;
    }
}
