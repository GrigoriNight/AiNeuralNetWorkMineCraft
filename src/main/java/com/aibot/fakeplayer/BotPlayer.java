package com.aibot.fakeplayer;

import com.aibot.brain.ActionType;
import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ItemInWorldManager;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A real EntityPlayerMP with no real network connection behind it, so vanilla
 * clients render it exactly like an ordinary player (name tag, skin, tab list)
 * without needing any mod installed. Movement/actions are driven by BotPlayerAI.
 */
public class BotPlayer extends EntityPlayerMP {

    public ActionType currentAction = ActionType.IDLE;
    public int actionTicksRemaining = 0;
    public int selfSampleCooldown = 0;
    public int sleepCheckCooldown = 0;
    public int retaliateAttackCooldown = 0;
    public String followPlayerName;
    public String copyPlayerName;
    public double lastPosX;
    public double lastPosZ;
    public int stuckTicks = 0;
    public boolean hasGatherTarget = false;
    public double gatherTargetX;
    public double gatherTargetY;
    public double gatherTargetZ;
    public int gatherRescanCooldown = 0;
    public boolean hasOpenedDoor = false;
    public int openedDoorX;
    public int openedDoorY;
    public int openedDoorZ;
    public boolean hasLootTarget = false;
    public double lootTargetX;
    public double lootTargetY;
    public double lootTargetZ;
    public int lootRescanCooldown = 0;
    public int eatCheckCooldown = 0;
    public int combatSampleCooldown = 0;
    public int behaviorSampleCooldown = 0;
    public boolean hasFarmTarget = false;
    public double farmTargetX;
    public double farmTargetY;
    public double farmTargetZ;
    public int farmRescanCooldown = 0;
    public boolean hasOreTarget = false;
    public double oreTargetX;
    public double oreTargetY;
    public double oreTargetZ;
    public int oreRescanCooldown = 0;
    public boolean miningMode = false;

    /** Set every tick by BotPlayerAI.tick() to whichever priority tier actually ran - read by /brain status for live debugging without needing to watch the bot in-world. */
    public String currentBehavior = "idle";

    /**
     * While positive, target-chasing behaviors (seek-mob/follow/return-home) skip
     * their turn entirely instead of immediately overriding a hazard-avoidance
     * turn the instant it happens. Without this, a target sitting across/behind a
     * hazard (very often water now that avoidance covers all liquids) caused an
     * infinite tug-of-war: turn away from hazard, then turn right back toward the
     * target next tick, forever - confirmed live, stuck oscillating in place for
     * 4+ minutes straight chasing a mob. See BotPlayerAI.handleStuck.
     */
    public int pursuitSuppressedTicks = 0;

    public boolean hasStoneTarget = false;
    public double stoneTargetX;
    public double stoneTargetY;
    public double stoneTargetZ;
    public int stoneRescanCooldown = 0;
    public int woolRescanCooldown = 0;
    public int craftCheckCooldown = 0;
    public ActionType lastPredictedAction = null;
    public boolean forcedGoingHome = false;
    public int totalStuckTicks = 0;
    public int samePredictionStreak = 0;

    /** Only used by TrainingBotAI (the simple explore/mine/fight loop for the extra self-play-only bots) - unused and harmless on the main Direwolf20 instance, which never reaches that code path. */
    public float trainingMoveYaw = 0.0F;
    public int trainingDirectionCooldown = 0;
    public int trainingSampleCooldown = 0;

    /** Entities that have attacked this bot before, remembered past vanilla's ~5s getAITarget() window - bounded so it can't grow forever over a long session. */
    private static final int MAX_GRUDGES = 20;
    public final Set<UUID> grudgeEntityIds = new LinkedHashSet<UUID>() {
        @Override
        public boolean add(UUID uuid) {
            boolean added = super.add(uuid);
            while (size() > MAX_GRUDGES) {
                remove(iterator().next());
            }
            return added;
        }
    };

    public BotPlayer(MinecraftServer server, WorldServer world, GameProfile profile) {
        super(server, world, profile, new ItemInWorldManager(world));
    }

    /**
     * Every damage source (combat, fall, fire, drowning, void, etc.) funnels through
     * this one method in vanilla, so capping it here is a single choke point that
     * keeps health from ever reaching 0. This is deliberate: a real death fires
     * LivingDeathEvent to every mod on the server, and several (confirmed: FTB
     * Utilities' player-death handler) assume real per-player data that a fake
     * player who never went through normal login never got, NPE-ing and
     * disconnecting whoever's packet triggered it. Same rationale as not firing
     * PlayerLoggedInEvent/PlayerLoggedOutEvent for this bot elsewhere in the mod.
     */
    @Override
    public boolean attackEntityFrom(DamageSource source, float amount) {
        if (amount >= getHealth()) {
            amount = Math.max(0.0F, getHealth() - 1.0F);
        }
        return super.attackEntityFrom(source, amount);
    }
}
