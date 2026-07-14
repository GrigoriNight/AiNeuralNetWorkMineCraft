package com.aibot.web;

import com.aibot.command.CommandBrain;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Two-way Discord bridge: DiscordWebhook (separate class) pushes status OUT
 * on a timer; this class polls a Discord channel via a real bot token for
 * new messages and relays anything starting with the command prefix back IN
 * as a real /brain command, posting the response back to the same channel.
 * Built specifically because Claude's sandboxed environment cannot reach
 * this server's IP directly on any port (confirmed 2026-07-14 - see
 * DiscordWebhook's doc comment) but CAN reach discord.com normally, so
 * Discord becomes the full two-way remote-control channel instead of a
 * direct server connection.
 *
 * SECURITY, told to the user when this was set up: anyone who can post in
 * the configured Discord channel can now run /brain commands on the live
 * server. Commands run via a console-equivalent sender (bypasses the normal
 * op-permission-level check, same as any server-console command already
 * does) - deliberately scoped to ONLY /brain commands by calling
 * CommandBrain.processCommand directly rather than going through the
 * generic multi-mod command dispatcher, so this can never be used to run
 * unrelated vanilla/other-mod commands even if the channel or token were
 * ever compromised. The channel's membership is effectively the same trust
 * boundary as server op access - keep it private.
 */
public class DiscordBotBridge {

    private static final int POLL_INTERVAL_TICKS = 20 * 15; // 15 seconds
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final String COMMAND_PREFIX = "!";
    private static final String API_BASE = "https://discord.com/api/v10";

    private static boolean enabled = false;
    private static String botToken = null;
    private static String channelId = null;
    private static String lastMessageId = null;

    private int tickCounter = 0;
    private volatile boolean pollInFlight = false;

    public static boolean isEnabled() {
        return enabled && botToken != null && channelId != null;
    }

    public static void configure(String token, String channel) {
        botToken = token;
        channelId = channel;
        enabled = true;
        save();
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!isEnabled() || pollInFlight) return;

        tickCounter++;
        if (tickCounter < POLL_INTERVAL_TICKS) return;
        tickCounter = 0;

