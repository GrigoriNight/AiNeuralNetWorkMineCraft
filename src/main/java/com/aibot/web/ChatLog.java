package com.aibot.web;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.server.MinecraftServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Ring buffer of recent real-player chat messages, so an external tool (or an
 * assistant helping run the bot) can read what was said and reply through the
 * bot via /api/v1/command?action=say, without needing to be connected as a
 * real client itself. Mirrors ErrorLog's structure/pattern.
 *
 * Persisted to <server_root>/aibot/chatlog.dat (save() on server shutdown and
 * every hour in between via MainThreadScheduler - see AIBotMod for the
 * shutdown call, MainThreadScheduler for the hourly one - load() on startup)
 * so chat history survives a restart instead of resetting to empty every
 * time, same as brain.dat/samples.dat/inventory.dat, and a crash never loses
 * more than an hour of it.
 */
public class ChatLog {

    private static final int MAX_ENTRIES = 100;
    private static final LinkedList<Entry> entries = new LinkedList<Entry>();

    public static synchronized void record(String username, String message) {
        Entry entry = new Entry();
        entry.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        entry.username = username;
        entry.message = message;

        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
    }

    public static synchronized List<Entry> getRecent() {
        return new LinkedList<Entry>(entries);
    }

    private static File getSaveFile() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        File dir = server != null ? server.getFile("aibot") : new File("aibot");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return new File(dir, "chatlog.dat");
    }

    public static synchronized void save() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(getSaveFile())));
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                out.writeUTF(entry.timestamp);
                out.writeUTF(entry.username);
                out.writeUTF(entry.message);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeQuietly(out);
        }
    }

    public static synchronized void load() {
        File file = getSaveFile();
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            int count = in.readInt();
            entries.clear();
            for (int i = 0; i < count; i++) {
                Entry entry = new Entry();
                entry.timestamp = in.readUTF();
                entry.username = in.readUTF();
                entry.message = in.readUTF();
                entries.addLast(entry);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static class Entry {
        public String timestamp;
        public String username;
        public String message;
    }
}
