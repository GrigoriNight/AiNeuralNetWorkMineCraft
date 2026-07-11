package com.aibot.web;

import com.aibot.fakeplayer.BotPlayer;
import com.aibot.fakeplayer.BotPlayerManager;
import com.aibot.fakeplayer.GoalType;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional conversational chat replies via an Ollama instance - originally
 * pointed straight at localhost since Ollama and the Minecraft server run on
 * the same PC (confirmed), but the endpoint is now a configurable URL
 * (/brain chat url) so it can instead go through a Cloudflare tunnel
 * (chat.grigorinightdragon.com) if that's reachable/preferred - same request/
 * response shape either way, Cloudflare's just proxying the same local Ollama
 * on the other end. No cloud API key, no per-message cost either way, per
 * explicit user preference over a paid cloud API. Disabled by default
 * (/brain chat to toggle) since it's new and untested on a live server. Any
 * failure to reach the endpoint (not running, tunnel down, wrong URL) is
 * swallowed silently rather than erroring into chat - per explicit "if it
 * can't connect make it not talk".
 */
public class ChatAI {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 30000;
    /** Floor between AI replies - a model call can take real seconds to respond (more so through a tunnel), and this keeps chat from getting flooded if the bot's name comes up a lot in normal conversation. */
    private static final int REPLY_COOLDOWN_MS = 5000;
    /** A player standing this close to the bot triggers a reply to whatever they say, even without naming the bot - like a real player nearby overhearing/joining the conversation. Roughly "same room" distance, tighter than the mob/wool/food seek radii elsewhere in the mod since this is about who's close enough to plausibly be talking to the bot, not who it can spot from afar. */
    private static final double PROXIMITY_REPLY_RADIUS = 20.0;

    private static boolean enabled = false;
    // Matches what's actually pulled in the user's local Ollama (confirmed via
    // `ollama list`) - "llama3.2" was a placeholder that doesn't exist there.
    private static String model = "llama3.1:8b";
    // Set to the user's Cloudflare-tunneled Ollama endpoint per explicit request
    // ("here the url chat.grigorinightdragon.com") - still just /brain chat url
    // away from being pointed back at localhost or anywhere else.
    private static String url = normalizeUrl("chat.grigorinightdragon.com");
    private static long lastReplyAtMs = 0L;

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ChatAI() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public static String getModel() {
        return model;
    }

    public static void setModel(String value) {
        model = value;
        save();
    }

    public static String getUrl() {
        return url;
    }

    public static void setUrl(String value) {
        url = normalizeUrl(value);
        save();
    }

