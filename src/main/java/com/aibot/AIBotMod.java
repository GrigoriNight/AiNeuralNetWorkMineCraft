package com.aibot;

import com.aibot.brain.BrainManager;
import com.aibot.command.CommandBrain;
import com.aibot.events.PlayerActionRecorder;
import com.aibot.fakeplayer.BotPlayer;
import com.aibot.fakeplayer.BotPlayerAI;
import com.aibot.fakeplayer.BotPlayerAutoSpawner;
import com.aibot.fakeplayer.BotPlayerManager;
import com.aibot.fakeplayer.TrainingBotAI;
import com.aibot.web.ChatAI;
import com.aibot.web.ChatLog;
import com.aibot.web.MainThreadScheduler;
import com.aibot.web.WebDashboardServer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.common.MinecraftForge;

/**
 * No custom entity type, no network packets, no client-only registration - so
 * this mod is never required on the client. Players can join a server running
 * it without installing anything themselves.
 */
@Mod(modid = AIBotMod.MODID, name = AIBotMod.NAME, version = AIBotMod.VERSION, acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public class AIBotMod {

    // Intentionally left as "aibot", not renamed to match the project's new
    // display name - the save-data folder (samples.dat, goals.dat, base
    // progress, etc.) is keyed off this exact string on the live server, and
    // changing it would make all of that existing data invisible to a rebuilt
    // jar rather than actually deleting it. Cosmetic renaming happens via NAME
    // below instead, which is safe to change freely.
    public static final String MODID = "aibot";
    public static final String NAME = "AiNeuralNetWorkMineCraft";
    public static final String VERSION = "1.4.0";

    @Mod.Instance(MODID)
    public static AIBotMod instance;

    private final WebDashboardServer webDashboard = new WebDashboardServer();

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(new PlayerActionRecorder());
        MinecraftForge.EVENT_BUS.register(new BotPlayerAI());
        FMLCommonHandler.instance().bus().register(new BotPlayerAI());
        FMLCommonHandler.instance().bus().register(BrainManager.instance);
        FMLCommonHandler.instance().bus().register(new BotPlayerAutoSpawner());
        MinecraftForge.EVENT_BUS.register(new TrainingBotAI());
        FMLCommonHandler.instance().bus().register(new TrainingBotAI());
        FMLCommonHandler.instance().bus().register(new MainThreadScheduler());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandBrain());
        BrainManager.instance.onServerStarting();
        ChatLog.load();
        ChatAI.load();
        BotPlayerManager.loadBaseProgress();
        BotPlayerManager.loadGoals();
        BotPlayerManager.loadKnownPlayerBases();
        webDashboard.start();
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        BrainManager.instance.save();
        BotPlayer active = BotPlayerManager.getActive();
        if (active != null) {
            BotPlayerManager.saveInventory(active);
        }
        ChatLog.save();
        BotPlayerManager.saveBaseProgress();
        BotPlayerManager.saveGoals();
        webDashboard.stop();
    }
}
