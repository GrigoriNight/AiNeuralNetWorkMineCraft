package com.aibot.fakeplayer;

import com.aibot.brain.ActionType;
import com.aibot.brain.BrainManager;
import com.aibot.brain.StateEncoder;
import com.aibot.web.ErrorLog;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.init.Blocks;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;

import java.util.List;

/**
 * Much simpler than BotPlayerAI's full goal/base/chat-aware decision tree -
 * these bots exist purely to generate extra self-play training data while
 * nobody real is online (per explicit "spawn players to help the network
 * out... to find all the caves and holes and building and trees" request),
 * roaming widely to encounter varied terrain, mining/fighting whatever they
 * run into along the way, and feeding every action into the same shared
 * network Direwolf20 trains from. Deliberately has none of Direwolf20's goal
 * queue, base-building, or chat awareness - see the identity check added to
 * BotPlayerAI.onLivingUpdate for why that separation matters (several bots
 * all running the single-bot-assuming logic at once would fight over the
 * same shared state).
 */
public class TrainingBotAI {

    private static final float MOVE_SPEED = 0.7F;
    private static final float TURN_SPEED_DEG_PER_TICK = 12.0F;
    private static final double INTERACT_REACH = 2.5;
    private static final double MOB_SEEK_RADIUS = 24.0;
    /** Long stretches in one direction, changed rarely - this is what actually gets them out to new caves/structures/trees instead of circling near spawn. */
    private static final int DIRECTION_CHANGE_TICKS = 400;
    private static final int SAMPLE_INTERVAL_TICKS = 40;
    /** Below this, skip self-play recording - same "don't teach it to imitate a bad moment" reasoning as BotPlayerAI.isGoodStateForSelfPlay. */
    private static final float LOW_HEALTH_SKIP_THRESHOLD = 6.0F;
    private static final float ATTACK_DAMAGE = 3.0F;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (BotPlayer bot : TrainingBotManager.getTrainingBots()) {
            try {
                bot.onUpdateEntity();
            } catch (Exception e) {
                ErrorLog.record("TrainingBotAI.onServerTick", e);
            }
        }
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!(event.entity instanceof BotPlayer)) return;
        BotPlayer mob = (BotPlayer) event.entity;
        if (!TrainingBotManager.getTrainingBots().contains(mob)) return;

        try {
            tick(mob);
        } catch (Exception e) {
            ErrorLog.record("TrainingBotAI.onLivingUpdate", e);
        }
    }

    private void tick(BotPlayer mob) {
        mob.setJumping(false);
        mob.moveStrafing = 0.0F;
        mob.moveForward = 0.0F;

        if (tryAttackNearbyHostile(mob)) {
            recordSample(mob, ActionType.ATTACK);
            return;
        }
        if (tryMineAhead(mob)) {
            recordSample(mob, ActionType.MINE_BLOCK);
            return;
        }
        explore(mob);
        recordSample(mob, ActionType.MOVE_FORWARD);
    }

    private boolean tryAttackNearbyHostile(BotPlayer mob) {
        EntityLivingBase target = findNearestHostile(mob);
        if (target == null) return false;

        double dx = target.posX - mob.posX;
        double dz = target.posZ - mob.posZ;
        double distSq = dx * dx + dz * dz;
        turnToward(mob, (float) Math.toDegrees(Math.atan2(-dx, dz)));

        if (distSq > INTERACT_REACH * INTERACT_REACH) {
            mob.moveForward = MOVE_SPEED;
            return true;
        }
        mob.swingItem();
        target.attackEntityFrom(DamageSource.causePlayerDamage(mob), ATTACK_DAMAGE);
        return true;
    }

    @SuppressWarnings("unchecked")
    private EntityLivingBase findNearestHostile(BotPlayer mob) {
        List<EntityLivingBase> nearby = mob.worldObj.getEntitiesWithinAABB(EntityLivingBase.class,
                mob.boundingBox.expand(MOB_SEEK_RADIUS, MOB_SEEK_RADIUS / 2.0, MOB_SEEK_RADIUS));
        EntityLivingBase best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase e : nearby) {
            if (!e.isEntityAlive() || !(e instanceof IMob)) continue;
            double dx = e.posX - mob.posX;
            double dz = e.posZ - mob.posZ;
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = e;
            }
        }
        return best;
    }

    /** Simple raycast-free "block right in front" check, same spirit as BotPlayerAI.mineAhead - breaks whatever's directly ahead as long as it isn't hazardous, so exploring naturally mines through trees/stone/ore it walks into. */
    private boolean tryMineAhead(BotPlayer mob) {
        Vec3 look = mob.getLookVec();
        int x = MathHelper.floor_double(mob.posX + look.xCoord * 1.2);
        int y = MathHelper.floor_double(mob.posY + mob.getEyeHeight() + look.yCoord * 1.2);
        int z = MathHelper.floor_double(mob.posZ + look.zCoord * 1.2);

        Block block = mob.worldObj.getBlock(x, y, z);
        if (block.getMaterial() == Material.air || isHazardous(block)) return false;

        float hardness = block.getBlockHardness(mob.worldObj, x, y, z);
        if (hardness < 0.0F || hardness > 5.0F) return false;

        mob.swingItem();
        mob.worldObj.func_147480_a(x, y, z, true);
        return true;
    }

    private boolean isHazardous(Block block) {
        return block == Blocks.lava || block == Blocks.flowing_lava
                || block == Blocks.fire || block == Blocks.cactus;
    }

    /** Picks a random direction and commits to it for DIRECTION_CHANGE_TICKS at a time - crude but effective at covering real distance and varied terrain (caves, structures, trees) rather than circling near spawn like a tight wander would. */
    private void explore(BotPlayer mob) {
        mob.trainingDirectionCooldown--;
        if (mob.trainingDirectionCooldown <= 0 || isHazardAhead(mob)) {
            mob.trainingMoveYaw = mob.worldObj.rand.nextFloat() * 360.0F;
            mob.trainingDirectionCooldown = DIRECTION_CHANGE_TICKS;
        }
        turnToward(mob, mob.trainingMoveYaw);
        mob.moveForward = MOVE_SPEED;

        if (mob.onGround && mob.worldObj.rand.nextInt(20) == 0) {
            mob.setJumping(true);
        }
    }

    private boolean isHazardAhead(BotPlayer mob) {
        Vec3 look = mob.getLookVec();
        int x = MathHelper.floor_double(mob.posX + look.xCoord * 1.5);
        int y = MathHelper.floor_double(mob.posY);
        int z = MathHelper.floor_double(mob.posZ + look.zCoord * 1.5);
        Block block = mob.worldObj.getBlock(x, y, z);
        return isHazardous(block) || isHazardous(mob.worldObj.getBlock(x, y + 1, z));
    }

    private void turnToward(BotPlayer mob, float desiredYaw) {
        float delta = MathHelper.wrapAngleTo180_float(desiredYaw - mob.rotationYaw);
        delta = MathHelper.clamp_float(delta, -TURN_SPEED_DEG_PER_TICK, TURN_SPEED_DEG_PER_TICK);
        mob.rotationYaw += delta;
        mob.rotationYawHead = mob.rotationYaw;
    }

    /**
     * Feeds this bot's activity into the shared network - same "no JSON
     * library, hand-rolled everything" spirit as the rest of this project,
     * just reusing BrainManager.recordSelfSample directly. No IDLE skipping
     * needed here (unlike maybeRecordSelfSample on the main bot) since this
     * loop never predicts IDLE in the first place - it's always doing one of
     * these three things.
     */
    private void recordSample(BotPlayer mob, ActionType action) {
        mob.trainingSampleCooldown--;
        if (mob.trainingSampleCooldown > 0) return;
        mob.trainingSampleCooldown = SAMPLE_INTERVAL_TICKS;
        if (mob.getHealth() <= LOW_HEALTH_SKIP_THRESHOLD) return;

        double[] state = StateEncoder.encode(mob.worldObj, mob);
        BrainManager.instance.recordSelfSample(state, action);
    }
}
