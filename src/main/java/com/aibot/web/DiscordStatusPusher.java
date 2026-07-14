package com.aibot.web;

import com.aibot.fakeplayer.BotPlayer;
import com.aibot.fakeplayer.BotPlayerManager;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

import java.util.List;

/**
 * Periodically posts Direwolf20's status - and any new ErrorLog entries - to
 * the configured Discord webhook (see DiscordWebhook, /brain webhook). No-ops
 * entirely whenever no webhook is configured, so this costs nothing on a
 * server that never sets one up.
 */
public class DiscordStatusPusher {

    private static final int STATUS_INTERVAL_TICKS = 20 * 60 * 5; // 5 minutes - Discord webhooks rate-limit around 5 req/2s, so this is nowhere close

    private int tickCounter = 0;
    private long lastReportedErrorCount = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!DiscordWebhook.isEnabled()) return;

        tickCounter++;
        if (tickCounter < STATUS_INTERVAL_TICKS) return;
        tickCounter = 0;

        try {
            pushStatus();
        } catch (Exception e) {
            ErrorLog.record("DiscordStatusPusher.onServerTick", e);
        }
    }

    private void pushStatus() {
        StringBuilder sb = new StringBuilder();
        BotPlayer bot = BotPlayerManager.getActive();
        if (bot != null) {
            sb.append(String.format("**%s** - %s | HP %.1f/%.1f | pos %.0f, %.0f, %.0f (dim %d)",
                    BotPlayerManager.getBotName(), bot.currentBehavior, bot.getHealth(), bot.getMaxHealth(),
                    bot.posX, bot.posY, bot.posZ, bot.dimension));
        } else {
            sb.append("**").append(BotPlayerManager.getBotName()).append("** - not currently spawned");
        }

        List<ErrorLog.Entry> recent = ErrorLog.getRecent();
        long totalNow = ErrorLog.getTotalRecorded();
        long newCount = totalNow - lastReportedErrorCount;
        if (newCount > 0) {
            int toShow = (int) Math.min(newCount, recent.size());
            sb.append("\n:warning: ").append(newCount).append(" new error(s) since last report:");
            for (int i = recent.size() - toShow; i < recent.size(); i++) {
                ErrorLog.Entry e = recent.get(i);
                sb.append("\n- [").append(e.timestamp).append("] ").append(e.context).append(": ").append(e.message);
            }
        }
        lastReportedErrorCount = totalNow;

        DiscordWebhook.send(sb.toString());
    }
}
