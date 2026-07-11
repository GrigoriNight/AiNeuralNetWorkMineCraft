package com.aibot.events;

import com.aibot.brain.ActionType;
import com.aibot.brain.BrainManager;
import com.aibot.brain.StateEncoder;
import com.aibot.fakeplayer.BotPlayer;
import com.aibot.fakeplayer.BotPlayerManager;
import com.aibot.web.ChatAI;
import com.aibot.web.ChatLog;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerSleepInBedEvent;
import net.minecraftforge.event.world.BlockEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Watches real players on the server and turns what they do into (state, action)
 * training samples, fed straight into the shared BrainManager dataset.
 */
public class PlayerActionRecorder {

    private static final int MOVEMENT_SAMPLE_INTERVAL = 5;
    private static final double MOVE_EPSILON = 0.02;

    // A real player standing still briefly (aiming, reading chat, mining in place)
    // is normal and worth a few samples - but a long stationary stretch (AFK, or a
    // scripted/bot connection with no real movement) shouldn't get to keep flooding
    // the dataset with repetitive "IDLE was correct" samples forever. 8 consecutive
    // samples at the ~4/sec rate below is about 2 real seconds of stillness before
    // this stops recording more, until the player actually moves again.
    private static final int MAX_CONSECUTIVE_IDLE_SAMPLES = 8;

    private final Map<UUID, PositionSnapshot> lastPositions = new HashMap<UUID, PositionSnapshot>();
    private final Map<UUID, Integer> tickCounters = new HashMap<UUID, Integer>();
    private final Map<UUID, Integer> consecutiveIdleCounts = new HashMap<UUID, Integer>();

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.player.getUniqueID();
        lastPositions.remove(id);
        tickCounters.remove(id);
        consecutiveIdleCounts.remove(id);
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!(event.entity instanceof EntityPlayerMP) || event.entity instanceof BotPlayer) return;
        EntityPlayerMP player = (EntityPlayerMP) event.entity;
        UUID id = player.getUniqueID();

        PositionSnapshot last = lastPositions.get(id);
        double curX = player.posX, curY = player.posY, curZ = player.posZ;
        float yaw = player.rotationYaw;

        Integer counterObj = tickCounters.get(id);
        int counter = counterObj == null ? 0 : counterObj;
        counter++;
        if (counter >= MOVEMENT_SAMPLE_INTERVAL) {
            counter = 0;
            if (last != null) {
                ActionType action = classifyMovement(last, curX, curY, curZ, yaw, player.onGround, player.isSprinting());

                if (action == ActionType.IDLE) {
                    int idleStreak = (consecutiveIdleCounts.containsKey(id) ? consecutiveIdleCounts.get(id) : 0) + 1;
                    consecutiveIdleCounts.put(id, idleStreak);
                    if (idleStreak <= MAX_CONSECUTIVE_IDLE_SAMPLES) {
                        double[] state = StateEncoder.encode(player.worldObj, player);
                        BrainManager.instance.recordHumanSample(state, action);
                    }
                } else {
                    consecutiveIdleCounts.put(id, 0);
                    double[] state = StateEncoder.encode(player.worldObj, player);
                    BrainManager.instance.recordHumanSample(state, action);
                }
            }
        }
        tickCounters.put(id, counter);
        lastPositions.put(id, new PositionSnapshot(curX, curY, curZ, player.onGround));
    }

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null) return;
        double[] state = StateEncoder.encode(event.world, event.getPlayer());
        BrainManager.instance.recordHumanSample(state, ActionType.MINE_BLOCK);
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.PlaceEvent event) {
        if (event.player == null) return;
        double[] state = StateEncoder.encode(event.world, event.player);
        BrainManager.instance.recordHumanSample(state, ActionType.PLACE_BLOCK);
    }

    @SubscribeEvent
    public void onAttack(AttackEntityEvent event) {
        double[] state = StateEncoder.encode(event.entityPlayer.worldObj, event.entityPlayer);
        BrainManager.instance.recordHumanSample(state, ActionType.ATTACK);
    }

    /** Captures real player chat into ChatLog so it's readable over the API - see /api/v1/chat. */
    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        ChatLog.record(event.username, event.message);
        ChatAI.maybeReplyTo(event.player, event.username, event.message);
    }

    /**
     * Per explicit "if a real player is online, make sure hiding the bot lets
     * them sleep, but keep the brain training like it's dreaming" request: the
     * bot already tries to auto-sleep at night on its own (see
     * BotPlayerAI.maybeAutoSleep), but that only succeeds if it happens to be
     * near a bed at the time - otherwise it stays "awake" and, being a real
     * entry in the player list, silently blocks vanilla's all-players-must-be-
     * asleep check from ever letting a real player skip the night. If a real
     * player starts sleeping while the main bot is online, not already
     * asleep itself, and not already hidden, this gets it fully out of the
     * way (same full despawn/"left the game" as a manual /brain hide) so the
     * real player's sleep can succeed alone - tracked separately from a
     * manual hide (autoSleepHidden, not hiddenIntent) so BotPlayerAutoSpawner
     * can bring it back on its own once morning comes, instead of it staying
     * hidden forever like an intentional /brain hide would. The network
     * itself never stops training either way - BrainManager's training loop
     * replays already-collected samples on a fixed tick interval independent
     * of any bot being spawned, exactly like a sleeping brain still dreaming
     * while the body isn't doing anything.
     */
    @SubscribeEvent
    public void onPlayerSleep(PlayerSleepInBedEvent event) {
        if (event.entityPlayer == null || event.entityPlayer instanceof BotPlayer) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || !BotPlayerManager.hasRealPlayerOnline(server)) return;

        BotPlayer bot = BotPlayerManager.getActive();
        if (bot == null || bot.isPlayerSleeping() || BotPlayerManager.isHiddenIntent()) return;

        BotPlayerManager.setAutoSleepHidden(true);
        BotPlayerManager.despawn(bot);
    }

    private ActionType classifyMovement(PositionSnapshot last, double curX, double curY, double curZ, float yaw, boolean onGround, boolean sprinting) {
        double dx = curX - last.x;
        double dz = curZ - last.z;
        double dy = curY - last.y;
        double horizMag = Math.sqrt(dx * dx + dz * dz);

        if (last.onGround && !onGround && dy > 0.1) {
            return ActionType.JUMP;
        }
        if (horizMag < MOVE_EPSILON) {
            return ActionType.IDLE;
        }

        double yawRad = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double forwardAmt = dx * forwardX + dz * forwardZ;
        double strafeAmt = dx * rightX + dz * rightZ;

        if (Math.abs(forwardAmt) >= Math.abs(strafeAmt)) {
            if (forwardAmt >= 0) {
                return sprinting ? ActionType.SPRINT_FORWARD : ActionType.MOVE_FORWARD;
            }
            return ActionType.MOVE_BACKWARD;
        } else {
            return strafeAmt >= 0 ? ActionType.STRAFE_RIGHT : ActionType.STRAFE_LEFT;
        }
    }

    private static class PositionSnapshot {
        final double x, y, z;
        final boolean onGround;

        PositionSnapshot(double x, double y, double z, boolean onGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.onGround = onGround;
        }
    }
}