    /**
     * Persists enabled/model/url across restarts - confirmed live (three
     * separate times) that resetting to disabled/default-model on every
     * restart was a real, recurring source of confusion ("i dont know y he
     * wont talk to me" turned out to just be this resetting again). Same
     * "save on every mutation, load once at server start" pattern as
     * BotPlayerManager's other persisted intents (miningModeIntent, goals).
     */
    private static void save() {
        File dir = getSaveDir();
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(dir, "chatai.dat"))));
            out.writeBoolean(enabled);
            out.writeUTF(model);
            out.writeUTF(url);
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
        File file = new File(getSaveDir(), "chatai.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            enabled = in.readBoolean();
            model = in.readUTF();
            url = in.readUTF();
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

    /**
     * Accepts a bare host ("chat.example.com"), a host with scheme
     * ("https://chat.example.com"), or a full URL with path already included -
     * fills in "https://" and Ollama's "/api/chat" path whenever they're missing,
     * so /brain chat url only requires typing the part that actually varies.
     */
    private static String normalizeUrl(String input) {
        String value = input.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }
        if (!value.contains("/api/")) {
            if (value.endsWith("/")) {
                value = value.substring(0, value.length() - 1);
            }
            value = value + "/api/chat";
        }
        return value;
    }

    /**
     * Called for every real player chat message (see PlayerActionRecorder.onChat) -
     * no-ops immediately unless enabled and either the bot's name was mentioned
     * (from anywhere) or the sender is standing near the bot right now (per
     * explicit "if someone near him make him talk back" request), so ordinary
     * chat across the rest of the server never touches the network call or
     * costs any latency. The actual HTTP call runs off the server thread (a
     * local model can take real seconds to respond, and the server tick loop
     * can't stall for that) - the reply is handed back to BotPlayerManager.say()
     * via MainThreadScheduler once it's ready, same pattern the web dashboard
     * uses for its own requests.
     */
    public static void maybeReplyTo(EntityPlayerMP sender, String playerName, String message) {
        if (!enabled || message == null) return;

        boolean mentioned = message.toLowerCase().indexOf(BotPlayerManager.getBotName().toLowerCase()) >= 0;
        if (!mentioned && !isNearBot(sender)) return;

        long now = System.currentTimeMillis();
        if (now - lastReplyAtMs < REPLY_COOLDOWN_MS) return;
        lastReplyAtMs = now;

        final String finalPlayerName = playerName;
        final String finalMessage = message;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                final AiResult result = requestReply(finalPlayerName, finalMessage);
                if (result == null) return;

                if (result.content != null && !result.content.trim().isEmpty()) {
                    final String reply = result.content.trim();
                    MainThreadScheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            BotPlayerManager.say(reply);
                        }
                    });
                }
                if (result.toolName != null) {
                    MainThreadScheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            executeTool(result, finalPlayerName);
                        }
                    });
                }
            }
        });
    }

    /**
     * Runs on the server thread (scheduled via MainThreadScheduler, same as the
     * chat reply itself) - maps the model's chosen tool onto the exact same
     * bot state the /brain commands already use (follow/unfollow/home/mine/goal),
     * per explicit "let the AI decide in-game actions too" request. Re-resolves
     * nothing by entity reference across the async gap - only the player's name
     * is carried over, matching this mod's existing "re-resolve by name every
     * tick" pattern (see BotPlayerAI.resolvePlayer) rather than risking a stale
     * EntityPlayerMP if they logged out while Ollama was thinking.
     */
    private static void executeTool(AiResult result, String playerName) {
        BotPlayer bot = BotPlayerManager.getActive();
        if (bot == null) return;

        if ("follow_player".equals(result.toolName)) {
            bot.followPlayerName = playerName;
            bot.copyPlayerName = null;
        } else if ("stop_following".equals(result.toolName)) {
            bot.followPlayerName = null;
        } else if ("go_home".equals(result.toolName)) {
            if (bot.dimension == BotPlayerManager.HOME_DIMENSION) {
                bot.forcedGoingHome = true;
            }
        } else if ("start_mining".equals(result.toolName)) {
            BotPlayerManager.setMiningModeIntent(true);
            bot.miningMode = true;
        } else if ("stop_mining".equals(result.toolName)) {
            BotPlayerManager.setMiningModeIntent(false);
            bot.miningMode = false;
        } else if ("gather_resource".equals(result.toolName)) {
            GoalType type = GoalType.fromCommand(result.toolResource);
            if (type != null && result.toolAmount > 0) {
                BotPlayerManager.addGoal(type, result.toolAmount);
            }
        }
    }

    private static boolean isNearBot(EntityPlayerMP sender) {
        if (sender == null) return false;
        BotPlayer bot = BotPlayerManager.getActive();
        if (bot == null || bot.worldObj != sender.worldObj) return false;

        double dx = sender.posX - bot.posX;
        double dy = sender.posY - bot.posY;
        double dz = sender.posZ - bot.posZ;
        return dx * dx + dy * dy + dz * dz <= PROXIMITY_REPLY_RADIUS * PROXIMITY_REPLY_RADIUS;
    }

    /**
     * Grounds the model in the actual modpack instead of generic vanilla
     * Minecraft (or worse, real-world) knowledge - confirmed live as a real
     * problem: asked for a "fun fact" it invented a false claim about vanilla
     * Minecraft's name origin, and separately, a "go home" reply included the
     * nonsensical real-world line "on my way to the store". Curated by hand
     * from this server's actual mod list (modlist.json used for the forge
     * handshake) down to the recognizable tech/magic mods, rather than dumping
     * all ~150 modids (mostly compat/library submodules) as noise.
     */
    private static final String MODPACK_PROFILE = "You're playing on a heavily modded Minecraft 1.7.10 server (not "
            + "vanilla) with mods including Applied Energistics 2, Thermal Expansion, Railcraft, BuildCraft, "
            + "IndustrialCraft 2, Forestry, Tinkers' Construct, EnderIO, Thaumcraft, Botania, Twilight Forest, "
            + "Mystcraft, Witchery, PneumaticCraft, Big Reactors, RFTools, ComputerCraft, Extra Utilities, and "
            + "Iron Chest, among many others - so ores, machines, magic, and dimensions well beyond vanilla are "
            + "all normal and expected here. Never reference real-world things (stores, cars, phones, etc.) or "
            + "make up trivia about Minecraft's real-world development - stay entirely in-universe.";

    /** Small, fixed set of things the model can actually make the bot do - each maps 1:1 onto an existing /brain command's underlying state change (see executeTool), nothing new or riskier than what an admin could already type. */
    private static final String TOOLS_JSON =
            "["
                    + "{\"type\":\"function\",\"function\":{\"name\":\"follow_player\",\"description\":\"Walk over to the player and follow them around. Use this when they ask you to come here, follow them, or stay close.\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"stop_following\",\"description\":\"Stop following the player and go back to doing your own thing.\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"go_home\",\"description\":\"Walk back to your home base.\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"start_mining\",\"description\":\"Start actively seeking out and mining ore.\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"stop_mining\",\"description\":\"Stop actively mining ore.\",\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}}},"
                    + "{\"type\":\"function\",\"function\":{\"name\":\"gather_resource\",\"description\":\"Go gather a specific amount of a resource and add it to your to-do list. Use this when asked to get/fetch/bring some wood, stone, ore, wool, or food.\",\"parameters\":{\"type\":\"object\",\"properties\":{\"resource\":{\"type\":\"string\",\"enum\":[\"wood\",\"stone\",\"ore\",\"wool\",\"food\"]},\"amount\":{\"type\":\"integer\"}},\"required\":[\"resource\",\"amount\"]}}}"
                    + "]";

    private static AiResult requestReply(String playerName, String message) {
        try {
            // Grounds the model in what the bot is actually doing right now, per
            // explicit "if he's saying he's doing something, make sure he's
            // actually doing it" request - without this, a "what are you up to?"
            // question had nothing real to answer from and the model would just
            // invent a plausible-sounding activity, which could easily contradict
            // reality (claiming to mine while actually idle, etc.).
            BotPlayer bot = BotPlayerManager.getActive();
            String currentActivity = bot != null ? bot.currentBehavior : "not currently online";

            String systemPrompt = "You are " + BotPlayerManager.getBotName() + ", a player on a Minecraft server chatting "
                    + "casually with other players. " + MODPACK_PROFILE + " What you are actually doing right now: \""
                    + currentActivity + "\" - "
                    + "if asked what you're up to or doing, answer truthfully based on this instead of making something "
                    + "up, described casually in your own words rather than reading it out literally. Reply in-character "
                    + "with a short, casual chat message like a real player typing in Minecraft chat - one or two "
                    + "sentences at most, plain text, no markdown, no asterisk actions, and never JSON or curly braces. "
                    + "If they ask you to come over, follow them, go home, mine, or gather something, call the matching "
                    + "tool using the real tool-calling mechanism instead of writing out JSON as your reply text - never "
                    + "type out a fake tool call yourself, only plain conversational text, and never claim you'll do "
                    + "something (like coming over or fetching an item) unless you actually call the matching tool.";
            String requestBody = "{"
                    + "\"model\":\"" + escapeJson(model) + "\","
                    + "\"stream\":false,"
                    + "\"tools\":" + TOOLS_JSON + ","
                    + "\"messages\":["
                    + "{\"role\":\"system\",\"content\":\"" + escapeJson(systemPrompt) + "\"},"
                    + "{\"role\":\"user\",\"content\":\"" + escapeJson(playerName + ": " + message) + "\"}"
                    + "]}";

            URL endpoint = new URL(url);
            HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            // Confirmed live (Cloudflare error 1010, "banned your access based on your
            // browser's signature"): Cloudflare's bot-detection blocks Java's default
            // HttpURLConnection User-Agent ("Java/1.8.0_x") outright when the request
            // goes through a Cloudflare-proxied hostname (localhost calls never hit
            // this, only the tunnel path does) - a normal-looking User-Agent avoids it.
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            OutputStream os = conn.getOutputStream();
            os.write(requestBody.getBytes(Charset.forName("UTF-8")));
            os.close();

            if (conn.getResponseCode() != 200) {
                conn.disconnect();
                return null;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), Charset.forName("UTF-8")));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            conn.disconnect();

            String json = sb.toString();
            AiResult result = new AiResult();
            result.content = sanitizeContent(extractContent(json));
            parseToolCall(json, result);
            return result;
        } catch (IOException e) {
            // Expected whenever Ollama isn't installed/running/listening on the
            // default port - stay silent rather than erroring into chat.
            return null;
        } catch (Exception e) {
            ErrorLog.record("ChatAI.requestReply", e);
            return null;
        }
    }

    private static class AiResult {
        String content;
        String toolName;
        String toolResource;
        int toolAmount;
    }

    /**
     * Minimal hand-rolled extraction of the first tool call, if any, from
     * Ollama's "tool_calls":[{"function":{"name":"...","arguments":{...}}}]
     * array - same "no JSON library in this project" reasoning as
     * extractContent. Deliberately only looks for the handful of keys this
     * mod's fixed tool set can ever produce (name/resource/amount), not a
     * general-purpose JSON parser.
     */
    private static void parseToolCall(String json, AiResult result) {
        int toolCallsIdx = json.indexOf("\"tool_calls\":[");
        if (toolCallsIdx < 0) return;
        int arrayEnd = json.indexOf(']', toolCallsIdx);
        if (arrayEnd < 0) return;
        String toolSection = json.substring(toolCallsIdx, arrayEnd);
        if (toolSection.indexOf("\"name\"") < 0) return;

        result.toolName = extractStringField(toolSection, "\"name\":\"");
        result.toolResource = extractStringField(toolSection, "\"resource\":\"");

        String amountMarker = "\"amount\":";
        int amountIdx = toolSection.indexOf(amountMarker);
        if (amountIdx >= 0) {
            int start = amountIdx + amountMarker.length();
            // Confirmed live: despite the "integer" type in the tool schema, the
            // model sometimes returns the amount as a quoted string ("15") instead
            // of a raw number (15) - accept both instead of silently dropping it.
            if (start < toolSection.length() && toolSection.charAt(start) == '"') {
                start++;
            }
            int end = start;
            while (end < toolSection.length() && (Character.isDigit(toolSection.charAt(end)) || toolSection.charAt(end) == '-')) {
                end++;
            }
            if (end > start) {
                try {
                    result.toolAmount = Integer.parseInt(toolSection.substring(start, end));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private static String extractStringField(String json, String marker) {
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    /** Minimal hand-rolled extraction of the "content" field from Ollama's /api/chat JSON reply - no JSON library in this project, same "build/parse JSON by hand" pattern WebDashboardServer already uses. */
    private static String extractContent(String json) {
        String marker = "\"content\":\"";
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
                } else if (next == 't') {
                    out.append('\t');
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

    /**
     * Confirmed live: smaller models (qwen2.5:1.5b) sometimes fake a tool call
     * as plain text content instead of using Ollama's real "tool_calls"
     * mechanism - e.g. replying with the literal text
     * {"name":"say","parameters":{"message":"hello back atcha, what's up"}}
     * which isn't even one of this mod's defined tools, so parseToolCall never
     * catches it either. Rather than let raw JSON show up in front of players,
     * salvage the human-readable message from inside it if there is one, or
     * suppress the reply entirely if it looks like JSON but nothing readable
     * can be pulled out.
     */
    private static String sanitizeContent(String content) {
        if (content == null) return null;
        String trimmed = content.trim();
        if (!trimmed.startsWith("{")) return content;

        String salvaged = extractStringField(trimmed, "\"message\":\"");
        if (salvaged == null) {
            salvaged = extractStringField(trimmed, "\"content\":\"");
        }
        return salvaged;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
