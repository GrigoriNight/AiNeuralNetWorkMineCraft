package com.aibot.web;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * Pushes plain-text status updates to a Discord webhook - built specifically
 * as a workaround after confirming (2026-07-14) that Claude's sandboxed
 * environment cannot make direct outbound connections to this server's IP
 * (raw TCP to every port tried - the web dashboard and the actual game port -
 * timed out, while a real player connected fine in the same window). Rather
 * than Claude connecting IN (blocked), the mod pushes status OUT to a public
 * webhook Claude can fetch/read normally. Same "hand-rolled HTTP off the tick
 * thread" pattern as ChatAI's Ollama calls, reused deliberately rather than
 * inventing a new approach.
 */
public class DiscordWebhook {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    /** Discord caps message content at 2000 chars - stay comfortably under it. */
    private static final int MAX_MESSAGE_LENGTH = 1900;

    private static boolean enabled = false;
    private static String webhookUrl = null;

    private DiscordWebhook() {
    }

    public static boolean isEnabled() {
        return enabled && webhookUrl != null;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        save();
    }

    public static String getUrl() {
        return webhookUrl;
    }

    /** Setting a URL also enables pushing - matches /brain webhook <url> being the one-step "turn it on" command. */
    public static void setUrl(String url) {
        webhookUrl = url;
        enabled = true;
        save();
    }

    /**
     * Fire-and-forget - runs the actual HTTP call on its own daemon thread
     * (never the tick thread) and swallows any failure silently. Unlike
     * ChatAI's requestReply, there's no reply to wait for and nothing to
     * schedule back onto the main thread afterward, so this is simpler: just
     * post and forget.
     */
    public static void send(String content) {
        if (!isEnabled()) return;

        final String targetUrl = webhookUrl;
        final String body = content.length() > MAX_MESSAGE_LENGTH
                ? content.substring(0, MAX_MESSAGE_LENGTH) + "... (truncated)"
                : content;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                postNow(targetUrl, body);
            }
        });
        thread.setDaemon(true);
        thread.setName("DiscordWebhook-post");
        thread.start();
    }

    private static void postNow(String targetUrl, String content) {
        try {
            String jsonBody = "{\"content\":\"" + escapeJson(content) + "\"}";

            URL endpoint = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            conn.setDoOutput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes(Charset.forName("UTF-8")));
            os.close();

            conn.getResponseCode(); // Discord returns 204 on success - just need the request to actually fire
            conn.disconnect();
        } catch (Exception e) {
            // Webhook unreachable/misconfigured - stay silent rather than feeding
            // this into ErrorLog, which would be a strange loop given this class
            // exists specifically to report ErrorLog entries elsewhere.
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public static void save() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "discordwebhook.dat"))));
            out.writeBoolean(enabled);
            out.writeUTF(webhookUrl == null ? "" : webhookUrl);
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
        File file = new File(getSaveDir(), "discordwebhook.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            enabled = in.readBoolean();
            String url = in.readUTF();
            webhookUrl = url.isEmpty() ? null : url;
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
