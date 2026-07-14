package com.aibot.fakeplayer;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Spawns/despawns a pool of extra fake players purely for self-play training
 * data - per explicit "make 16 extra bots until a real player joins" request.
 * Deliberately kept entirely separate from BotPlayerManager's single "active"
 * Direwolf20 bot: these bots are anonymous, have no saved inventory/base/goal
 * state of their own (nothing here is persisted - they're pure ephemeral
 * training fodder, freshly spawned and forgotten every time), and never touch
 * BotPlayerManager.active. Their own tick loop (TrainingBotAI) is intentionally
 * much simpler than BotPlayerAI's full goal/base/chat-aware decision tree.
 */
public class TrainingBotManager {

    public static final int MAX_TRAINING_BOTS = 16;

    private static final List<BotPlayer> trainingBots = new ArrayList<BotPlayer>();

    /**
     * Player-controlled intent (/brain trainingbots), persisted across restarts
     * same as BotPlayerManager's other *Intent flags (miningModeIntent, etc.) -
     * an earlier bug in this project (mining mode silently resetting on restart)
     * came from exactly this kind of flag NOT being saved, so this one is from
     * the start. Even while false, BotPlayerAutoSpawner still lets them spawn
     * back in temporarily if Direwolf20 is under attack (see isDirewolfUnderAttack)
     * - "off" means "don't ambiently spawn for training", not "never help".
     */
    private static boolean enabled = true;

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            despawnAll();
        }
        saveEnabled();
    }

    public static void saveEnabled() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "trainingbots.dat"))));
            out.writeBoolean(enabled);
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

    public static void loadEnabled() {
        File file = new File(getSaveDir(), "trainingbots.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            enabled = in.readBoolean();
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

    public static List<BotPlayer> getTrainingBots() {
        return trainingBots;
    }

    /** Tops the pool up to MAX_TRAINING_BOTS - safe to call repeatedly, only spawns what's missing. */
    public static void spawnAllIfNeeded(WorldServer world) {
        while (trainingBots.size() < MAX_TRAINING_BOTS) {
            spawnOne(world);
        }
    }

    private static void spawnOne(WorldServer world) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        ChunkCoordinates spawnPoint = world.getSpawnPoint();
        String name = "TrainBot" + (trainingBots.size() + 1);
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        BotPlayer bot = new BotPlayer(server, world, profile);
        bot.playerNetServerHandler = new FakeNetHandler(server, bot);
        float yaw = world.rand.nextFloat() * 360.0F;
        bot.setLocationAndAngles(spawnPoint.posX + 0.5, spawnPoint.posY, spawnPoint.posZ + 0.5, yaw, 0.0F);

        world.spawnEntityInWorld(bot);
        server.getConfigurationManager().playerEntityList.add(bot);
        server.getConfigurationManager().sendPacketToAllPlayers(new S38PacketPlayerListItem(bot.getDisplayName(), true, 0));

        trainingBots.add(bot);
    }

    /** Despawns every training bot immediately - called the instant a real player joins, since these only ever exist while nobody real is online. */
    public static void despawnAll() {
        if (trainingBots.isEmpty()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        for (BotPlayer bot : trainingBots) {
            if (server != null) {
                server.getConfigurationManager().playerEntityList.remove(bot);
                server.getConfigurationManager().sendPacketToAllPlayers(new S38PacketPlayerListItem(bot.getDisplayName(), false, 0));
            }
            bot.worldObj.removeEntity(bot);
        }
        trainingBots.clear();
    }
}
