package com.aibot.fakeplayer;

import com.aibot.web.ErrorLog;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/**
 * Spawns the brain bot whenever it isn't active, so it's always out training/
 * moving instead of sitting despawned. Previously also auto-despawned it the
 * moment an untrusted real player joined - removed per explicit request
 * ("make so he does not hide... like a true gamer playing"): a real player
 * doesn't log off just because someone else logs on, so the bot shouldn't
 * either. It can still be despawned manually with /brain hide.
 */
public class BotPlayerAutoSpawner {

    private static final int CHECK_INTERVAL_TICKS = 100;

    private int tickCounter = 0;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickCounter++;
        if (tickCounter < CHECK_INTERVAL_TICKS) return;
        tickCounter = 0;

        try {
            tick();
        } catch (Exception e) {
            ErrorLog.record("BotPlayerAutoSpawner.onServerTick", e);
        }
    }

    private void tick() {
        // While a flee-respawn is counting down, skip auto-spawn entirely so its
        // cooldown isn't defeated by immediately spawning the bot back in.
        if (BotPlayerManager.tickPendingRespawn(CHECK_INTERVAL_TICKS)) {
            return;
        }

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        if (BotPlayerManager.getActive() == null && !BotPlayerManager.isHiddenIntent()) {
            WorldServer world = server.worldServerForDimension(0);
            if (world != null) {
                BotPlayerManager.spawn(world);
            }
        }
    }
}
