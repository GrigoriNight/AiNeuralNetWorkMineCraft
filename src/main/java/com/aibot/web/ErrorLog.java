package com.aibot.web;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * Small ring buffer of recent errors from aibot's own per-tick code, so they
 * can be pulled over the API instead of needing a pasted server log every time
 * something breaks.
 */
public class ErrorLog {

    private static final int MAX_ENTRIES = 50;
    private static final LinkedList<Entry> entries = new LinkedList<Entry>();
    /** Monotonic count of every error ever recorded (unlike entries, never shrinks) - lets a consumer like DiscordStatusPusher tell how many are new since it last checked, even after the ring buffer has evicted some. */
    private static long totalRecorded = 0;

    public static synchronized void record(String context, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));

        Entry entry = new Entry();
        entry.timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        entry.context = context;
        entry.message = String.valueOf(t.getMessage());
        entry.stackTrace = sw.toString();

        entries.addLast(entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.removeFirst();
        }
        totalRecorded++;

        t.printStackTrace();
    }

    public static synchronized List<Entry> getRecent() {
        return new LinkedList<Entry>(entries);
    }

    public static synchronized long getTotalRecorded() {
        return totalRecorded;
    }

    public static class Entry {
        public String timestamp;
        public String context;
        public String message;
        public String stackTrace;
    }
}
