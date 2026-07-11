package com.aibot.brain;

import com.aibot.fakeplayer.BotPlayer;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.List;

/**
 * Builds a fixed-size feature vector describing the world around a living entity.
 * Used identically for real players (to create training samples) and for the
 * brain mob (to make decisions), so the network sees the same kind of input either way.
 *
 * Expanded from the original 12 features to 19 (2026-07-10) to give the network
 * real awareness of: lava specifically (not just "any liquid"), rough terrain
 * shape ahead (cliff/hill), a second-nearest hostile (not just the single
 * closest), and separately-tracked nearest real player vs nearest passive/
 * neutral mob (previously lumped together as "nearest other"). Changing
 * STATE_SIZE resets the trained network on next load (see BrainManager.load's
 * topology-mismatch guard) - a one-time, safe, expected cost of this change,
 * not a bug.
 */
public class StateEncoder {

    public static final int STATE_SIZE = 19;

    /** How many blocks ahead (in look direction) to sample for cliff/hill detection. */
    private static final int SLOPE_SAMPLE_DISTANCE = 3;
    private static final double SLOPE_NORMALIZE = 4.0;

    /**
     * How far the bot senses other entities (hostiles/players/passive mobs).
     * Originally pure distance math against the entity list with no
     * raycasting at all, meaning it could sense straight through walls -
     * changed per explicit "make it like a real player would [perceive]"
     * request: entity sensing is now filtered by an actual line-of-sight
     * raycast (see hasLineOfSight), so something on the other side of a wall
     * or floor genuinely isn't sensed, the same as a real player couldn't see
     * it either. This is a real, deliberate change to what these input
     * features mean - the network was trained against the old, omniscient
     * version of them, so behavior may shift for a while as it adapts to the
     * new, more honest signal. A backup of the pre-change file was kept
     * before this edit per explicit request.
     */
    private static final double ENTITY_SENSE_RADIUS = 32.0;

    public static double[] encode(World world, EntityLivingBase entity) {
        double[] state = new double[STATE_SIZE];

        int x = MathHelper.floor_double(entity.posX);
        int y = MathHelper.floor_double(entity.boundingBox.minY);
        int z = MathHelper.floor_double(entity.posZ);

        state[0] = entity.getHealth() / Math.max(1.0F, entity.getMaxHealth());
        state[1] = entity.isInWater() ? 1.0 : 0.0;
        state[2] = isInLava(entity) ? 1.0 : 0.0;
        state[3] = entity.onGround ? 1.0 : 0.0;

        int light = 8;
        try {
            light = world.getBlockLightValue(x, y, z);
        } catch (Exception ignored) {
        }
        state[4] = light / 15.0;

        Vec3 look = entity.getLookVec();
        int aheadX = MathHelper.floor_double(entity.posX + look.xCoord);
        int aheadY = MathHelper.floor_double(entity.posY + look.yCoord);
        int aheadZ = MathHelper.floor_double(entity.posZ + look.zCoord);
        Block aheadBlock = world.getBlock(aheadX, aheadY, aheadZ);
        state[5] = aheadBlock.getMaterial().isSolid() ? 1.0 : 0.0;
        state[6] = aheadBlock.getMaterial().isLiquid() ? 1.0 : 0.0;
        state[7] = aheadBlock.getMaterial() == Material.lava ? 1.0 : 0.0;

        Block belowBlock = world.getBlock(x, y - 1, z);
        state[8] = belowBlock.getMaterial().isSolid() ? 1.0 : 0.0;

        state[9] = groundSlopeAhead(world, entity, look, x, y, z);

        double nearestHostileDist = ENTITY_SENSE_RADIUS;
        double nearestHostileAngle = 0.0;
        double secondHostileDist = ENTITY_SENSE_RADIUS;
        double secondHostileAngle = 0.0;
        double nearestPlayerDist = ENTITY_SENSE_RADIUS;
        double nearestPlayerAngle = 0.0;
        double nearestPassiveDist = ENTITY_SENSE_RADIUS;
        double nearestPassiveAngle = 0.0;

        AxisAlignedBB area = entity.boundingBox.expand(ENTITY_SENSE_RADIUS, ENTITY_SENSE_RADIUS / 2.0, ENTITY_SENSE_RADIUS);
        @SuppressWarnings("unchecked")
        List<EntityLivingBase> nearby = world.getEntitiesWithinAABB(EntityLivingBase.class, area);
        for (EntityLivingBase other : nearby) {
            if (other == entity) continue;
            // The bot itself is a real EntityPlayer under the hood - never let it
            // treat its own kind as "a nearby player" for this feature.
            if (other instanceof BotPlayer) continue;

            double dist = other.getDistanceToEntity(entity);

            // Line-of-sight is only actually checked once a candidate would already
            // improve on the best-known distance for its category - a real raycast
            // per nearby entity, every tick, across up to 17 simultaneous bots
            // (main + 16 training) would be real, avoidable server-tick cost for
            // entities that were never going to become the "nearest" anyway.
            if (other instanceof IMob) {
                if (dist < nearestHostileDist) {
                    if (!hasLineOfSight(world, entity, other)) continue;
                    secondHostileDist = nearestHostileDist;
                    secondHostileAngle = nearestHostileAngle;
                    nearestHostileDist = dist;
                    nearestHostileAngle = relativeAngleTo(entity, other);
                } else if (dist < secondHostileDist) {
                    if (!hasLineOfSight(world, entity, other)) continue;
                    secondHostileDist = dist;
                    secondHostileAngle = relativeAngleTo(entity, other);
                }
            } else if (other instanceof EntityPlayer) {
                if (dist < nearestPlayerDist) {
                    if (!hasLineOfSight(world, entity, other)) continue;
                    nearestPlayerDist = dist;
                    nearestPlayerAngle = relativeAngleTo(entity, other);
                }
            } else {
                if (dist < nearestPassiveDist) {
                    if (!hasLineOfSight(world, entity, other)) continue;
                    nearestPassiveDist = dist;
                    nearestPassiveAngle = relativeAngleTo(entity, other);
                }
            }
        }

        // Normalized against ENTITY_SENSE_RADIUS (32), not a stale hardcoded 8 -
        // confirmed live as a real bug: when the sense radius was widened from
        // 8 to 32 (for the "give him all the ESP" request), this divisor was
        // never updated to match, so any entity between 8-32 blocks away
        // produced an increasingly negative, unbounded feature value (down to
        // -3.0 at the edge of range) instead of a clean 0-1 scale. Now 1.0 =
        // right on top of it, 0.0 = at the edge of sense range or beyond/none.
        state[10] = 1.0 - (nearestHostileDist / ENTITY_SENSE_RADIUS);
        state[11] = nearestHostileAngle;
        state[12] = 1.0 - (secondHostileDist / ENTITY_SENSE_RADIUS);
        state[13] = secondHostileAngle;
        state[14] = 1.0 - (nearestPlayerDist / ENTITY_SENSE_RADIUS);
        state[15] = nearestPlayerAngle;
        state[16] = 1.0 - (nearestPassiveDist / ENTITY_SENSE_RADIUS);
        state[17] = nearestPassiveAngle;

        float yaw = MathHelper.wrapAngleTo180_float(entity.rotationYaw);
        state[18] = yaw / 180.0;

        return state;
    }