        pollInFlight = true;
        final String token = botToken;
        final String channel = channelId;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    pollOnce(token, channel);
                } catch (Exception ignored) {
                    // A bad/expired token or a transient Discord outage shouldn't
                    // spam ErrorLog every 15 seconds - same reasoning as DiscordWebhook.
                } finally {
                    pollInFlight = false;
                }
            }
        });
        thread.setDaemon(true);
        thread.setName("DiscordBotBridge-poll");
        thread.start();
    }

    private void pollOnce(String token, String channel) throws IOException {
        String urlStr = API_BASE + "/channels/" + channel + "/messages?limit=20";
        if (lastMessageId != null) {
            urlStr += "&after=" + lastMessageId;
        }

        URL endpoint = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bot " + token);
        conn.setRequestProperty("User-Agent", "aibot-discord-bridge (https://github.com/GrigoriNight/AiNeuralNetWorkMineCraft, 1.0)");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            return;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        conn.disconnect();

        List<DiscordMessage> messages = parseMessages(sb.toString());
        if (messages.isEmpty()) return;

        // Discord returns newest-first; advance the watermark to the newest
        // message regardless of whether it was a command, so ordinary chatter
        // in the channel never gets reprocessed either.
        lastMessageId = messages.get(0).id;
        save();

        // Execute oldest-first so multiple queued commands run in typed order.
        for (int i = messages.size() - 1; i >= 0; i--) {
            DiscordMessage msg = messages.get(i);
            if (msg.isBot || msg.content == null) continue;
            if (!msg.content.startsWith(COMMAND_PREFIX)) continue;

            String[] args = msg.content.substring(COMMAND_PREFIX.length()).trim().split("\\s+");
            if (args.length == 0 || args[0].isEmpty()) continue;

            final String[] finalArgs = args;
            MainThreadScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    runBrainCommand(finalArgs);
                }
            });
        }
    }

    /** Runs on the main server thread (scheduled via MainThreadScheduler) - the only place BotPlayer/world state is safe to touch. */
    private void runBrainCommand(String[] args) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        ResponseCapturingSender sender = new ResponseCapturingSender(server);
        try {
            new CommandBrain().processCommand(sender, args);
        } catch (Exception e) {
            sender.addChatMessage(new net.minecraft.util.ChatComponentText("Error running command: " + e.getMessage()));
            ErrorLog.record("DiscordBotBridge.runBrainCommand", e);
        }

        String reply = sender.getCapturedText();
        DiscordWebhook.send("**!" + join(args) + "**\n" + (reply.isEmpty() ? "(no response)" : reply));
    }

    private String join(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /**
     * Delegates everything to the real server (console-equivalent identity/
     * permissions/world) except addChatMessage, which is captured into a
     * buffer instead of only going to the server log - this is what lets a
     * relayed command's actual response (e.g. /brain status's output) get
     * posted back to Discord instead of disappearing into the console.
     */
    private static class ResponseCapturingSender implements ICommandSender {
        private final MinecraftServer server;
        private final StringBuilder captured = new StringBuilder();

        ResponseCapturingSender(MinecraftServer server) {
            this.server = server;
        }

        String getCapturedText() {
            return captured.toString();
        }

        @Override
        public String getCommandSenderName() {
            return server.getCommandSenderName();
        }

        @Override
        public IChatComponent func_145748_c_() {
            return server.func_145748_c_();
        }

        @Override
        public void addChatMessage(IChatComponent component) {
            if (captured.length() > 0) captured.append('\n');
            captured.append(component.getUnformattedText());
        }

        @Override
        public boolean canCommandSenderUseCommand(int permLevel, String commandName) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return server.getPlayerCoordinates();
        }

        @Override
        public World getEntityWorld() {
            return server.getEntityWorld();
        }
    }

    private static class DiscordMessage {
        String id;
        String content;
        boolean isBot;
    }

    /**
     * Hand-rolled JSON extraction (no library, same standing project
     * convention as ChatAI's Ollama parsing) - splits the top-level message
     * objects out of Discord's "[{...},{...}]" array by tracking brace depth
     * (ignoring braces inside quoted strings), then pulls id/content/
     * author.bot out of each object's substring directly rather than a real
     * nested parse.
     */
    private List<DiscordMessage> parseMessages(String json) {
        List<DiscordMessage> result = new ArrayList<DiscordMessage>();
        int depth = 0;
        int objStart = -1;
        boolean inString = false;
        boolean escape = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\') {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    String obj = json.substring(objStart, i + 1);
                    DiscordMessage msg = new DiscordMessage();
                    msg.id = extractStringField(obj, "\"id\":\"");
                    msg.content = extractStringField(obj, "\"content\":\"");
                    // "author":{...,"bot":true,...} - the whole author object is
                    // nested inside this same substring, so a plain search for
                    // "bot":true within it is safe (nothing else in a Discord
                    // message object uses that exact key).
                    msg.isBot = obj.contains("\"bot\":true");
                    if (msg.id != null) {
                        result.add(msg);
                    }
                    objStart = -1;
                }
            }
        }
        return result;
    }

    private String extractStringField(String json, String marker) {
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();

        StringBuilder out = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') break;
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                if (next == 'n') {
                    out.append('\n');
                    i++;
                } else if (next == '"' || next == '\\') {
                    out.append(next);
                    i++;
                } else {
                    out.append(c);
                }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static void save() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "discordbot.dat"))));
            out.writeBoolean(enabled);
            out.writeUTF(botToken == null ? "" : botToken);
            out.writeUTF(channelId == null ? "" : channelId);
            out.writeUTF(lastMessageId == null ? "" : lastMessageId);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static void load() {
        File file = new File(getSaveDir(), "discordbot.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            enabled = in.readBoolean();
            String token = in.readUTF();
            botToken = token.isEmpty() ? null : token;
            String channel = in.readUTF();
            channelId = channel.isEmpty() ? null : channel;
            String lastId = in.readUTF();
            lastMessageId = lastId.isEmpty() ? null : lastId;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static File getSaveDir() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        File dir = server != null ? server.getFile("aibot") : new File("aibot");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
