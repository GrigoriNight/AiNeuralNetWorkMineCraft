package com.aibot.command;

import com.aibot.entity.ai.EntityAIBrainControl;
import net.minecraft.entity.ai.EntityAILookIdle;
import net.minecraft.entity.ai.EntityAISwimming;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;

/** Bot actions shared between the in-game /brain command and the web API, so both behave identically. */
public class BrainActions {

    public static EntityZombie spawnZombie(EntityPlayer near) {
        return spawnZombie((WorldServer) near.worldObj, near.posX, near.posY, near.posZ, near.rotationYaw);
    }

    /** Spawns at the world's spawn point - used when there's no player to stand next to (e.g. from the API). */
    public static EntityZombie spawnZombie(WorldServer world) {
        ChunkCoordinates spawnPoint = world.getSpawnPoint();
        return spawnZombie(world, spawnPoint.posX + 0.5, spawnPoint.posY, spawnPoint.posZ + 0.5, 0.0F);
    }

    private static EntityZombie spawnZombie(WorldServer world, double x, double y, double z, float yaw) {
        EntityZombie mob = new EntityZombie(world);
        mob.tasks.taskEntries.clear();
        mob.targetTasks.taskEntries.clear();
        mob.tasks.addTask(0, new EntityAISwimming(mob));
        mob.tasks.addTask(1, new EntityAIBrainControl(mob));
        mob.tasks.addTask(2, new EntityAILookIdle(mob));
        mob.setLocationAndAngles(x, y, z, yaw, 0.0F);
        world.spawnEntityInWorld(mob);
        return mob;
    }
}
