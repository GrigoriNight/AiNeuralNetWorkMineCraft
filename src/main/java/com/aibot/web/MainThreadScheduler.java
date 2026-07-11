package com.aibot.web;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Minecraft's world/entity state is only safe to touch from the server thread.
 * HTTP requests arrive on their own threads, so anything they need to do to the
 * game gets queued here and drained once per tick on the server thread instead.
 */
public class MainThreadScheduler {

    private static final Queue<Runnable> queue = new ConcurrentLinkedQueue<Runnable>();

    public static void schedule(Runnable task) {
        queue.add(task);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
        }
    }
}
