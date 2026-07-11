package com.aibot.entity.ai;

import com.aibot.brain.ActionType;
import com.aibot.brain.BrainManager;
import com.aibot.brain.StateEncoder;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAIBase;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;

import java.util.List;

/**
 * Every few ticks, asks the shared brain for the next action and carries it out
 * in the world (movement, jumping, mining, attacking). Works on any EntityLiving,
 * so it can be bolted onto an ordinary vanilla mob instead of a custom entity type
 * - that way clients never need to know about a new entity and don't need the mod.
 */
public class EntityAIBrainControl extends EntityAIBase {

    private static final int ACTION_HOLD_TICKS = 4;
    private static final float ATTACK_DAMAGE = 3.0F;

    private final EntityLiving mob;
    private ActionType currentAction = ActionType.IDLE;
    private int actionTicksRemaining = 0;

    public EntityAIBrainControl(EntityLiving mob) {
        this.mob = mob;
        setMutexBits(3);
    }

    @Override
    public boolean shouldExecute() {
        return true;
    }

    @Override
    public void updateTask() {
        if (actionTicksRemaining <= 0) {
            currentAction = decideAction();
            actionTicksRemaining = ACTION_HOLD_TICKS;
        }
        actionTicksRemaining--;
        execute(currentAction);
    }

    private ActionType decideAction() {
        double[] state = StateEncoder.encode(mob.worldObj, mob);
        int idx = BrainManager.instance.getNetwork().predictAction(state);
        return ActionType.VALUES[idx];
    }

    private void execute(ActionType action) {
        switch (action) {
            case MOVE_FORWARD:
                moveRelative(1.0F, 0.0F);
                break;
            case MOVE_BACKWARD:
                moveRelative(-1.0F, 0.0F);
                break;
            case STRAFE_LEFT:
                moveRelative(0.0F, 1.0F);
                break;
            case STRAFE_RIGHT:
                moveRelative(0.0F, -1.0F);
                break;
            case JUMP:
                if (mob.onGround) {
                    mob.motionY = 0.42D;
                }
                break;
            case MINE_BLOCK:
                mineAhead();
                break;
            case PLACE_BLOCK:
                break;
            case ATTACK:
                attackNearby();
                break;
            case IDLE:
            default:
                mob.getNavigator().clearPathEntity();
                break;
        }
    }

    private void moveRelative(float forwardSign, float strafeSign) {
        double yawRad = Math.toRadians(mob.rotationYaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);

        double targetX = mob.posX + forwardX * forwardSign * 2.0 + rightX * strafeSign * 2.0;
        double targetZ = mob.posZ + forwardZ * forwardSign * 2.0 + rightZ * strafeSign * 2.0;

        mob.getMoveHelper().setMoveTo(targetX, mob.posY, targetZ, 0.6D);
    }

    private void mineAhead() {
        Vec3 look = mob.getLookVec();
        int x = MathHelper.floor_double(mob.posX + look.xCoord * 1.5);
        int y = MathHelper.floor_double(mob.posY + mob.getEyeHeight() + look.yCoord * 1.5);
        int z = MathHelper.floor_double(mob.posZ + look.zCoord * 1.5);

        World world = mob.worldObj;
        Block block = world.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) {
            return;
        }
        float hardness = block.getBlockHardness(world, x, y, z);
        if (hardness < 0.0F || hardness > 5.0F) {
            return;
        }
        int meta = world.getBlockMetadata(x, y, z);
        world.setBlockToAir(x, y, z);
        world.playAuxSFX(2001, x, y, z, Block.getIdFromBlock(block) + (meta << 12));
    }

    private void attackNearby() {
        @SuppressWarnings("unchecked")
        List<EntityLivingBase> nearby = mob.worldObj.getEntitiesWithinAABB(EntityLivingBase.class, mob.boundingBox.expand(1.5, 1.0, 1.5));
        for (EntityLivingBase other : nearby) {
            if (other == mob) continue;
            other.attackEntityFrom(DamageSource.causeMobDamage(mob), ATTACK_DAMAGE);
            break;
        }
    }
}