    /** Real eye-to-eye raycast against solid blocks (liquids don't block vision, matching real-player sight through water) - true when nothing solid sits between the two entities. */
    private static boolean hasLineOfSight(World world, EntityLivingBase from, EntityLivingBase to) {
        Vec3 eyeStart = Vec3.createVectorHelper(from.posX, from.posY + from.getEyeHeight(), from.posZ);
        Vec3 eyeEnd = Vec3.createVectorHelper(to.posX, to.posY + to.getEyeHeight(), to.posZ);
        return world.rayTraceBlocks(eyeStart, eyeEnd) == null;
    }

    private static boolean isInLava(EntityLivingBase entity) {
        int x = MathHelper.floor_double(entity.posX);
        int y = MathHelper.floor_double(entity.boundingBox.minY);
        int z = MathHelper.floor_double(entity.posZ);
        return entity.worldObj.getBlock(x, y, z).getMaterial() == Material.lava;
    }

    /**
     * Positive = ground drops away ahead (cliff/ledge), negative = ground rises
     * ahead (hill/wall), near zero = flat. Scans down from a few blocks up to a
     * few blocks down at a point SLOPE_SAMPLE_DISTANCE ahead in the look
     * direction to find the first solid surface, compares its height to the
     * entity's own current ground level.
     */
    private static double groundSlopeAhead(World world, EntityLivingBase entity, Vec3 look, int x, int y, int z) {
        int aheadX = MathHelper.floor_double(entity.posX + look.xCoord * SLOPE_SAMPLE_DISTANCE);
        int aheadZ = MathHelper.floor_double(entity.posZ + look.zCoord * SLOPE_SAMPLE_DISTANCE);

        int aheadGroundY = findGroundY(world, aheadX, y, aheadZ);
        if (aheadGroundY == Integer.MIN_VALUE) {
            return 1.0; // nothing solid found nearby below - treat as a steep drop-off
        }

        double delta = (y - aheadGroundY) / SLOPE_NORMALIZE;
        return MathHelper.clamp_double(delta, -1.0, 1.0);
    }

    /** Scans down (then up a little, in case of a small rise) from startY for the first solid block, or Integer.MIN_VALUE if none found in range. */
    private static int findGroundY(World world, int x, int startY, int z) {
        for (int dy = 2; dy >= -6; dy--) {
            int checkY = startY + dy;
            if (world.getBlock(x, checkY, z).getMaterial().isSolid()) {
                return checkY;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static double relativeAngleTo(EntityLivingBase from, EntityLivingBase to) {
        double dx = to.posX - from.posX;
        double dz = to.posZ - from.posZ;
        double angleToTarget = Math.toDegrees(Math.atan2(-dx, dz));
        float relative = MathHelper.wrapAngleTo180_float((float) (angleToTarget - from.rotationYaw));
        return relative / 180.0;
    }
}
