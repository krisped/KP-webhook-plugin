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

@Singleton
public class CustomMessageCommandHandler {
    private final Client client;
    private final ClientThread clientThread;
    @Inject private KPWebhookPlugin plugin;

    // Short window duplicate suppression (same type|name|msg within 400ms)
    private volatile String lastSig = null;
    private volatile long lastSigTime = 0L;
    private volatile String lastFullMessage = null; // track last full message text
    private volatile long lastFullTime = 0L;
    private static final long DUP_WINDOW_MS = 400;

    @Inject
    public CustomMessageCommandHandler(Client client, ClientThread clientThread) {
        this.client = client;
        this.clientThread = clientThread;
    }

    public boolean handle(String line, KPWebhookPreset rule, Map<String,String> ctx) {
        if(line == null) return false;
        String trim = line.trim();
        if(!trim.regionMatches(true,0,"CUSTOM_MESSAGE",0,"CUSTOM_MESSAGE".length())) return false;
        String body = trim.length() > 14 ? trim.substring(14).trim() : ""; // length of CUSTOM_MESSAGE
        if(body.isEmpty()) return true;

        // Split first token to detect type
        String typeToken;
        String remainder;
        int sp = body.indexOf(' ');
        if(sp > 0){
            typeToken = body.substring(0, sp).trim();
            remainder = body.substring(sp+1).trim();
        } else {
            // Only message text: default type & whole body as message
            typeToken = null;
            remainder = body;
        }
        ChatMessageType chatType = ChatMessageType.GAMEMESSAGE;
        if(typeToken != null){
            ChatMessageType resolved = KPWebhookPlugin.resolveChatType(typeToken);
            if(resolved != null){
                chatType = resolved;
            } else {
                // typeToken actually part of message
                remainder = body; // restore full body
            }
        }
        if(remainder == null || remainder.isEmpty()) return true;

        // Optional explicit name syntax: @Name: message  OR  Name: message
        String name = "";
        String msg = remainder;
        int colonPos = remainder.indexOf(':');
        if(colonPos > 0){
            String possibleName = remainder.substring(0, colonPos).trim();
            String afterColon = remainder.substring(colonPos+1).trim();
            if(!afterColon.isEmpty()){
                // Accept if starts with @ or no spaces (simple name) and length reasonable
                if((possibleName.startsWith("@") && possibleName.length() > 1) || (!possibleName.contains(" ") && possibleName.length()<=20)){
                    name = possibleName.startsWith("@")? possibleName.substring(1): possibleName;
                    msg = afterColon;
                }
            }
        }

        if(msg.isEmpty()) return true;

        final String fName = name;
        final String fMsg = msg;
        final ChatMessageType fType = chatType;
        String signature = fType.ordinal()+"|"+fName+"|"+fMsg;
        long now = System.currentTimeMillis();
        // Suppress exact duplicate within window
        if(signature.equals(lastSig) && (now - lastSigTime) < DUP_WINDOW_MS){
            return true; // suppress duplicate rapid call
        }
        // Suppress truncated variant (message without last word) right after a longer one
        if(lastFullMessage!=null && (now - lastFullTime) < DUP_WINDOW_MS){
            int lp = lastFullMessage.lastIndexOf(' ');
            if(lp>0){
                String lastPrefix = lastFullMessage.substring(0, lp).trim();
                if(fMsg.equals(lastPrefix) && fType.ordinal()==Integer.parseInt(signature.split("\\|",3)[0]) && fName.equals(signature.split("\\|",3)[1])){
                    return true; // skip truncated duplicate
                }
            }
        }
        lastSig = signature; lastSigTime = now; lastFullMessage = fMsg; lastFullTime = now;

        clientThread.invokeLater(() -> {
            try {
                client.addChatMessage(fType, fName, fMsg, null);
                if(plugin!=null) plugin.markInjectedChat(fType, fMsg);
            } catch(Exception ignored){}
        });
        return true;
    }
}
