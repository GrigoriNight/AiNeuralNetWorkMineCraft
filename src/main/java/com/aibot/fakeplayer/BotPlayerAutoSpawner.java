package com.aibot.fakeplayer;

import com.aibot.web.ErrorLog;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.entity.EntityLivingBase;
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
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        // Extra self-play-only training bots (per explicit "make 16 extra bots
        // until a real player joins" request) - independent of the main bot's
        // flee-respawn cooldown below, since they have nothing to do with it.
        // /brain trainingbots can turn off their ambient spawning entirely
        // (isEnabled() false), but even then they're allowed back in
        // temporarily as backup while Direwolf20 is under attack - "off" means
        // "don't keep them around just for training", not "never help".
        if (BotPlayerManager.hasRealPlayerOnline(server)) {
            TrainingBotManager.despawnAll();
        } else if (TrainingBotManager.isEnabled() || isDirewolfUnderAttack()) {
            WorldServer trainingWorld = server.worldServerForDimension(0);
            if (trainingWorld != null) {
                TrainingBotManager.spawnAllIfNeeded(trainingWorld);
            }
        } else {
            TrainingBotManager.despawnAll();
        }

        // While a flee-respawn is counting down, skip auto-spawn entirely so its
        // cooldown isn't defeated by immediately spawning the bot back in.
        if (BotPlayerManager.tickPendingRespawn(CHECK_INTERVAL_TICKS)) {
            return;
        }

        WorldServer world = server.worldServerForDimension(0);

        // Once morning comes, an auto-hide-for-sleep (see
        // PlayerActionRecorder.onPlayerSleep) has done its job - let the bot
        // come back like normal. An explicit /brain hide (hiddenIntent) is
        // never cleared here; only the user reverses that one.
        if (BotPlayerManager.isAutoSleepHidden() && world != null && world.isDaytime()) {
            BotPlayerManager.setAutoSleepHidden(false);
        }

        if (BotPlayerManager.getActive() == null && !BotPlayerManager.isHiddenIntent() && !BotPlayerManager.isAutoSleepHidden()) {
            if (world != null) {
                BotPlayerManager.spawn(world);
            }
        }
    }

    /** Same "who last hit it" check tryRetaliate uses - true while Direwolf20 has a live hostile target, i.e. is actively fighting back. */
    private boolean isDirewolfUnderAttack() {
        BotPlayer active = BotPlayerManager.getActive();
        if (active == null) return false;
        EntityLivingBase target = active.getAITarget();
        return target != null && target.isEntityAlive();
    }
}
