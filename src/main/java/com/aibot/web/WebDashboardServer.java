package com.aibot.web;

import com.aibot.brain.ActionType;
import com.aibot.brain.BrainManager;
import com.aibot.brain.StateEncoder;
import com.aibot.brain.TrainingSample;
import com.aibot.command.BrainActions;
import com.aibot.fakeplayer.BotPlayer;
import com.aibot.fakeplayer.BotPlayerManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.List;
import java.util.UUID;

/**
 * A small built-in dashboard: shows the bot's live status/inventory/training
 * stats and lets you tell it to drop its items, all from a browser. Bound to
 * all interfaces so it works whether Minecraft runs locally or on a remote
 * host, gated behind a random token printed to the server console at startup
 * since there's no login system here otherwise.
 *
 * Separately, /api/v1/* is a persistent-key API meant for external tools (or
 * an assistant helping debug the mod) rather than a browser: pull live status,
 * pull/push training samples, trigger the same actions /brain commands do, and
 * read recent errors without needing a pasted server log.
 */
public class WebDashboardServer {

    private static final int PORT = 25581;
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int DEFAULT_SAMPLE_LIMIT = 500;
    private static final int MAX_SAMPLE_LIMIT = 5000;

    private static WebDashboardServer running;

    private String accessToken;
    private String apiKey;
    private byte[] pageBytes;
    private HttpServer server;

    public static WebDashboardServer getRunning() {
        return running;
    }

