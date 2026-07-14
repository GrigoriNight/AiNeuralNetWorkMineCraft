package com.aibot.fakeplayer;

import com.mojang.authlib.GameProfile;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S38PacketPlayerListItem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** Spawns/despawns the fake player and keeps the server's player list and tab list in sync. */
public class BotPlayerManager {

    private static String botName = "Direwolf20";
    private static BotPlayer active;

    /**
     * Persisted /brain mine intent - BotPlayer.miningMode itself lives on the
     * per-spawn entity and resets to false whenever a fresh BotPlayer is
     * constructed (e.g. the despawn/respawn cycle triggered by 2+ real players
     * going back down to 1). Confirmed live: toggling /brain mine on, then having
     * the bot despawn/respawn from an unrelated player-count change, silently
     * turned mining back off with no indication why. Tracking the user's actual
     * intent here (same pattern as botName) and re-applying it in spawn() fixes
     * that - mining now stays on across despawns until explicitly toggled off.
     */
    private static boolean miningModeIntent = false;

    public static boolean isMiningModeIntent() {
        return miningModeIntent;
    }

    public static void setMiningModeIntent(boolean enabled) {
        miningModeIntent = enabled;
    }

    /**
     * Tracks an explicit /brain hide - without this, BotPlayerAutoSpawner (which
     * unconditionally respawns the bot whenever it's not active, since the
     * despawn-on-untrusted-player logic was removed) would silently undo a manual
     * hide within CHECK_INTERVAL_TICKS of it happening. Cleared by /brain
     * spawnplayer or /brain hide again, matching the miningModeIntent pattern.
     */
    private static boolean hiddenIntent = false;

    public static boolean isHiddenIntent() {
        return hiddenIntent;
    }

    public static void setHiddenIntent(boolean hidden) {
        hiddenIntent = hidden;
    }

    /**
     * Separate from hiddenIntent on purpose - this tracks a hide that
     * PlayerActionRecorder.onPlayerSleep triggered automatically so a real
     * player's sleep isn't blocked, not one the user asked for with
     * /brain hide. BotPlayerAutoSpawner clears this (and lets the bot respawn
     * normally) once it's daytime again; an explicit /brain hide never gets
     * auto-reversed like that. Not persisted - only ever meaningful within
     * the same night it was set, never something that should survive a
     * restart.
     */
    private static boolean autoSleepHidden = false;

    public static boolean isAutoSleepHidden() {
        return autoSleepHidden;
    }

    public static void setAutoSleepHidden(boolean value) {
        autoSleepHidden = value;
    }

    /**
     * Player-assigned to-do list (/brain goal) - kept here rather than on
     * BotPlayer for the same reason as miningModeIntent/baseWallIndex above:
     * it must survive despawn/respawn cycles (a fresh BotPlayer instance every
     * time) and full server restarts, not just live for one spawn. Whichever
     * goal is at the head of the queue takes priority over the ambient NN/
     * mining-toggle behavior in BotPlayerAI.tick() until its target amount is
     * reached, then it's popped and the next one (if any) begins.
     */
    private static final Deque<Goal> goalQueue = new ArrayDeque<Goal>();
    private static int goalProgress = 0;

    public static void addGoal(GoalType type, int targetAmount) {
        goalQueue.addLast(new Goal(type, targetAmount));
        saveGoals();
    }

    public static boolean hasActiveGoal() {
        return !goalQueue.isEmpty();
    }

    public static Goal peekActiveGoal() {
        return goalQueue.peekFirst();
    }

    public static int getGoalProgress() {
        return goalProgress;
    }

    public static List<Goal> listGoals() {
        return new java.util.ArrayList<Goal>(goalQueue);
    }

    public static void clearGoals() {
        goalQueue.clear();
        goalProgress = 0;
        saveGoals();
    }

    /**
     * Called from BotPlayerAI whenever a goal-relevant unit of work actually
     * completes (a log chopped, a stone block mined, an ore mined, a sheep/
     * animal killed) - no-ops unless the head-of-queue goal matches the given
     * type, so ambient gathering done for the bot's own base-building needs
     * (which calls the same tryGatherWood/tryGatherStone etc. methods) doesn't
     * accidentally advance an unrelated queued goal.
     */
    public static void onGoalUnitGathered(GoalType type) {
        Goal current = goalQueue.peekFirst();
        if (current == null || current.type != type) return;

        goalProgress++;
        if (goalProgress >= current.targetAmount) {
            goalQueue.pollFirst();
            goalProgress = 0;
            say("Goal complete: " + current + (goalQueue.isEmpty() ? ". All goals done!" : ". Next up: " + goalQueue.peekFirst() + "."));
        }
        saveGoals();
    }

    public static void saveGoals() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "goals.dat"))));
            out.writeInt(goalQueue.size());
            for (Goal goal : goalQueue) {
                out.writeInt(goal.type.ordinal());
                out.writeInt(goal.targetAmount);
            }
            out.writeInt(goalProgress);
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

    public static void loadGoals() {
        File file = new File(getSaveDir(), "goals.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            int count = in.readInt();
            goalQueue.clear();
            GoalType[] types = GoalType.values();
            for (int i = 0; i < count; i++) {
                int typeOrdinal = in.readInt();
                int targetAmount = in.readInt();
                if (typeOrdinal >= 0 && typeOrdinal < types.length) {
                    goalQueue.addLast(new Goal(types[typeOrdinal], targetAmount));
                }
            }
            goalProgress = in.readInt();
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

    /**
     * Any player-built structure the bot notices in passing (bed, chest, or
     * item frame cluster - see BotPlayerAI's periodic detection check) gets
     * remembered here, per explicit "if it's near a player-built structure,
     * get the network to mark it as player's home/base" request. "The
     * network" can't literally store a discrete GPS fact in its weights (it's
     * a small classifier over numeric state, not a knowledge base), so this
     * is the actual mechanism: a persisted list the bot keeps building up
     * over time, the same way it already remembers its own base site and its
     * to-do list. Deduplicated against existing entries within
     * KNOWN_BASE_DEDUP_RADIUS so wandering back through the same structure
     * repeatedly doesn't pile up redundant near-identical points.
     * isProtectedFromDigging checks every entry here (in addition to the
     * user's hardcoded HOME_X/Z and the bot's own base) so digging protection
     * accumulates as it discovers more of them, not just the live nearby-scan
     * at the moment of a specific dig attempt.
     */
    private static final List<double[]> knownPlayerBases = new ArrayList<double[]>();
    private static final double KNOWN_BASE_DEDUP_RADIUS = 30.0;

    public static List<double[]> getKnownPlayerBases() {
        return knownPlayerBases;
    }

    public static void recordKnownPlayerBase(double x, double z) {
        for (double[] existing : knownPlayerBases) {
            double dx = existing[0] - x;
            double dz = existing[1] - z;
            if (dx * dx + dz * dz <= KNOWN_BASE_DEDUP_RADIUS * KNOWN_BASE_DEDUP_RADIUS) return;
        }
        knownPlayerBases.add(new double[]{x, z});
        saveKnownPlayerBases();
    }

    public static void saveKnownPlayerBases() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "knownbases.dat"))));
            out.writeInt(knownPlayerBases.size());
            for (double[] loc : knownPlayerBases) {
                out.writeDouble(loc[0]);
                out.writeDouble(loc[1]);
            }
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

    public static void loadKnownPlayerBases() {
        File file = new File(getSaveDir(), "knownbases.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            int count = in.readInt();
            knownPlayerBases.clear();
            for (int i = 0; i < count; i++) {
                double x = in.readDouble();
                double z = in.readDouble();
                knownPlayerBases.add(new double[]{x, z});
            }
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

    /** Hardcoded home base for this deployment - shared by BotPlayerAI's wander/leash logic and /brain home. */
    public static final double HOME_X = 162.5;
    public static final double HOME_Y = 64.0;
    public static final double HOME_Z = 152.5;
    public static final int HOME_DIMENSION = 0;

    /**
     * Site for the bot's own constructed base - deliberately NOT a fixed offset
     * from HOME_X/Z (per explicit user request: "not where my base is at").
     * Picked once, randomly (a random direction and 200-400 block distance from
     * home), the first time it's actually needed (see ensureBaseLocationChosen),
     * then persisted so it stays put afterward rather than re-rolling on every
     * restart. Ground level at the chosen site is found dynamically the first
     * time the bot gets there (see BotPlayerAI.tryBuildBase) since terrain height
     * isn't known in advance either.
     */
    private static double baseX = Double.NaN;
    private static double baseZ = Double.NaN;
    private static double baseGroundY = Double.NaN;

    public static double getBaseX() {
        ensureBaseLocationChosen();
        return baseX;
    }

    public static double getBaseZ() {
        ensureBaseLocationChosen();
        return baseZ;
    }

    private static void ensureBaseLocationChosen() {
        if (!Double.isNaN(baseX)) return;
        java.util.Random random = new java.util.Random();
        double angle = random.nextDouble() * Math.PI * 2.0;
        double distance = 200.0 + random.nextDouble() * 200.0;
        baseX = HOME_X + Math.cos(angle) * distance;
        baseZ = HOME_Z + Math.sin(angle) * distance;
        saveBaseProgress();
    }

    public static double getBaseGroundY() {
        return baseGroundY;
    }

    public static void setBaseGroundY(double y) {
        baseGroundY = y;
    }

    /**
     * Base-build progress - deliberately kept here on the manager rather than on
     * BotPlayer, so it's remembered correctly across despawns/respawns (which
     * construct a brand new BotPlayer instance) and persisted to disk
     * (basedata.dat, same directory as inventory.dat/samples.dat) so it survives
     * full server restarts too. The door's location isn't stored separately since
     * it's always the same fixed offset from BASE_X/Z in the wall blueprint - see
     * BotPlayerAI.getBaseDoorOffset.
     */
    private static int baseWallIndex = 0;
    private static boolean baseBedPlaced = false;
    private static boolean baseChestPlaced = false;
    private static boolean baseDoorPlaced = false;
    private static boolean baseCraftingTablePlaced = false;

    public static int getBaseWallIndex() {
        return baseWallIndex;
    }

    public static void setBaseWallIndex(int index) {
        baseWallIndex = index;
    }

    public static boolean isBaseBedPlaced() {
        return baseBedPlaced;
    }

    public static void setBaseBedPlaced(boolean placed) {
        baseBedPlaced = placed;
    }

    public static boolean isBaseChestPlaced() {
        return baseChestPlaced;
    }

    public static void setBaseChestPlaced(boolean placed) {
        baseChestPlaced = placed;
    }

    public static boolean isBaseDoorPlaced() {
        return baseDoorPlaced;
    }

    public static void setBaseDoorPlaced(boolean placed) {
        baseDoorPlaced = placed;
    }

    public static boolean isBaseCraftingTablePlaced() {
        return baseCraftingTablePlaced;
    }

    public static void setBaseCraftingTablePlaced(boolean placed) {
        baseCraftingTablePlaced = placed;
    }

    public static void saveBaseProgress() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "basedata.dat"))));
            out.writeInt(baseWallIndex);
            out.writeBoolean(baseBedPlaced);
            out.writeBoolean(baseChestPlaced);
            out.writeBoolean(baseDoorPlaced);
            out.writeDouble(baseGroundY);
            out.writeDouble(baseX);
            out.writeDouble(baseZ);
            out.writeBoolean(baseCraftingTablePlaced);
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

    public static void loadBaseProgress() {
        File file = new File(getSaveDir(), "basedata.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            baseWallIndex = in.readInt();
            baseBedPlaced = in.readBoolean();
            baseChestPlaced = in.readBoolean();
            baseDoorPlaced = in.readBoolean();
            baseGroundY = in.readDouble();
            // Older save files (before the base location itself became
            // un-hardcoded) won't have these two doubles - readDouble() throwing
            // here is fine, it just means baseX/baseZ stay at their NaN default,
            // which correctly triggers ensureBaseLocationChosen() to pick a fresh
            // one on next use rather than silently defaulting to 0,0.
            baseX = in.readDouble();
            baseZ = in.readDouble();
            // Older save files (before the crafting-table requirement) won't
            // have this trailing boolean - readBoolean() throwing here is fine,
            // baseCraftingTablePlaced just stays at its false default, which
            // correctly means one still needs to be placed.
            baseCraftingTablePlaced = in.readBoolean();
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

    /**
     * A player-commanded or self-play-chosen schematic build in progress - same
     * "kept on the manager, not on BotPlayer" reasoning as the base-build fields
     * above (survives despawn/respawn, persisted to disk so it survives full
     * restarts too). schematicAnchorY starts NaN (ground level unknown) exactly
     * like baseGroundY - found dynamically once the bot is close enough, see
     * BotPlayerAI.tryBuildSchematic.
     */
    private static String schematicName = null;
    private static double schematicAnchorX;
    private static double schematicAnchorY = Double.NaN;
    private static double schematicAnchorZ;
    private static int schematicDimension;
    private static int schematicBlockIndex = 0;

    public static boolean hasActiveSchematicBuild() {
        return schematicName != null;
    }

    public static String getSchematicName() {
        return schematicName;
    }

    public static double getSchematicAnchorX() {
        return schematicAnchorX;
    }

    public static double getSchematicAnchorY() {
        return schematicAnchorY;
    }

    public static void setSchematicAnchorY(double y) {
        schematicAnchorY = y;
    }

    public static double getSchematicAnchorZ() {
        return schematicAnchorZ;
    }

    public static int getSchematicDimension() {
        return schematicDimension;
    }

    public static int getSchematicBlockIndex() {
        return schematicBlockIndex;
    }

    /**
     * Deliberately does NOT save on every call, matching setBaseWallIndex's
     * existing pattern - this is called once per block placement, which could
     * be dozens of times a minute during a build, and synchronous disk I/O
     * that often on the main tick thread is exactly the kind of thing this
     * project has already learned to avoid (see tryBuildSchematic's own
     * per-tick-load fix from earlier tonight). Progress is instead persisted
     * at the same checkpoints base-build progress already uses: despawn()
     * and server shutdown - a crash between those points loses at most the
     * blocks placed since the last checkpoint, not silently corrupting
     * anything, since placeNextWallBlock's/tryBuildSchematic's "already
     * correct" resume check re-derives lost progress for free anyway.
     */
    public static void setSchematicBlockIndex(int index) {
        schematicBlockIndex = index;
    }

    /** Starts (or restarts) a schematic build at the given anchor - used by both /brain schem build and the autonomous idle picker. */
    public static void startSchematicBuild(String name, double anchorX, double anchorZ, int dimension) {
        schematicName = name;
        schematicAnchorX = anchorX;
        schematicAnchorZ = anchorZ;
        schematicAnchorY = Double.NaN;
        schematicDimension = dimension;
        schematicBlockIndex = 0;
        saveSchematicProgress();
    }

    public static void clearSchematicBuild() {
        schematicName = null;
        schematicBlockIndex = 0;
        schematicAnchorY = Double.NaN;
        saveSchematicProgress();
    }

    public static void saveSchematicProgress() {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getSaveDir(), "schembuild.dat"))));
            out.writeBoolean(schematicName != null);
            if (schematicName != null) {
                out.writeUTF(schematicName);
                out.writeDouble(schematicAnchorX);
                out.writeDouble(schematicAnchorY);
                out.writeDouble(schematicAnchorZ);
                out.writeInt(schematicDimension);
                out.writeInt(schematicBlockIndex);
            }
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

    public static void loadSchematicProgress() {
        File file = new File(getSaveDir(), "schembuild.dat");
        if (!file.exists()) return;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            if (!in.readBoolean()) return;
            schematicName = in.readUTF();
            schematicAnchorX = in.readDouble();
            schematicAnchorY = in.readDouble();
            schematicAnchorZ = in.readDouble();
            schematicDimension = in.readInt();
            schematicBlockIndex = in.readInt();
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

    public static String getBotName() {
        return botName;
    }

    public static BotPlayer getActive() {
        return active;
    }

    public static BotPlayer spawn(EntityPlayer near) {
        return spawn((WorldServer) near.worldObj, near.posX, near.posY, near.posZ, near.rotationYaw);
    }

    /** Spawns at the world's spawn point - used when nobody's online to stand next to. */
    public static BotPlayer spawn(WorldServer world) {
        ChunkCoordinates spawnPoint = world.getSpawnPoint();
        return spawn(world, spawnPoint.posX + 0.5, spawnPoint.posY, spawnPoint.posZ + 0.5, 0.0F);
    }

    private static int pendingRespawnTicks = 0;
    private static double pendingRespawnX;
    private static double pendingRespawnY;
    private static double pendingRespawnZ;
    private static float pendingRespawnYaw;
    private static int pendingRespawnDimension;

    /** Called when the bot flees critically low health - despawns it and remembers where/when to bring it back. */
    public static void fleeAndScheduleRespawn(BotPlayer bot, int delayTicks) {
        // Respawn at home, not the exact spot it fled from - confirmed live as a
        // real bug: whatever hurt it (lava, a mob, fall damage) is usually still
        // right there, so respawning in the same spot just meant it got hurt
        // again almost immediately and fled again, over and over, forever, never
        // actually getting the "retreat and recover" the flee mechanic was meant
        // to provide. Prefers its own constructed base once that exists (per
        // explicit "he stays there and lives there" request - retreating to
        // its own walled, bedded home makes more sense than the arbitrary
        // HOME_X/Z anchor once it actually has a real home), falling back to
        // HOME_X/Y/Z before the base is built/located.
        if (isBaseChestPlaced() && !Double.isNaN(baseGroundY)) {
            pendingRespawnX = baseX;
            pendingRespawnY = baseGroundY + 1;
            pendingRespawnZ = baseZ;
        } else {
            pendingRespawnX = HOME_X;
            pendingRespawnY = HOME_Y;
            pendingRespawnZ = HOME_Z;
        }
        pendingRespawnYaw = bot.rotationYaw;
        pendingRespawnDimension = HOME_DIMENSION;
        pendingRespawnTicks = delayTicks;
        despawn(bot);
    }

    /**
     * Called by BotPlayerAutoSpawner every CHECK_INTERVAL_TICKS while a flee-
     * respawn is pending, counting it down by that same interval. Returns true
     * whenever a flee cycle is in progress (whether or not it just finished),
     * so the caller knows to skip its normal auto-spawn logic for this cycle -
     * otherwise the normal "spawn when nobody/only-trusted-player is online"
     * rule could respawn the bot immediately, defeating the whole point of the
     * 1-minute cooldown.
     */
    public static boolean tickPendingRespawn(int elapsedTicks) {
        if (pendingRespawnTicks <= 0) return false;

        pendingRespawnTicks -= elapsedTicks;
        if (pendingRespawnTicks <= 0) {
            pendingRespawnTicks = 0;
            MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (server != null && getActive() == null) {
                WorldServer world = server.worldServerForDimension(pendingRespawnDimension);
                if (world != null) {
                    spawn(world, pendingRespawnX, pendingRespawnY, pendingRespawnZ, pendingRespawnYaw);
                }
            }
        }
        return true;
    }

    static BotPlayer spawn(WorldServer world, double x, double y, double z, float yaw) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();

        GameProfile profile = new GameProfile(UUID.randomUUID(), botName);
        BotPlayer bot = new BotPlayer(server, world, profile);
        bot.playerNetServerHandler = new FakeNetHandler(server, bot);
        bot.setLocationAndAngles(x, y, z, yaw, 0.0F);

        // Fresh EntityPlayerMP would otherwise start empty (random UUID, never loads
        // saved player data) - explicitly cleared first, then restored from our own
        // inventory.dat (written on every despawn/server stop) so the bot's items
        // and armor persist across respawns and restarts instead of resetting.
        Arrays.fill(bot.inventory.mainInventory, null);
        Arrays.fill(bot.inventory.armorInventory, null);
        bot.inventory.currentItem = 0;
        loadInventory(bot);
        bot.miningMode = miningModeIntent;

        world.spawnEntityInWorld(bot);
        server.getConfigurationManager().playerEntityList.add(bot);
        server.getConfigurationManager().sendPacketToAllPlayers(new S38PacketPlayerListItem(bot.getDisplayName(), true, 0));
        broadcastStatusMessage("multiplayer.player.joined", bot);

        active = bot;
        return bot;
    }

    public static void despawn(BotPlayer bot) {
        saveInventory(bot);
        saveBaseProgress();
        saveSchematicProgress();
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        server.getConfigurationManager().playerEntityList.remove(bot);
        server.getConfigurationManager().sendPacketToAllPlayers(new S38PacketPlayerListItem(bot.getDisplayName(), false, 0));
        broadcastStatusMessage("multiplayer.player.left", bot);
        bot.worldObj.removeEntity(bot);

        if (active == bot) {
            active = null;
        }
    }

    /**
     * The bot's name comes from an immutable GameProfile, so an in-place rename
     * isn't possible - this despawns (broadcasting a "left" under the old name)
     * and respawns at the same spot under the new name (broadcasting "joined"),
     * the same way a real player reconnecting under a different name would look.
     */
    public static void rename(String newName) {
        BotPlayer bot = active;
        if (bot == null) {
            botName = newName;
            return;
        }

        WorldServer world = (WorldServer) bot.worldObj;
        double x = bot.posX;
        double y = bot.posY;
        double z = bot.posZ;
        float yaw = bot.rotationYaw;

        despawn(bot);
        botName = newName;
        spawn(world, x, y, z, yaw);
    }

    /**
     * Searches a cube around the given position for a bed block. Used both for
     * the manual /brain sleep command and for automatic sleep-at-night.
     */
    public static ChunkCoordinates findNearbyBed(World world, int cx, int cy, int cz, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (world.getBlock(x, y, z) == Blocks.bed) {
                        return new ChunkCoordinates(x, y, z);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Finds the nearest bed and tries to sleep in it. sleepInBedAt() itself already
     * checks daytime/safety/distance and returns why it failed - we just have to get
     * the bot close enough first, which we do by teleporting rather than pathfinding
     * (BotPlayer has no pathfinding, same limitation as its normal movement).
     */
    public static EntityPlayer.EnumStatus trySleep(BotPlayer bot) {
        ChunkCoordinates bed = findNearbyBed(bot.worldObj,
                MathHelper.floor_double(bot.posX), MathHelper.floor_double(bot.posY), MathHelper.floor_double(bot.posZ), 24);
        if (bed == null) {
            return EntityPlayer.EnumStatus.NOT_POSSIBLE_HERE;
        }
        bot.setPosition(bed.posX + 0.5, bed.posY, bed.posZ + 0.5);
        return bot.sleepInBedAt(bed.posX, bed.posY, bed.posZ);
    }

    // The account used to remotely watch/check on the bot (via /brain status
    // etc.) rather than actually play - per explicit "make it so they can't
    // tell that you're online" request: this connection was silently counting
    // as "a real player online" everywhere that check is used, which despawned
    // all 16 training bots, re-leashed the main bot, and suppressed its
    // self-play recording for as long as it stayed connected to watch, even
    // though nobody was actually playing. Excluded by username specifically
    // (not by skipping the check for every account) so a genuine real player
    // on this same account name would still count normally - this only
    // matters because it's consistently this one monitoring connection.
    private static final String MONITORING_ONLY_USERNAME = "Hamashaich";

    @SuppressWarnings("unchecked")
    public static boolean hasRealPlayerOnline(MinecraftServer server) {
        List<EntityPlayerMP> players = server.getConfigurationManager().playerEntityList;
        for (EntityPlayerMP player : players) {
            if (player instanceof BotPlayer) continue;
            if (MONITORING_ONLY_USERNAME.equalsIgnoreCase(player.getCommandSenderName())) continue;
            return true;
        }
        return false;
    }

    private static File getSaveDir() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        File dir = server != null ? server.getFile("aibot") : new File("aibot");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * Persists the bot's inventory (main + armor) to <server_root>/aibot/inventory.dat
     * using InventoryPlayer's own writeToNBT (the same format vanilla uses for real
     * player data) so items, damage, enchantments, etc. all round-trip correctly.
     * Called on every despawn and on server shutdown (see AIBotMod) so nothing is
     * lost across a /brain hide, a rename, or a full restart.
     */
    public static void saveInventory(BotPlayer bot) {
        try {
            NBTTagCompound root = new NBTTagCompound();
            root.setTag("Inventory", bot.inventory.writeToNBT(new NBTTagList()));
            CompressedStreamTools.write(root, new File(getSaveDir(), "inventory.dat"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /** Called from spawn() right after the inventory is cleared - restores whatever was last saved, if anything. */
    private static void loadInventory(BotPlayer bot) {
        File file = new File(getSaveDir(), "inventory.dat");
        if (!file.exists()) return;

        try {
            NBTTagCompound root = CompressedStreamTools.read(file);
            if (root != null && root.hasKey("Inventory")) {
                bot.inventory.readFromNBT(root.getTagList("Inventory", 10));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void broadcastStatusMessage(String translationKey, BotPlayer bot) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        ChatComponentTranslation message = new ChatComponentTranslation(translationKey, bot.getDisplayName());
        message.setChatStyle(new ChatStyle().setColor(EnumChatFormatting.YELLOW));
        server.getConfigurationManager().sendPacketToAllPlayers(new S02PacketChat(message));
    }

    /**
     * Makes the bot speak in chat, formatted exactly like a real player's message
     * ("chat.type.text" - the same vanilla translation key normal chat uses, "<name> text").
     * No-op if no bot is currently active.
     */
    public static void say(String text) {
        BotPlayer bot = active;
        if (bot == null) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        ChatComponentTranslation message = new ChatComponentTranslation("chat.type.text", bot.getDisplayName(), text);
        server.getConfigurationManager().sendPacketToAllPlayers(new S02PacketChat(message));
    }
}