    public static int getPort() {
        return PORT;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void start() {
        accessToken = UUID.randomUUID().toString().replace("-", "");
        apiKey = loadOrCreateApiKey();
        pageBytes = loadPage();

        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.createContext("/", new PageHandler());
            server.createContext("/api/status", new StatusHandler());
            server.createContext("/api/dropall", new DropAllHandler());
            server.createContext("/api/v1/status", new ApiStatusHandler());
            server.createContext("/api/v1/samples", new ApiSamplesHandler());
            server.createContext("/api/v1/command", new ApiCommandHandler());
            server.createContext("/api/v1/errors", new ApiErrorsHandler());
            server.createContext("/api/v1/chat", new ApiChatHandler());
            server.setExecutor(null);
            server.start();
            running = this;
            System.out.println("[aibot] Web dashboard: http://<this-server-host>:" + PORT + "/?token=" + accessToken
                    + " (or run /brain weburl in-game)");
            System.out.println("[aibot] API key (persists across restarts, run /brain apikey in-game): " + apiKey);
        } catch (IOException e) {
            System.out.println("[aibot] Could not start web dashboard on port " + PORT + ": " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (running == this) {
            running = null;
        }
    }

    private File getDataDir() {
        MinecraftServer mcServer = FMLCommonHandler.instance().getMinecraftServerInstance();
        File dir = mcServer != null ? mcServer.getFile("aibot") : new File("aibot");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** Unlike the browser token (regenerated every restart), the API key is written to disk once and reused. */
    private String loadOrCreateApiKey() {
        File keyFile = new File(getDataDir(), "apikey.txt");

        if (keyFile.exists()) {
            FileInputStream in = null;
            try {
                in = new FileInputStream(keyFile);
                byte[] buf = new byte[512];
                int n = in.read(buf);
                if (n > 0) {
                    String key = new String(buf, 0, n, UTF8).trim();
                    if (!key.isEmpty()) {
                        return key;
                    }
                }
            } catch (IOException ignored) {
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }

        String key = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(keyFile);
            out.write(key.getBytes(UTF8));
        } catch (IOException e) {
            System.out.println("[aibot] Could not persist API key: " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
        return key;
    }

    private boolean checkToken(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        return query != null && query.contains("token=" + accessToken);
    }

    private boolean checkApiKey(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Api-Key");
        if (header != null && header.equals(apiKey)) {
            return true;
        }
        String query = exchange.getRequestURI().getQuery();
        return query != null && query.contains("apikey=" + apiKey);
    }

    private byte[] loadPage() {
        InputStream in = null;
        try {
            in = WebDashboardServer.class.getClassLoader().getResourceAsStream("assets/aibot/dashboard.html");
            if (in == null) {
                return "<html><body>dashboard.html missing from jar</body></html>".getBytes(UTF8);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            return ("Error loading dashboard: " + e.getMessage()).getBytes(UTF8);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        OutputStream os = exchange.getResponseBody();
        os.write(body);
        os.close();
    }

    private static void respondJson(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json", json.getBytes(UTF8));
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        InputStream in = exchange.getRequestBody();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static String firstQueryParam(HttpExchange exchange, String key) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null) return null;
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (pair.substring(0, eq).equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static int parseIntParam(HttpExchange exchange, String key, int defaultValue) {
        String v = firstQueryParam(exchange, key);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String buildStatusJson() {
        BotPlayer bot = BotPlayerManager.getActive();
        StringBuilder json = new StringBuilder();
        json.append('{');
        json.append("\"name\":\"").append(escapeJson(BotPlayerManager.getBotName())).append("\",");
        json.append("\"active\":").append(bot != null).append(',');

        if (bot != null) {
            json.append("\"x\":").append(bot.posX).append(',');
            json.append("\"y\":").append(bot.posY).append(',');
            json.append("\"z\":").append(bot.posZ).append(',');
            json.append("\"health\":").append(bot.getHealth()).append(',');
            json.append("\"maxHealth\":").append(bot.getMaxHealth()).append(',');
            json.append("\"sleeping\":").append(bot.isPlayerSleeping()).append(',');
            json.append("\"action\":\"").append(bot.currentAction.name()).append("\",");

            json.append("\"items\":[");
            boolean first = true;
            ItemStack[] main = bot.inventory.mainInventory;
            for (int i = 0; i < main.length; i++) {
                ItemStack stack = main[i];
                if (stack == null) continue;
                if (!first) json.append(',');
                first = false;
                json.append("{\"name\":\"").append(escapeJson(stack.getDisplayName())).append("\",\"count\":").append(stack.stackSize).append('}');
            }
            json.append("],");
        }

        json.append("\"learningRate\":").append(BrainManager.getLearningRate()).append(',');
        json.append("\"samples\":").append(BrainManager.instance.getDataset().size()).append(',');
        json.append("\"selfSamples\":").append(BrainManager.instance.getDataset().selfGeneratedSize()).append(',');
        json.append("\"trainingSteps\":").append(BrainManager.instance.getTotalTrainingSteps()).append(',');
        json.append("\"lastAverageLoss\":").append(BrainManager.instance.getLastAverageLoss());
        json.append('}');
        return json.toString();
    }

    private class PageHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkToken(exchange)) {
                respond(exchange, 403, "text/plain; charset=utf-8", "Missing or bad token".getBytes(UTF8));
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", pageBytes);
        }
    }

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkToken(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad token\"}");
                return;
            }
            respondJson(exchange, 200, buildStatusJson());
        }
    }

    private class DropAllHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkToken(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad token\"}");
                return;
            }
            MainThreadScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    BotPlayer bot = BotPlayerManager.getActive();
                    if (bot != null) {
                        bot.inventory.dropAllItems();
                    }
                }
            });
            respondJson(exchange, 200, "{\"ok\":true}");
        }
    }

    private class ApiStatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkApiKey(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad api key\"}");
                return;
            }
            respondJson(exchange, 200, buildStatusJson());
        }
    }

    private class ApiSamplesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkApiKey(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad api key\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                handleExport(exchange);
            } else if ("POST".equalsIgnoreCase(method)) {
                handlePush(exchange);
            } else {
                respondJson(exchange, 405, "{\"error\":\"use GET to export or POST to push\"}");
            }
        }

        private void handleExport(HttpExchange exchange) throws IOException {
            int offset = Math.max(0, parseIntParam(exchange, "offset", 0));
            int limit = parseIntParam(exchange, "limit", DEFAULT_SAMPLE_LIMIT);
            limit = Math.max(0, Math.min(limit, MAX_SAMPLE_LIMIT));

            List<TrainingSample> samples = BrainManager.instance.getDataset().getSamples(offset, limit);

            JsonObject root = new JsonObject();
            root.addProperty("total", BrainManager.instance.getDataset().size());
            root.addProperty("offset", offset);
            root.addProperty("count", samples.size());

            JsonArray arr = new JsonArray();
            for (TrainingSample s : samples) {
                JsonObject obj = new JsonObject();
                JsonArray stateArr = new JsonArray();
                for (double v : s.state) {
                    stateArr.add(new JsonPrimitive(v));
                }
                obj.add("state", stateArr);
                obj.addProperty("action", ActionType.VALUES[s.actionIndex].name());
                obj.addProperty("selfGenerated", s.selfGenerated);
                arr.add(obj);
            }
            root.add("samples", arr);

            respondJson(exchange, 200, root.toString());
        }

        private void handlePush(HttpExchange exchange) throws IOException {
            String body = readBody(exchange);
            JsonObject obj;
            try {
                obj = new JsonParser().parse(body).getAsJsonObject();
            } catch (Exception e) {
                respondJson(exchange, 400, "{\"error\":\"invalid json body\"}");
                return;
            }

            if (!obj.has("state") || !obj.has("action")) {
                respondJson(exchange, 400, "{\"error\":\"expected fields: state (array of " + StateEncoder.STATE_SIZE + " numbers), action (name, e.g. MOVE_FORWARD)\"}");
                return;
            }

            double[] state;
            try {
                JsonArray stateArr = obj.get("state").getAsJsonArray();
                state = new double[stateArr.size()];
                for (int i = 0; i < state.length; i++) {
                    state[i] = stateArr.get(i).getAsDouble();
                }
            } catch (Exception e) {
                respondJson(exchange, 400, "{\"error\":\"state must be a JSON array of numbers\"}");
                return;
            }

            ActionType action;
            try {
                action = ActionType.valueOf(obj.get("action").getAsString().toUpperCase());
            } catch (Exception e) {
                respondJson(exchange, 400, "{\"error\":\"unknown action name\"}");
                return;
            }

            boolean added = BrainManager.instance.getDataset().add(new TrainingSample(state, action.ordinal(), false));
            if (added) {
                respondJson(exchange, 200, "{\"ok\":true}");
            } else {
                respondJson(exchange, 400, "{\"error\":\"rejected - state must have exactly " + StateEncoder.STATE_SIZE + " numbers\"}");
            }
        }
    }

    private class ApiCommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkApiKey(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad api key\"}");
                return;
            }
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondJson(exchange, 405, "{\"error\":\"use POST\"}");
                return;
            }

            final String action = firstQueryParam(exchange, "action");
            final String nameArg = firstQueryParam(exchange, "name");
            final String messageArg = firstQueryParam(exchange, "message");
            if (action == null) {
                respondJson(exchange, 400, "{\"error\":\"missing action param - one of: spawn, spawnplayer, despawnplayer, rename, sleep, say, save, load\"}");
                return;
            }

            MainThreadScheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    runCommand(action, nameArg, messageArg);
                }
            });

            respondJson(exchange, 200, "{\"ok\":true,\"queued\":\"" + escapeJson(action) + "\"}");
        }

        private void runCommand(String action, String nameArg, String messageArg) {
            try {
                MinecraftServer mcServer = FMLCommonHandler.instance().getMinecraftServerInstance();
                if (mcServer == null) return;
                WorldServer world = mcServer.worldServerForDimension(0);

                if ("spawn".equalsIgnoreCase(action)) {
                    if (world != null) BrainActions.spawnZombie(world);
                } else if ("spawnplayer".equalsIgnoreCase(action)) {
                    if (world != null && BotPlayerManager.getActive() == null) BotPlayerManager.spawn(world);
                } else if ("despawnplayer".equalsIgnoreCase(action)) {
                    BotPlayer bot = BotPlayerManager.getActive();
                    if (bot != null) BotPlayerManager.despawn(bot);
                } else if ("rename".equalsIgnoreCase(action) && nameArg != null) {
                    BotPlayerManager.rename(nameArg);
                } else if ("sleep".equalsIgnoreCase(action)) {
                    BotPlayer bot = BotPlayerManager.getActive();
                    if (bot != null) BotPlayerManager.trySleep(bot);
                } else if ("say".equalsIgnoreCase(action) && messageArg != null) {
                    BotPlayerManager.say(messageArg);
                } else if ("save".equalsIgnoreCase(action)) {
                    BrainManager.instance.save();
                } else if ("load".equalsIgnoreCase(action)) {
                    BrainManager.instance.load();
                }
            } catch (Exception e) {
                ErrorLog.record("WebDashboardServer.ApiCommandHandler(" + action + ")", e);
            }
        }
    }

    private class ApiChatHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkApiKey(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad api key\"}");
                return;
            }
            JsonArray arr = new JsonArray();
            for (ChatLog.Entry entry : ChatLog.getRecent()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("timestamp", entry.timestamp);
                obj.addProperty("username", entry.username);
                obj.addProperty("message", entry.message);
                arr.add(obj);
            }
            respondJson(exchange, 200, arr.toString());
        }
    }

    private class ApiErrorsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!checkApiKey(exchange)) {
                respondJson(exchange, 403, "{\"error\":\"bad api key\"}");
                return;
            }
            JsonArray arr = new JsonArray();
            for (ErrorLog.Entry entry : ErrorLog.getRecent()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("timestamp", entry.timestamp);
                obj.addProperty("context", entry.context);
                obj.addProperty("message", entry.message);
                obj.addProperty("stackTrace", entry.stackTrace);
                arr.add(obj);
            }
            respondJson(exchange, 200, arr.toString());
        }
    }
}
