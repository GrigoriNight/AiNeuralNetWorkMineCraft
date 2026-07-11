package com.aibot.fakeplayer;

import com.aibot.brain.ActionType;
import com.aibot.brain.BrainManager;
import com.aibot.brain.StateEncoder;
import com.aibot.web.ErrorLog;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoor;
import net.minecraft.block.material.Material;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityChicken;
import net.minecraft.entity.passive.EntityCow;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemAxe;
import net.minecraft.item.ItemFood;
import net.minecraft.item.ItemHoe;
import net.minecraft.item.ItemPickaxe;
import net.minecraft.item.ItemSpade;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.DamageSource;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingEvent.LivingUpdateEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives BotPlayer instances every tick using the shared brain, the same way
 * EntityAIBrainControl drives the zombie version - just through EntityPlayer's
 * own moveEntityWithHeading/setJumping instead of the mob AI/pathfinding system,
 * since EntityPlayer has none of that.
 *
 * IMPORTANT: EntityPlayerMP's real per-tick physics/AI (and the LivingUpdateEvent
 * below) is normally only driven by NetHandlerPlayServer.processPlayer() reacting
 * to an incoming movement packet from a real client - EntityPlayerMP.onUpdate()
 * (called by the world's generic entity tick loop) deliberately does NOT call
 * super.onUpdate(), it only does network/chunk bookkeeping. Since our bot has no
 * real client ever sending packets, nothing would ever call onUpdateEntity() (and
 * therefore LivingUpdateEvent would never fire) without onServerTick() below
 * doing it manually, once per tick, standing in for that missing network packet.
 */
public class BotPlayerAI {

    private static final int ACTION_HOLD_TICKS = 4;
    private static final float ATTACK_DAMAGE = 3.0F;
    private static final float MOVE_SPEED = 1.0F;
    private static final double RETALIATE_ATTACK_RANGE = 2.5;
    private static final double FOLLOW_STOP_DISTANCE = 3.0;

    private static final double LEASH_RADIUS = 40.0;

    /** Caps how fast the bot turns per tick so it swings its view around like a mouse-look turn instead of snap-aiming. */
    private static final float TURN_SPEED_DEG_PER_TICK = 12.0F;

    /** No real pathfinding exists (see class doc) - these tune the "notice it's not going anywhere, try jump then turn" fallback. */
    private static final double STUCK_MOVE_EPSILON = 0.05;
    private static final int STUCK_JUMP_THRESHOLD_TICKS = 10;
    private static final int STUCK_BREAK_THRESHOLD_TICKS = 20;
    /** Nearby bed/chest treated as "probably a base" for the obstacle-breaking guard - see isProtectedFromDigging. */
    private static final double STRUCTURE_PROTECT_RADIUS = 8.0;
    /**
     * Deliberately much smaller than HOME_PROTECT_RADIUS (100, tuned for "don't
     * chop wood that might be a wall") - confirmed live as a real bug: using the
     * 100-block radius here meant the dig-through-obstacles fix could never fire
     * anywhere near home, exactly where the bot spends most of its time, leaving
     * it with no way to clear a path there at all.
     */
    private static final double DIG_PROTECT_RADIUS = 15.0;
    private static final int STUCK_REROUTE_THRESHOLD_TICKS = 30;
    /** Absolute last resort - if nothing else has resolved being stuck for this long (~10s), force-teleport a short distance instead of oscillating forever. */
    private static final int ULTIMATE_STUCK_TICKS = 200;

    /** How long (ticks) seek-mob/follow/return-home stay suppressed after a hazard/stuck reroute, so the turn-away actually gets a chance before pursuit turns back toward its target. ~3s. */
    private static final int PURSUIT_SUPPRESS_TICKS = 60;

    /** Widened per explicit user request ("give him all the ESP you can") - wood/stone/chest/tillable scans see much further now. Block-volume scan, so kept more conservative than the pure-entity radii below. */
    private static final double GATHER_SCAN_RADIUS = 24.0;
    private static final int GATHER_RESCAN_INTERVAL_TICKS = 60;
    private static final double GATHER_REACH = 3.0;

    /** Logs within this radius of home are assumed to be building material, not a wild tree - never chopped. */
    private static final double HOME_PROTECT_RADIUS = 100.0;

    /** Widened per "give him all the ESP you can" - kept more moderate than GATHER_SCAN_RADIUS since ore scanning already covers a deep vertical range (ORE_SCAN_DOWN+UP), so the 3D volume cost compounds faster here. */
    private static final double ORE_SCAN_RADIUS = 18.0;
    private static final int ORE_SCAN_DOWN = 20;
    private static final int ORE_SCAN_UP = 6;
    private static final double ORE_HOME_DEPOSIT_RADIUS = 20.0;

    /** If the network confidently repeats the same non-IDLE decision this many times in a row, treat it as IDLE instead so gatherOrWander's fallback behaviors get a chance to run. */
    private static final int STUCK_PREDICTION_THRESHOLD = 10;

    /** Below this health, flee (log out) rather than keep fighting - a real player would retreat too. */
    private static final float FLEE_HEALTH_THRESHOLD = 6.0F;
    private static final int FLEE_RESPAWN_DELAY_TICKS = 20 * 60; // 1 minute

    private static final int CRAFT_CHECK_INTERVAL_TICKS = 100;
    /** The base's walls need 46 cobblestone exactly (5x5 perimeter, 3 tall, minus the 2-block door gap) - 48 gives a small buffer without pointlessly over-mining. */
    private static final int COBBLESTONE_TARGET = 48;

    /** Base footprint is a (2*BASE_HALF_SIZE+1) square, walls BASE_WALL_HEIGHT tall, one door gap. */
    private static final int BASE_HALF_SIZE = 2;
    private static final int BASE_WALL_HEIGHT = 3;

    private static final double DOOR_CLOSE_DISTANCE = 3.0;
    /** Widened per "give him all the ESP you can" - pure entity-list distance check, cheap regardless of radius (cost scales with nearby entity count, not scan volume), so bumped generously. Also used by wool/food hunting. */
    private static final double MOB_SEEK_RADIUS = 40.0;
    private static final int EAT_CHECK_INTERVAL_TICKS = 100;
    private static final int EAT_FOOD_THRESHOLD = 18;

    /** Combat samples are rarer than the ambient self-play ones and recorded regardless of who's online - real combat is valuable, rare signal. */
    private static final int COMBAT_SAMPLE_INTERVAL_DECISIONS = 8;

    /** Self-play samples are recorded far less often than human ones (every 5 ticks) - see BrainDataset's cap too. */
    private static final int SELF_SAMPLE_INTERVAL_DECISIONS = 8;

    /** How often (in ticks) to check whether it's night and a bed is nearby. */
    private static final int SLEEP_CHECK_INTERVAL_TICKS = 100;

    /**
     * Stands in for the real client movement packet that would normally drive
     * EntityPlayerMP.onUpdateEntity() (see class doc). This single call is what
     * makes the bot's physics run at all, and it cascades into firing
     * LivingUpdateEvent below, which is where the actual decision logic lives.
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        BotPlayer bot = BotPlayerManager.getActive();
        if (bot == null) return;

        try {
            bot.onUpdateEntity();
        } catch (Exception e) {
            ErrorLog.record("BotPlayerAI.onServerTick", e);
        }
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (!(event.entity instanceof BotPlayer)) return;
        BotPlayer mob = (BotPlayer) event.entity;
        // Identity check, not just type - now that TrainingBotManager can have
        // several more BotPlayer instances alive at once (per explicit "make 16
        // extra bots" request), this full goal/base/chat-aware decision tree
        // must only ever drive the one real bot. Training bots get their own,
        // much simpler tick loop (see TrainingBotAI) - letting them fall through
        // to here too would have several of them fighting over the same shared
        // goal queue/base-build state as if they were all the same bot.
        if (mob != BotPlayerManager.getActive()) return;

        try {
            tick(mob);
        } catch (Exception e) {
            ErrorLog.record("BotPlayerAI.onLivingUpdate", e);
        }
    }

    private void tick(BotPlayer mob) {
        if (mob.playerNetServerHandler instanceof FakeNetHandler) {
            ((FakeNetHandler) mob.playerNetServerHandler).drainOutbound();
        }

        // Vanilla EntityPlayer.onUpdate() (called via our onUpdateEntity() driver,
        // see class doc) does its own auto-wake-at-dawn check earlier in the same
        // tick, before LivingUpdateEvent (and therefore this method) fires - so by
        // the time we get here isPlayerSleeping() should already reflect a same-tick
        // wake-up. But that's an implicit ordering dependency on vanilla internals
        // we don't control, not something this mod enforces - force it explicitly
        // here too as a guarantee, so waking up (and immediately resuming whatever
        // it was doing before, via the hasGatherTarget/followPlayerName/etc. state
        // that sleeping never touches) doesn't silently break if that ordering ever
        // changes. wakeUpPlayer(true, true, false): immediate (no lingering sleep
        // animation), update the world's sleeping-players flag, don't reset spawn.
        if (mob.isPlayerSleeping()) {
            if (mob.worldObj.isDaytime()) {
                mob.wakeUpPlayer(true, true, false);
            } else {
                mob.currentBehavior = "sleeping";
                return;
            }
        }

        if (maybeAutoSleep(mob)) {
            mob.currentBehavior = "sleeping";
            return;
        }

        // Cleared each tick so only whichever behavior below actually wants to jump
        // this tick sets it - otherwise it'd latch true forever once anything set it,
        // since nothing resets it for us the way a real network "not jumping" packet would.
        mob.setJumping(false);
        handleStuck(mob);

        // Opening/closing doors and eating don't need to consume the whole tick's
        // decision - they're cheap side-checks that run alongside whatever else
        // this tick ends up doing.
        tryHandleDoors(mob);
        maybeCloseDoor(mob);
        tryEat(mob);
        tryCraft(mob);

        // Critically low health - flee by logging out entirely rather than
        // continuing to fight (BotPlayer.attackEntityFrom already floors health at
        // 1 so it can never actually die, but sitting at near-zero HP getting hit
        // repeatedly isn't meaningfully different from dying in practice). Comes
        // back automatically after FLEE_RESPAWN_DELAY_TICKS at the same spot.
        if (mob.getHealth() <= FLEE_HEALTH_THRESHOLD) {
            BotPlayerManager.fleeAndScheduleRespawn(mob, FLEE_RESPAWN_DELAY_TICKS);
            return;
        }

        // Vanilla EntityLivingBase already tracks whoever last hit it via
        // attackEntityFrom (getAITarget/setRevengeTarget), self-clearing once the
        // attacker dies or ~5s pass with no fresh hit - no event listener needed.
        // While a target is set, retaliation overrides the NN-driven decision entirely.
        if (tryRetaliate(mob)) {
            mob.currentBehavior = "retaliating";
            applyNnMovementFlourish(mob);
            return;
        }

        // /brain home - walks there like any other movement in this class, instead
        // of the instant teleport this command used to do (looked like "popping
        // out of thin air" per explicit user feedback).
        if (mob.forcedGoingHome && tryForcedGoHome(mob)) {
            mob.currentBehavior = "heading home (/brain home)";
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Explicit player commands (/brain copy, /brain follow) take priority over
        // the ambient home-leash/NN behavior below, but not over retaliating when hit.
        // Copy intentionally skips the flourish (and sample recording) - it's
        // mirroring another player's exact input 1:1, not making its own
        // judgment, so there's nothing of the network's own to reinforce here.
        if (tryCopy(mob)) {
            mob.currentBehavior = "copying " + mob.copyPlayerName;
            return;
        }

        if (tryFollow(mob)) {
            mob.currentBehavior = "following " + mob.followPlayerName;
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Proactively fight nearby hostile mobs (never real players - only
        // retaliation, above, ever targets a player) like a real player exploring
        // would, before falling back to the leash/NN/wander logic below.
        if (trySeekAndKillMob(mob)) {
            mob.currentBehavior = "seeking/killing mob";
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Strayed too far from home (chased off, knocked back, etc.) - walk back
        // before resuming normal brain-driven behavior. Takes priority over the NN
        // the same way retaliation does, but only kicks in past LEASH_RADIUS.
        if (tryReturnHome(mob)) {
            mob.currentBehavior = "returning home (leash)";
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Carrying ore and there's somewhere to put it - make the trip before
        // resuming normal gathering/wandering, same priority tier as the leash.
        if (tryDepositOre(mob)) {
            mob.currentBehavior = "depositing ore at home chest";
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Player-assigned to-do list (/brain goal) - a deliberate, specific request
        // takes priority over the ambient /brain mine toggle and NN-driven idle
        // wandering below, same tier as the explicit commands above it. Falls
        // through to everything below once the queue is empty.
        if (BotPlayerManager.hasActiveGoal() && tryWorkOnGoal(mob)) {
            Goal activeGoal = BotPlayerManager.peekActiveGoal();
            mob.currentBehavior = "working on goal: " + activeGoal
                    + " (" + BotPlayerManager.getGoalProgress() + "/" + activeGoal.targetAmount + ")";
            applyNnMovementFlourish(mob);
            return;
        }

        // /brain mine - explicit request to actively go find ore right now, rather
        // than waiting for the NN to happen to predict IDLE and fall through to
        // gatherOrWander's wood->ore->chest->farm chain. Still yields to everything
        // above (retaliate/copy/follow/seek-mob/home-leash/deposit-run).
        if (mob.miningMode && tryMineOre(mob)) {
            mob.currentBehavior = "mining ore" + (mob.hasOreTarget ? " (target at " + (int) mob.oreTargetX + "," + (int) mob.oreTargetY + "," + (int) mob.oreTargetZ + ")" : " (scanning)");
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Genuine hunger takes priority over construction - confirmed live as a
        // real bug (see tryHuntFood's own doc comment): with nothing else
        // feeding it, food crashed to 0 and stayed there for the bot's entire
        // session. Must stay above the base-building tier below it, not just
        // above gatherOrWander's other fallbacks - moving base-building to its
        // own top-level tier (next) would have silently reintroduced that
        // exact bug otherwise, since gatherOrWander (and this same hunger
        // check that used to live at its top) would never even be reached
        // until the base was finished.
        if (tryHuntFood(mob)) {
            mob.currentBehavior = "hunting food";
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        // Building its own base - confirmed live as a real bug: this used to
        // only run inside gatherOrWander, which is itself only reached when
        // the NN predicts IDLE. Whenever it predicted anything else
        // (MOVE_FORWARD, STRAFE_LEFT, etc. - whatever direction it happened
        // to already be facing, not toward the base site), that took over
        // instead, and since the base site can be hundreds of blocks away,
        // an undertrained network alternating between different non-IDLE
        // guesses could starve base-building indefinitely without ever
        // triggering the same-prediction-streak safety net (that only
        // catches repeating the SAME action many times in a row). Elevating
        // this to its own tier - same idea as the goal queue above - means
        // the network's own noisy guesses can no longer interrupt a
        // half-built base once it's underway.
        if (!BotPlayerManager.isBaseChestPlaced() && tryBuildBase(mob)) {
            mob.currentBehavior = "building base (see /brain base)";
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }

        if (mob.actionTicksRemaining <= 0) {
            double[] state = StateEncoder.encode(mob.worldObj, mob);
            int idx = BrainManager.instance.getNetwork().predictAction(state);
            ActionType predicted = ActionType.VALUES[idx];

            // Defensive: an undertrained network (e.g. right after a StateEncoder
            // change wipes historical samples - confirmed live) can get stuck
            // confidently repeating one non-IDLE action indefinitely. Since every
            // gathering/crafting/building fallback behavior in gatherOrWander only
            // ever runs on an IDLE decision, a stuck prediction would otherwise
            // block all of them forever, not just look repetitive.
            if (predicted == mob.lastPredictedAction && predicted != ActionType.IDLE) {
                mob.samePredictionStreak++;
            } else {
                mob.samePredictionStreak = 0;
            }
            mob.lastPredictedAction = predicted;

            mob.currentAction = mob.samePredictionStreak >= STUCK_PREDICTION_THRESHOLD ? ActionType.IDLE : predicted;
            mob.actionTicksRemaining = ACTION_HOLD_TICKS;

            maybeRecordSelfSample(mob, state, mob.currentAction);
        }
        mob.actionTicksRemaining--;
        mob.currentBehavior = "NN decision: " + mob.currentAction
                + (mob.currentAction == ActionType.IDLE ? " -> " + describeIdleFallback(mob) : "");

        execute(mob, mob.currentAction);
    }

    /** Best-effort description of what gatherOrWander will actually do this tick, for /brain status - doesn't mutate state, just inspects existing target flags. */
    private String describeIdleFallback(BotPlayer mob) {
        if (!BotPlayerManager.isBaseChestPlaced()) return "working on base (see /brain base)";
        if (mob.hasGatherTarget) return "gathering wood";
        if (mob.hasStoneTarget) return "gathering stone";
        if (mob.hasOreTarget) return "mining ore";
        if (mob.hasLootTarget) return "looting chest";
        if (mob.hasFarmTarget) return "farming";
        return "wandering (or about to pick a new gather/loot/farm/wander target)";
    }

    /**
     * If something (mob or player) has hit the bot recently, chase and attack it
     * instead of following the NN's normal decision. Returns true while retaliation
     * is active (caller should skip the normal decision loop this tick).
     */
    private boolean tryRetaliate(BotPlayer mob) {
        EntityLivingBase target = mob.getAITarget();
        if (target == null || !target.isEntityAlive() || target.worldObj != mob.worldObj) {
            return false;
        }

        // Remember this attacker past vanilla's own ~5s getAITarget() window, so a
        // mob that hit it once and wandered off can be recognized/re-engaged later
        // by trySeekAndKillMob. Deliberately does NOT make it seek out a player who
        // attacked before - retaliation-only for players, per the earlier PvP scoping.
        mob.grudgeEntityIds.add(target.getUniqueID());

        double dx = target.posX - mob.posX;
        double dz = target.posZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        boolean inRange = distSq <= RETALIATE_ATTACK_RANGE * RETALIATE_ATTACK_RANGE;
        mob.retaliateAttackCooldown--;
        if (inRange) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            if (mob.retaliateAttackCooldown <= 0) {
                switchToTool(mob, ItemSword.class);
                mob.swingItem();
                target.attackEntityFrom(DamageSource.causePlayerDamage(mob), currentAttackDamage(mob));
                mob.retaliateAttackCooldown = ACTION_HOLD_TICKS;
            }
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }

        recordCombatSample(mob, inRange ? ActionType.ATTACK : ActionType.MOVE_FORWARD);
        return true;
    }

    /**
     * Feeds real combat situations into the neural network as training samples -
     * unlike the ambient self-play cap (maybeRecordSelfSample), this runs regardless
     * of whether real players are online, since combat is rare/valuable signal
     * rather than a continuous filler behavior. BrainDataset's existing 15%
     * self-generated cap still applies, so this can't dominate human-derived data.
     */
    private void recordCombatSample(BotPlayer mob, ActionType action) {
        mob.combatSampleCooldown--;
        if (mob.combatSampleCooldown > 0) return;
        mob.combatSampleCooldown = COMBAT_SAMPLE_INTERVAL_DECISIONS;
        if (!isGoodStateForSelfPlay(mob)) return;

        double[] state = StateEncoder.encode(mob.worldObj, mob);
        BrainManager.instance.recordSelfSample(state, action);
    }

    /**
     * Self-play should only reinforce behavior that's actually going well -
     * per explicit "make it learn from itself, like self-learning" request.
     * Previously every self-play recording point recorded whatever action was
     * taken unconditionally, which risked teaching the network "this was the
     * right move" even while genuinely stuck or about to flee from critical
     * health - not something worth imitating. Deliberately lenient (only
     * excludes clearly-bad moments, not just "took some damage" or "briefly
     * paused"), since self-play is already capped to 15% of the dataset and
     * doesn't need to be squeezed much further.
     */
    private boolean isGoodStateForSelfPlay(BotPlayer mob) {
        if (mob.totalStuckTicks > 0) return false;
        if (mob.getHealth() <= FLEE_HEALTH_THRESHOLD) return false;
        return true;
    }

    /**
     * Dispatches to whichever existing gather behavior the head-of-queue goal
     * needs - deliberately reuses tryGatherWood/tryGatherStone/tryMineOre/
     * tryGetWool/tryHuntFood exactly as-is rather than duplicating their scan/
     * approach/harvest logic, so a goal gets the same hazard-safety checks
     * (isSafeToMine), tool-switching, and stuck-handling as ambient gathering
     * already has. Each of those methods calls BotPlayerManager.onGoalUnitGathered
     * itself at the moment it actually completes a unit of work.
     */
    private boolean tryWorkOnGoal(BotPlayer mob) {
        Goal goal = BotPlayerManager.peekActiveGoal();
        if (goal == null) return false;

        boolean acted;
        switch (goal.type) {
            case WOOD:
                acted = tryGatherWood(mob);
                break;
            case STONE:
                acted = tryGatherStone(mob);
                break;
            case ORE:
                acted = tryMineOre(mob);
                break;
            case WOOL:
                acted = tryGetWool(mob);
                break;
            case FOOD:
                acted = tryHuntFood(mob);
                break;
            default:
                acted = false;
        }
        // Confirmed live: a "gather wood" goal near home could never complete -
        // logs within HOME_PROTECT_RADIUS (100) are off-limits to protect the
        // user's base, but the gather scan itself only reaches GATHER_SCAN_RADIUS
        // (24), so the bot would never find a single valid candidate while
        // anywhere near home, and (since general aimless wandering was removed
        // per explicit request) had no way to ever walk itself out of that
        // trap. A player-assigned goal that can never complete is a real bug,
        // not a feature, so goal-driven work specifically (not ambient
        // gatherOrWander, which is fine standing still) gets a deliberate
        // "walk outward until back in range" fallback instead.
        if (!acted) {
            acted = exploreOutwardForGoal(mob);
        }
        if (acted) {
            recordBehaviorSample(mob);
        }
        return acted;
    }

    /**
     * Walks straight away from home so a goal that can't find anything nearby
     * (most commonly: everything in scan range is inside the protected
     * HOME_PROTECT_RADIUS) eventually gets far enough for the underlying
     * findNearbyX scan to succeed on its own. Stops offering movement once
     * comfortably past the protected radius, letting the normal gather logic
     * take back over from there rather than marching indefinitely outward.
     */
    private boolean exploreOutwardForGoal(BotPlayer mob) {
        double dx = mob.posX - BotPlayerManager.HOME_X;
        double dz = mob.posZ - BotPlayerManager.HOME_Z;
        double clearRadius = HOME_PROTECT_RADIUS + GATHER_SCAN_RADIUS;
        if (dx * dx + dz * dz > clearRadius * clearRadius) {
            return false;
        }

        float yaw = (dx * dx + dz * dz < 1.0) ? mob.worldObj.rand.nextFloat() * 360.0F
                : (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);
        mob.moveStrafing = 0.0F;
        mob.moveForward = MOVE_SPEED;
        return true;
    }

    /**
     * Feeds every scripted behavior's active time into the network, not just
     * goals/combat - per explicit "make him learn everything and feed the
     * network" request. Previously ambient gatherOrWander behaviors (building,
     * wood/stone/ore/wool/food, farming, chest-looting) contributed nothing:
     * their entry point is always an NN-predicted IDLE decision, and
     * maybeRecordSelfSample deliberately skips recording IDLE (recording "IDLE
     * was correct" every time it already leans IDLE just reinforces that bias
     * circularly - the actual root cause of an earlier bug where the network
     * predicted IDLE ~97% of the time). So all the real, specific actions
     * gatherOrWander actually takes under the hood - walking to a target,
     * swinging a tool, attacking - were invisible to training. This one helper
     * (already proven out for goal-work and reused verbatim here) infers the
     * action label the same way the underlying tryX methods already signal it
     * internally: moveForward > 0 means still approaching the target, 0 means
     * it's stopped and mining/attacking/interacting.
     */
    private void recordBehaviorSample(BotPlayer mob) {
        mob.behaviorSampleCooldown--;
        if (mob.behaviorSampleCooldown > 0) return;
        mob.behaviorSampleCooldown = COMBAT_SAMPLE_INTERVAL_DECISIONS;
        if (!isGoodStateForSelfPlay(mob)) return;

        double[] state = StateEncoder.encode(mob.worldObj, mob);
        ActionType action = mob.moveForward > 0.0F ? ActionType.MOVE_FORWARD : ActionType.MINE_BLOCK;
        BrainManager.instance.recordSelfSample(state, action);
    }

    /**
     * If the bot has wandered past LEASH_RADIUS from its anchor point, walk
     * straight back toward it (no pathfinding, same straight-line limitation as
     * the rest of this class' movement). Returns true while homing is active.
     *
     * The anchor is its own constructed base once that's done (per explicit
     * "he stays there and lives there" request - the base is genuinely home
     * once built, not just a side project), falling back to the original
     * HOME_X/Z anchor before then since the base location isn't chosen/built yet.
     *
     * No leash at all while nobody real is online - it can roam as far as it wants.
     * The instant a real player is online, this resumes pulling it back within
     * LEASH_RADIUS like normal ("if I'm online we train" - stay close together).
     */
    private boolean tryReturnHome(BotPlayer mob) {
        if (mob.dimension != BotPlayerManager.HOME_DIMENSION) return false;
        if (!isAnyRealPlayerOnline()) return false;
        if (mob.pursuitSuppressedTicks > 0) return false;
        // Building its own base is a legitimate, intentional 100-block trip from
        // home - the leash must not fight that journey (it has higher priority
        // than tryBuildBase, so without this the bot would get yanked back before
        // ever getting close enough to start). Normal leash behavior resumes once
        // the base (walls + bed + chest) is actually finished.
        if (!BotPlayerManager.isBaseChestPlaced()) return false;

        double anchorX = BotPlayerManager.getBaseX();
        double anchorZ = BotPlayerManager.getBaseZ();
        double dx = anchorX - mob.posX;
        double dz = anchorZ - mob.posZ;
        if (dx * dx + dz * dz <= LEASH_RADIUS * LEASH_RADIUS) return false;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);
        mob.moveStrafing = 0.0F;
        mob.moveForward = MOVE_SPEED;
        return true;
    }

    /** /brain home - walks toward home unconditionally (ignores the leash's usual "only with a real player online"/base-building exemptions, since this is an explicit direct request) until within a few blocks, then stops on its own. */
    private boolean tryForcedGoHome(BotPlayer mob) {
        if (mob.dimension != BotPlayerManager.HOME_DIMENSION) {
            mob.forcedGoingHome = false;
            return false;
        }

        double dx = BotPlayerManager.HOME_X - mob.posX;
        double dz = BotPlayerManager.HOME_Z - mob.posZ;
        if (dx * dx + dz * dz <= FOLLOW_STOP_DISTANCE * FOLLOW_STOP_DISTANCE) {
            mob.forcedGoingHome = false;
            return false;
        }

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);
        mob.moveStrafing = 0.0F;
        mob.moveForward = MOVE_SPEED;
        return true;
    }

    private boolean isAnyRealPlayerOnline() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        return server != null && BotPlayerManager.hasRealPlayerOnline(server);
    }

    /**
     * Seeks out and fights nearby hostile mobs (vanilla's IMob interface - zombies,
     * skeletons, spiders, creepers, etc.) or anything on the grudge list, like a
     * real player would rather than only ever reacting to being hit first.
     * Never targets real players - see tryRetaliate's grudge comment.
     */
    private boolean trySeekAndKillMob(BotPlayer mob) {
        if (mob.pursuitSuppressedTicks > 0) return false;

        EntityLivingBase target = findNearestHostileMob(mob);
        if (target == null) return false;

        double dx = target.posX - mob.posX;
        double dz = target.posZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        mob.retaliateAttackCooldown--;
        if (distSq <= RETALIATE_ATTACK_RANGE * RETALIATE_ATTACK_RANGE) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            if (mob.retaliateAttackCooldown <= 0) {
                switchToTool(mob, ItemSword.class);
                mob.swingItem();
                target.attackEntityFrom(DamageSource.causePlayerDamage(mob), currentAttackDamage(mob));
                mob.retaliateAttackCooldown = ACTION_HOLD_TICKS;
            }
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private EntityLivingBase findNearestHostileMob(BotPlayer mob) {
        @SuppressWarnings("unchecked")
        List<EntityLivingBase> nearby = mob.worldObj.getEntitiesWithinAABB(EntityLivingBase.class,
                mob.boundingBox.expand(MOB_SEEK_RADIUS, MOB_SEEK_RADIUS / 2.0, MOB_SEEK_RADIUS));

        EntityLivingBase best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityLivingBase e : nearby) {
            if (!e.isEntityAlive()) continue;
            boolean hostile = e instanceof IMob || mob.grudgeEntityIds.contains(e.getUniqueID());
            if (!hostile) continue;

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

    /**
     * Best-effort hazard check ahead of the bot (feet and head level). Since there's
     * no real pathfinding (see class doc), this can't route cleanly around a hazard
     * that an active target (follow/retaliate/seek-mob/return-home) sits behind or
     * across - those will keep turning back toward their target each tick, so it
     * may hover/oscillate at the edge rather than cleanly avoiding it. It does
     * reliably stop the ambient wander/gather-wood behaviors from walking in.
     */
    private boolean isHazardAhead(BotPlayer mob) {
        ChunkCoordinates ahead = getBlockAheadFeet(mob);
        Block feet = mob.worldObj.getBlock(ahead.posX, ahead.posY, ahead.posZ);
        Block head = mob.worldObj.getBlock(ahead.posX, ahead.posY + 1, ahead.posZ);
        return isHazardousBlock(feet) || isHazardousBlock(head);
    }

    /**
     * Known-dangerous vanilla materials, plus a keyword heuristic for modded
     * hazards (Thaumcraft wards/taint, Blood Magic rituals, PneumaticCraft/TNT-like
     * explosives) since none of those mods' actual Block classes are on this
     * project's compile classpath - same reasoning as isLog()'s heuristic. Not
     * exhaustive; this is a best-effort filter, not a verified blocklist.
     *
     * Material.isLiquid() (rather than singling out Material.lava) covers water,
     * lava, and any modded fluid uniformly per the user's explicit request to stay
     * away from "water and lava and other fluids".
     */
    private boolean isHazardousBlock(Block block) {
        Material m = block.getMaterial();
        if (m.isLiquid() || m == Material.fire || m == Material.tnt || m == Material.cactus) {
            return true;
        }
        String name = block.getUnlocalizedName().toLowerCase();
        return name.contains("trap") || name.contains("mine") || name.contains("ward")
                || name.contains("taint") || name.contains("flux") || name.contains("rift")
                || name.contains("explos");
    }

    /** The block the bot is facing at foot level, one block ahead - used for door detection/awareness. */
    private ChunkCoordinates getBlockAheadFeet(BotPlayer mob) {
        double yawRad = Math.toRadians(mob.rotationYaw);
        int x = MathHelper.floor_double(mob.posX - Math.sin(yawRad));
        int y = MathHelper.floor_double(mob.posY);
        int z = MathHelper.floor_double(mob.posZ + Math.cos(yawRad));
        return new ChunkCoordinates(x, y, z);
    }

    /** Opens a closed wooden door directly ahead so it doesn't just walk into it. Iron doors need redstone, can't hand-open. */
    private void tryHandleDoors(BotPlayer mob) {
        ChunkCoordinates ahead = getBlockAheadFeet(mob);
        Block block = mob.worldObj.getBlock(ahead.posX, ahead.posY, ahead.posZ);
        if (!(block instanceof BlockDoor) || block.getMaterial() == Material.iron) return;

        BlockDoor door = (BlockDoor) block;
        int combined = door.func_150012_g(mob.worldObj, ahead.posX, ahead.posY, ahead.posZ);
        boolean isOpen = (combined & 4) != 0;
        if (!isOpen) {
            door.func_150014_a(mob.worldObj, ahead.posX, ahead.posY, ahead.posZ, true);
            mob.hasOpenedDoor = true;
            mob.openedDoorX = ahead.posX;
            mob.openedDoorY = ahead.posY;
            mob.openedDoorZ = ahead.posZ;
        }
    }

    /** Closes a door it opened once it's walked far enough away, like a real player would behind them. */
    private void maybeCloseDoor(BotPlayer mob) {
        if (!mob.hasOpenedDoor) return;

        double dx = mob.openedDoorX + 0.5 - mob.posX;
        double dz = mob.openedDoorZ + 0.5 - mob.posZ;
        if (dx * dx + dz * dz < DOOR_CLOSE_DISTANCE * DOOR_CLOSE_DISTANCE) return;

        Block block = mob.worldObj.getBlock(mob.openedDoorX, mob.openedDoorY, mob.openedDoorZ);
        if (block instanceof BlockDoor) {
            ((BlockDoor) block).func_150014_a(mob.worldObj, mob.openedDoorX, mob.openedDoorY, mob.openedDoorZ, false);
        }
        mob.hasOpenedDoor = false;
    }

    /** Eats from its own inventory when hungry - EAT_FOOD_THRESHOLD (18) matches vanilla's own natural-regen threshold, so this is also what enables healing. */
    /**
     * Real eating now, not instant - switches to the food (so it's actually
     * visible in hand) and starts vanilla's own item-use state
     * (setItemInUse), which EntityPlayer.onUpdate() already ticks down and
     * completes on its own (confirmed live-reachable ever since the
     * onUpdateEntity() driver fix earlier in this project - same mechanism
     * real players use for the ~1.6s eating animation/sound, followed by
     * vanilla itself applying the food value and consuming the item -
     * no manual onEaten()/inventory bookkeeping needed here anymore).
     */
    private void tryEat(BotPlayer mob) {
        if (mob.isUsingItem()) return; // already mid-animation - let it finish naturally, don't re-trigger

        mob.eatCheckCooldown--;
        if (mob.eatCheckCooldown > 0) return;
        mob.eatCheckCooldown = EAT_CHECK_INTERVAL_TICKS;

        if (mob.getFoodStats().getFoodLevel() >= EAT_FOOD_THRESHOLD) return;

        ItemStack[] inventory = mob.inventory.mainInventory;
        for (int i = 0; i < inventory.length; i++) {
            ItemStack stack = inventory[i];
            if (stack != null && stack.getItem() instanceof ItemFood) {
                mob.inventory.currentItem = i;
                ItemFood food = (ItemFood) stack.getItem();
                mob.setItemInUse(stack, food.getMaxItemUseDuration(stack));
                return;
            }
        }
    }

    /**
     * /brain copy - mirrors the target player's own movement/look/jump inputs onto
     * the bot verbatim instead of the NN, tick for tick. Scoped to movement only
     * (no mining/attacking mirrored) - keeping it simple and not dependent on the
     * bot actually being colocated with the player it's copying.
     */
    private boolean tryCopy(BotPlayer mob) {
        if (mob.copyPlayerName == null) return false;
        EntityPlayer target = resolvePlayer(mob.copyPlayerName);
        if (target == null) return false;

        mob.rotationYaw = target.rotationYaw;
        mob.rotationYawHead = target.rotationYaw;
        mob.rotationPitch = target.rotationPitch;
        mob.moveForward = target.moveForward;
        mob.moveStrafing = target.moveStrafing;
        // EntityLivingBase.isJumping is protected (no public getter) - approximate
        // "target just jumped" from a burst of upward velocity instead.
        if (mob.onGround && target.motionY > 0.1) {
            mob.setJumping(true);
        }
        return true;
    }

    /** /brain follow <player> - walks toward the target, stopping a few blocks short. */
    private boolean tryFollow(BotPlayer mob) {
        if (mob.followPlayerName == null) return false;
        if (mob.pursuitSuppressedTicks > 0) return false;
        EntityPlayer target = resolvePlayer(mob.followPlayerName);
        if (target == null || target.worldObj != mob.worldObj) return false;

        double dx = target.posX - mob.posX;
        double dz = target.posZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= FOLLOW_STOP_DISTANCE * FOLLOW_STOP_DISTANCE) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    /**
     * There's no real pathfinding in this mod - movement is always "turn toward
     * target, walk straight at it." This is the fallback for when that straight
     * line runs into something: if the bot meant to move but its actual position
     * barely changed since last tick, try jumping (clears 1-block ledges/stairs);
     * if that keeps failing, turn away and abandon whatever target it was walking
     * toward so the next tick's logic picks a different one/direction.
     */
    private void handleStuck(BotPlayer mob) {
        if (mob.pursuitSuppressedTicks > 0) {
            mob.pursuitSuppressedTicks--;
        }

        double dx = mob.posX - mob.lastPosX;
        double dz = mob.posZ - mob.lastPosZ;
        boolean triedToMove = mob.moveForward != 0.0F || mob.moveStrafing != 0.0F;
        boolean barelyMoved = dx * dx + dz * dz < STUCK_MOVE_EPSILON * STUCK_MOVE_EPSILON;
        mob.lastPosX = mob.posX;
        mob.lastPosZ = mob.posZ;

        // Tracked independently of everything below (including the hazard branch,
        // which resets stuckTicks every time it fires) - confirmed live as a real
        // bug: a bot stuck facing a hazard on every tick never once reached the
        // jump/break/reroute thresholds below, because the hazard branch kept
        // resetting the counter it depends on before it could accumulate. This is
        // the guaranteed last resort once NOTHING else has resolved it for a
        // genuinely long time (10s), regardless of why.
        if (triedToMove && barelyMoved) {
            mob.totalStuckTicks++;
        } else {
            mob.totalStuckTicks = 0;
        }
        if (mob.totalStuckTicks >= ULTIMATE_STUCK_TICKS) {
            forceUnstuck(mob);
            mob.totalStuckTicks = 0;
            mob.stuckTicks = 0;
            return;
        }

        // Checked next: don't accumulate stuckTicks while touching a hazard, react
        // to it immediately instead.
        if (isHazardAhead(mob)) {
            mob.rotationYaw += 90.0F + mob.worldObj.rand.nextFloat() * 90.0F;
            mob.moveForward = 0.0F;
            mob.moveStrafing = 0.0F;
            mob.hasGatherTarget = false;
            mob.stuckTicks = 0;
            // Give the turn-away an actual chance to work: without this, seek-mob/
            // follow/return-home would immediately turn straight back toward their
            // target next tick, forever - confirmed live as a 4+ minute stuck loop.
            mob.pursuitSuppressedTicks = PURSUIT_SUPPRESS_TICKS;
            return;
        }

        if (triedToMove && barelyMoved) {
            mob.stuckTicks++;
        } else {
            mob.stuckTicks = 0;
            return;
        }

        if (mob.stuckTicks >= STUCK_JUMP_THRESHOLD_TICKS && mob.onGround) {
            mob.setJumping(true);
        }

        if (mob.stuckTicks == STUCK_BREAK_THRESHOLD_TICKS) {
            tryBreakBlockingBlock(mob);
        }

        if (mob.stuckTicks >= STUCK_REROUTE_THRESHOLD_TICKS) {
            mob.rotationYaw += 90.0F + mob.worldObj.rand.nextFloat() * 90.0F;
            mob.hasGatherTarget = false;
            mob.stuckTicks = 0;
            mob.pursuitSuppressedTicks = PURSUIT_SUPPRESS_TICKS;
        }
    }

    /**
     * Absolute last resort once nothing else has worked for ULTIMATE_STUCK_TICKS
     * straight: teleport a short random distance instead of continuing to
     * oscillate forever. Abandons every active target so the next tick picks
     * something fresh rather than immediately walking straight back into
     * whatever it was stuck on.
     */
    private void forceUnstuck(BotPlayer mob) {
        double angle = mob.worldObj.rand.nextDouble() * Math.PI * 2.0;
        double distance = 4.0 + mob.worldObj.rand.nextDouble() * 4.0;
        double newX = mob.posX + Math.cos(angle) * distance;
        double newZ = mob.posZ + Math.sin(angle) * distance;
        int groundY = findGroundY(mob.worldObj, MathHelper.floor_double(newX), MathHelper.floor_double(mob.posY), MathHelper.floor_double(newZ));
        double newY = groundY == Integer.MIN_VALUE ? mob.posY : groundY + 1;
        mob.setPosition(newX, newY, newZ);

        mob.hasGatherTarget = false;
        mob.hasStoneTarget = false;
        mob.hasOreTarget = false;
        mob.hasLootTarget = false;
        mob.hasFarmTarget = false;
    }

    /**
     * Mines through whatever's directly blocking the bot's path (both foot and
     * head level, so it can actually walk through afterward) instead of only
     * ever jumping or giving up and turning away - useful any time it's headed
     * somewhere specific (a wall-block placement spot, a gather/loot target) and
     * a solid block just happens to be in the way. Same rules as mineAhead/
     * tryMineOre: never breaks a hazardous block, and ore specifically still
     * requires a diamond pickaxe - if that's not held, this just leaves the ore
     * alone and lets the existing jump/reroute fallbacks handle it instead.
     */
    private void tryBreakBlockingBlock(BotPlayer mob) {
        ChunkCoordinates ahead = getBlockAheadFeet(mob);
        if (isProtectedFromDigging(mob, ahead.posX, ahead.posY, ahead.posZ)) return;
        breakIfBreakable(mob, ahead.posX, ahead.posY, ahead.posZ);
        breakIfBreakable(mob, ahead.posX, ahead.posY + 1, ahead.posZ);
    }

    /**
     * Never dig through a wall while "just clearing a path" - this must not
     * apply to the bot's own actual construction (placeNextWallBlock etc. never
     * call this, they only place into open gaps), only to the reactive
     * obstacle-breaking above. The hardcoded HOME_X/Z (the user's own real base)
     * is protected out to the full HOME_PROTECT_RADIUS (100 blocks) per explicit
     * request - previously this only checked the much tighter DIG_PROTECT_RADIUS
     * (15) here, so the bot could still break blocks anywhere from 15-100 blocks
     * out from home. The bot's own constructed base still only needs the tighter
     * DIG_PROTECT_RADIUS since it's a small 5x5 structure, not a whole base area.
     * Also uses a nearby bed/chest as a best-effort heuristic for "this is
     * probably someone else's base" - imperfect (no real per-block ownership
     * exists to check against in vanilla Minecraft), but reasonable given the
     * alternative is no protection at all for other players' builds.
     */
    private boolean isProtectedFromDigging(BotPlayer mob, int x, int y, int z) {
        double hdx = x + 0.5 - BotPlayerManager.HOME_X;
        double hdz = z + 0.5 - BotPlayerManager.HOME_Z;
        if (hdx * hdx + hdz * hdz <= HOME_PROTECT_RADIUS * HOME_PROTECT_RADIUS) return true;

        double bdx = x + 0.5 - BotPlayerManager.getBaseX();
        double bdz = z + 0.5 - BotPlayerManager.getBaseZ();
        if (bdx * bdx + bdz * bdz <= DIG_PROTECT_RADIUS * DIG_PROTECT_RADIUS) return true;

        World world = mob.worldObj;
        int radius = (int) STRUCTURE_PROTECT_RADIUS;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    int cx = x + dx;
                    int cy = y + dy;
                    int cz = z + dz;
                    if (world.getBlock(cx, cy, cz) == Blocks.bed) return true;
                    if (world.getTileEntity(cx, cy, cz) instanceof IInventory) return true;
                }
            }
        }
        return false;
    }

    private void breakIfBreakable(BotPlayer mob, int x, int y, int z) {
        Block block = mob.worldObj.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) return;
        if (isHazardousBlock(block)) return;

        if (isOre(block)) {
            if (switchToItem(mob, Items.diamond_pickaxe) == null) return;
        } else {
            int meta = mob.worldObj.getBlockMetadata(x, y, z);
            Class<? extends Item> toolClass = harvestToolClassFor(block, meta);
            if (toolClass != null) {
                switchToTool(mob, toolClass);
            }
        }
        mob.swingItem();
        mob.worldObj.func_147480_a(x, y, z, true);
    }

    /**
     * Fallback for IDLE decisions: look for a nearby tree to chop or chest to loot
     * like a real player gathering resources, and only wander aimlessly if neither
     * is in range.
     */
    private void gatherOrWander(BotPlayer mob) {
        // Hunger and base-building are no longer checked here - both are now
        // their own top-level priority tiers in tick() (above this whole
        // NN-decision path), so by the time gatherOrWander ever runs, hunger
        // is already handled and the base (if not yet built) has already had
        // its turn this tick. See tick()'s tryHuntFood/tryBuildBase tiers for
        // why - the short version is that leaving them buried in here (only
        // reachable on an IDLE prediction) meant the network's own non-IDLE
        // guesses could starve both indefinitely.
        if (tryGatherWood(mob)) {
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }
        if (tryGatherStone(mob)) {
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }
        if (tryMineOre(mob)) {
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }
        if (tryGetWool(mob)) {
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }
        if (tryLootChest(mob)) {
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }
        if (tryFarm(mob)) {
            applyNnMovementFlourish(mob);
            recordBehaviorSample(mob);
            return;
        }
        // Nothing left to do this tick - per explicit "stop wandering" request,
        // just stand still instead of picking a random nearby point to walk to.
        // moveForward/moveStrafing are already 0 here (reset at the top of
        // execute() before this method runs).
    }

    /**
     * Only does anything once the bot has a hoe - tills the nearest grass/dirt
     * block (with clear air above, same condition vanilla's ItemHoe.onItemUse
     * checks) into farmland, then plants a wheat seed on top if it's carrying
     * any. Same scan/approach/act pattern as tryGatherWood/tryLootChest above.
     * Deliberately not home-protected like wood/chests - tilling plain dirt or
     * grass doesn't destroy anything the player built, unlike chopping a log
     * that might be part of their house.
     */
    private boolean tryFarm(BotPlayer mob) {
        if (switchToTool(mob, ItemHoe.class) == null) return false;

        if (mob.hasFarmTarget) {
            Block current = mob.worldObj.getBlock(
                    MathHelper.floor_double(mob.farmTargetX), MathHelper.floor_double(mob.farmTargetY), MathHelper.floor_double(mob.farmTargetZ));
            if (!isTillable(current)) {
                mob.hasFarmTarget = false;
            }
        }

        if (!mob.hasFarmTarget) {
            mob.farmRescanCooldown--;
            if (mob.farmRescanCooldown > 0) return false;
            mob.farmRescanCooldown = GATHER_RESCAN_INTERVAL_TICKS;

            ChunkCoordinates spot = findNearbyTillable(mob);
            if (spot == null) return false;

            mob.hasFarmTarget = true;
            mob.farmTargetX = spot.posX + 0.5;
            mob.farmTargetY = spot.posY;
            mob.farmTargetZ = spot.posZ + 0.5;
        }

        double dx = mob.farmTargetX - mob.posX;
        double dz = mob.farmTargetZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;

            int x = MathHelper.floor_double(mob.farmTargetX);
            int y = MathHelper.floor_double(mob.farmTargetY);
            int z = MathHelper.floor_double(mob.farmTargetZ);
            mob.worldObj.setBlock(x, y, z, Blocks.farmland);

            ItemStack seeds = findItemStack(mob, Items.wheat_seeds);
            if (seeds != null && mob.worldObj.getBlock(x, y + 1, z).getMaterial() == Material.air) {
                mob.worldObj.setBlock(x, y + 1, z, Blocks.wheat);
                seeds.stackSize--;
                if (seeds.stackSize <= 0) {
                    for (int i = 0; i < mob.inventory.mainInventory.length; i++) {
                        if (mob.inventory.mainInventory[i] == seeds) {
                            mob.inventory.mainInventory[i] = null;
                            break;
                        }
                    }
                }
            }
            mob.hasFarmTarget = false;
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private boolean isTillable(Block block) {
        return block == Blocks.grass || block == Blocks.dirt;
    }

    private ChunkCoordinates findNearbyTillable(BotPlayer mob) {
        World world = mob.worldObj;
        int cx = MathHelper.floor_double(mob.posX);
        int cy = MathHelper.floor_double(mob.posY);
        int cz = MathHelper.floor_double(mob.posZ);
        int radius = (int) GATHER_SCAN_RADIUS;

        ChunkCoordinates best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (isTillable(world.getBlock(x, y, z)) && world.getBlock(x, y + 1, z).getMaterial() == Material.air) {
                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new ChunkCoordinates(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    private boolean tryGatherWood(BotPlayer mob) {
        if (mob.hasGatherTarget) {
            int tx = MathHelper.floor_double(mob.gatherTargetX);
            int ty = MathHelper.floor_double(mob.gatherTargetY);
            int tz = MathHelper.floor_double(mob.gatherTargetZ);
            if (!isLog(mob.worldObj.getBlock(tx, ty, tz))) {
                mob.hasGatherTarget = false;
            }
        }

        if (!mob.hasGatherTarget) {
            mob.gatherRescanCooldown--;
            if (mob.gatherRescanCooldown > 0) return false;
            mob.gatherRescanCooldown = GATHER_RESCAN_INTERVAL_TICKS;

            ChunkCoordinates log = findNearbyLog(mob);
            if (log == null) return false;

            mob.hasGatherTarget = true;
            mob.gatherTargetX = log.posX + 0.5;
            mob.gatherTargetY = log.posY;
            mob.gatherTargetZ = log.posZ + 0.5;
        }

        double dx = mob.gatherTargetX - mob.posX;
        double dy = mob.gatherTargetY - mob.posY;
        double dz = mob.gatherTargetZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH && Math.abs(dy) <= 4.0) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            switchToTool(mob, ItemAxe.class);
            mob.swingItem();
            // Instant break, same simplification mineAhead() already uses elsewhere
            // in this class - no partial-mining-progress system exists.
            mob.worldObj.func_147480_a(
                    MathHelper.floor_double(mob.gatherTargetX),
                    MathHelper.floor_double(mob.gatherTargetY),
                    MathHelper.floor_double(mob.gatherTargetZ), true);
            mob.hasGatherTarget = false;
            BotPlayerManager.onGoalUnitGathered(GoalType.WOOD);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    /**
     * Proactively seeks out and mines nearby ore, rather than only mining whatever
     * mineAhead() happens to be facing - "he really mines for ores" per the user's
     * request. Same scan/approach/act pattern as tryGatherWood, but with a much
     * deeper vertical scan range since ore is typically underground, not at eye
     * level. Auto-pickup of the drop into the bot's inventory needs no extra code
     * here - it's a real EntityPlayerMP standing right next to the drop, and
     * vanilla's own EntityItem proximity pickup already handles that for any
     * nearby player, same as it always has for mineAhead()/tryGatherWood().
     *
     * User explicitly required a diamond pickaxe specifically for ore (not just
     * any pickaxe) - gated at the top so the bot won't even go looking for ore
     * until it actually has one.
     */
    private boolean tryMineOre(BotPlayer mob) {
        if (findItemStack(mob, Items.diamond_pickaxe) == null) return false;

        if (mob.hasOreTarget) {
            int tx = MathHelper.floor_double(mob.oreTargetX);
            int ty = MathHelper.floor_double(mob.oreTargetY);
            int tz = MathHelper.floor_double(mob.oreTargetZ);
            // Re-checked every tick, not just when first picked - covers terrain
            // changing after the target was chosen (e.g. lava spreading nearby).
            if (!isOre(mob.worldObj.getBlock(tx, ty, tz)) || !isSafeToMine(mob.worldObj, tx, ty, tz)) {
                mob.hasOreTarget = false;
            }
        }

        if (!mob.hasOreTarget) {
            mob.oreRescanCooldown--;
            if (mob.oreRescanCooldown > 0) return false;
            mob.oreRescanCooldown = GATHER_RESCAN_INTERVAL_TICKS;

            ChunkCoordinates ore = findNearbyOre(mob);
            if (ore == null) return false;

            mob.hasOreTarget = true;
            mob.oreTargetX = ore.posX + 0.5;
            mob.oreTargetY = ore.posY;
            mob.oreTargetZ = ore.posZ + 0.5;
        }

        double dx = mob.oreTargetX - mob.posX;
        double dy = mob.oreTargetY - mob.posY;
        double dz = mob.oreTargetZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH && Math.abs(dy) <= 4.0) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;

            int x = MathHelper.floor_double(mob.oreTargetX);
            int y = MathHelper.floor_double(mob.oreTargetY);
            int z = MathHelper.floor_double(mob.oreTargetZ);
            switchToItem(mob, Items.diamond_pickaxe);
            mob.swingItem();
            mob.worldObj.func_147480_a(x, y, z, true);
            mob.hasOreTarget = false;
            BotPlayerManager.onGoalUnitGathered(GoalType.ORE);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private ChunkCoordinates findNearbyOre(BotPlayer mob) {
        World world = mob.worldObj;
        int cx = MathHelper.floor_double(mob.posX);
        int cy = MathHelper.floor_double(mob.posY);
        int cz = MathHelper.floor_double(mob.posZ);
        int radius = (int) ORE_SCAN_RADIUS;

        ChunkCoordinates best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -ORE_SCAN_DOWN; dy <= ORE_SCAN_UP; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (isOre(world.getBlock(x, y, z)) && !isNearHome(x, z) && isSafeToMine(world, x, y, z)) {
                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new ChunkCoordinates(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Checks the 6 blocks directly adjacent to a mining target for hazards
     * (lava especially) before ever selecting or breaking it - per explicit
     * user request that mining "also use /brain scan" (i.e. look at what's
     * actually around it, not just blindly swing) - valuable ore commonly
     * generates right next to lava pools, and nothing previously checked for
     * that before committing to break through.
     */
    private boolean isSafeToMine(World world, int x, int y, int z) {
        return !isHazardousBlock(world.getBlock(x + 1, y, z))
                && !isHazardousBlock(world.getBlock(x - 1, y, z))
                && !isHazardousBlock(world.getBlock(x, y + 1, z))
                && !isHazardousBlock(world.getBlock(x, y - 1, z))
                && !isHazardousBlock(world.getBlock(x, y, z + 1))
                && !isHazardousBlock(world.getBlock(x, y, z - 1));
    }

    /** Vanilla ore blocks match directly; modded ores (not on this project's compile classpath) match by the same "ore" name-convention heuristic isLog() uses for modded trees. */
    private boolean isOre(Block block) {
        if (block == Blocks.coal_ore || block == Blocks.iron_ore || block == Blocks.gold_ore
                || block == Blocks.diamond_ore || block == Blocks.redstone_ore || block == Blocks.lit_redstone_ore
                || block == Blocks.lapis_ore || block == Blocks.emerald_ore || block == Blocks.quartz_ore) {
            return true;
        }
        return block.getUnlocalizedName().toLowerCase().contains("ore");
    }

    /** Recognizes an inventory item as an ore drop - either the ore block itself (iron/gold ore drop as their own block) or a direct-drop item (coal/diamond/emerald/redstone/lapis dye/quartz). */
    private boolean isOreItem(ItemStack stack) {
        Item item = stack.getItem();
        if (item == Items.coal || item == Items.diamond || item == Items.emerald
                || item == Items.redstone || item == Items.quartz) {
            return true;
        }
        if (item == Items.dye && stack.getItemDamage() == 4) {
            return true; // lapis lazuli
        }
        Block block = Block.getBlockFromItem(item);
        if (block != Blocks.air && isOre(block)) {
            return true;
        }
        return item.getUnlocalizedName().toLowerCase().contains("ore");
    }

    private boolean hasOre(BotPlayer mob) {
        for (ItemStack stack : mob.inventory.mainInventory) {
            if (stack != null && isOreItem(stack)) return true;
        }
        return false;
    }

    /**
     * Once carrying ore, walks it home and dumps it into whatever chest/IInventory
     * is nearest the hardcoded home coordinate (within ORE_HOME_DEPOSIT_RADIUS) -
     * "put ore in the home chest" per the user's request. If no such chest exists
     * near home, this just never triggers and the ore stays in the bot's own
     * inventory (harmless - it isn't lost, just not auto-sorted).
     */
    private boolean tryDepositOre(BotPlayer mob) {
        if (mob.dimension != BotPlayerManager.HOME_DIMENSION) return false;
        if (!hasOre(mob)) return false;
        if (mob.pursuitSuppressedTicks > 0) return false;

        ChunkCoordinates chest = findHomeChest(mob);
        if (chest == null) return false;

        double targetX = chest.posX + 0.5;
        double targetZ = chest.posZ + 0.5;
        double dx = targetX - mob.posX;
        double dy = chest.posY - mob.posY;
        double dz = targetZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH && Math.abs(dy) <= 4.0) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            depositOreInto(mob, chest.posX, chest.posY, chest.posZ);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private void depositOreInto(BotPlayer mob, int x, int y, int z) {
        TileEntity te = mob.worldObj.getTileEntity(x, y, z);
        if (!(te instanceof IInventory)) return;
        IInventory inv = (IInventory) te;

        ItemStack[] main = mob.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            if (main[i] == null || !isOreItem(main[i])) continue;

            for (int slot = 0; slot < inv.getSizeInventory() && main[i] != null && main[i].stackSize > 0; slot++) {
                ItemStack existing = inv.getStackInSlot(slot);
                if (existing == null) {
                    inv.setInventorySlotContents(slot, main[i]);
                    main[i] = null;
                } else if (existing.isItemEqual(main[i]) && ItemStack.areItemStackTagsEqual(existing, main[i])
                        && existing.stackSize < existing.getMaxStackSize()) {
                    int room = existing.getMaxStackSize() - existing.stackSize;
                    int move = Math.min(room, main[i].stackSize);
                    existing.stackSize += move;
                    main[i].stackSize -= move;
                    if (main[i].stackSize <= 0) main[i] = null;
                }
            }
        }
    }

    private ChunkCoordinates findHomeChest(BotPlayer mob) {
        World world = mob.worldObj;
        int hx = MathHelper.floor_double(BotPlayerManager.HOME_X);
        int hy = MathHelper.floor_double(BotPlayerManager.HOME_Y);
        int hz = MathHelper.floor_double(BotPlayerManager.HOME_Z);
        int radius = (int) ORE_HOME_DEPOSIT_RADIUS;

        ChunkCoordinates best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    int x = hx + dx;
                    int y = hy + dy;
                    int z = hz + dz;
                    if (world.getTileEntity(x, y, z) instanceof IInventory) {
                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new ChunkCoordinates(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Mines plain stone (for cobblestone - building material) - only while below
     * COBBLESTONE_TARGET, so it doesn't mine forever once it has enough for the
     * base. Same scan/approach/act pattern as tryGatherWood/tryMineOre, but no
     * diamond-pickaxe requirement (that's specific to ore per the user's request) -
     * any pickaxe works via the existing harvestToolClassFor selection.
     */
    private boolean tryGatherStone(BotPlayer mob) {
        // Stone requires a pickaxe to actually drop cobblestone in vanilla -
        // without this gate the bot would "mine" stone forever using whatever
        // was currently held (a sword, bare hands) and get nothing for it, same
        // class of bug tryMineOre/tryFarm already guard against for their tools.
        if (switchToTool(mob, ItemPickaxe.class) == null) return false;

        // A queued "gather stone" goal can ask for more than the base ever needs -
        // widen the cap to the goal's target so it doesn't silently stall at
        // COBBLESTONE_TARGET while a player is waiting on a bigger request.
        int stoneCap = COBBLESTONE_TARGET;
        Goal activeGoal = BotPlayerManager.peekActiveGoal();
        if (activeGoal != null && activeGoal.type == GoalType.STONE) {
            stoneCap = Math.max(stoneCap, activeGoal.targetAmount);
        }
        if (countItemOfBlock(mob, Blocks.cobblestone) >= stoneCap) return false;

        if (mob.hasStoneTarget) {
            int tx = MathHelper.floor_double(mob.stoneTargetX);
            int ty = MathHelper.floor_double(mob.stoneTargetY);
            int tz = MathHelper.floor_double(mob.stoneTargetZ);
            if (mob.worldObj.getBlock(tx, ty, tz) != Blocks.stone) {
                mob.hasStoneTarget = false;
            }
        }

        if (!mob.hasStoneTarget) {
            mob.stoneRescanCooldown--;
            if (mob.stoneRescanCooldown > 0) return false;
            mob.stoneRescanCooldown = GATHER_RESCAN_INTERVAL_TICKS;

            ChunkCoordinates stone = findNearbyStone(mob);
            if (stone == null) return false;

            mob.hasStoneTarget = true;
            mob.stoneTargetX = stone.posX + 0.5;
            mob.stoneTargetY = stone.posY;
            mob.stoneTargetZ = stone.posZ + 0.5;
        }

        double dx = mob.stoneTargetX - mob.posX;
        double dy = mob.stoneTargetY - mob.posY;
        double dz = mob.stoneTargetZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH && Math.abs(dy) <= 4.0) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            switchToTool(mob, ItemPickaxe.class);
            mob.swingItem();
            mob.worldObj.func_147480_a(
                    MathHelper.floor_double(mob.stoneTargetX),
                    MathHelper.floor_double(mob.stoneTargetY),
                    MathHelper.floor_double(mob.stoneTargetZ), true);
            mob.hasStoneTarget = false;
            BotPlayerManager.onGoalUnitGathered(GoalType.STONE);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private ChunkCoordinates findNearbyStone(BotPlayer mob) {
        World world = mob.worldObj;
        int cx = MathHelper.floor_double(mob.posX);
        int cy = MathHelper.floor_double(mob.posY);
        int cz = MathHelper.floor_double(mob.posZ);
        int radius = (int) GATHER_SCAN_RADIUS;

        ChunkCoordinates best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -6; dy <= 2; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (world.getBlock(x, y, z) == Blocks.stone && !isNearHome(x, z) && isSafeToMine(world, x, y, z)) {
                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new ChunkCoordinates(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Kills nearby sheep for wool (needed for a bed) - only while short on wool
     * and not already carrying a bed. Same chase/attack pattern as
     * trySeekAndKillMob, just targeting EntitySheep instead of hostile mobs.
     */
    private boolean tryGetWool(BotPlayer mob) {
        // A queued "get wool" goal overrides the normal "only while short on wool
        // and no bed yet" gating - same reasoning as tryGatherStone's cap widening
        // above, a specific player request shouldn't silently stall just because
        // the bot already has what it personally needs.
        Goal activeGoal = BotPlayerManager.peekActiveGoal();
        boolean woolGoalActive = activeGoal != null && activeGoal.type == GoalType.WOOL;
        if (!woolGoalActive) {
            if (findItemStack(mob, Items.bed) != null) return false;
            if (countItemOfBlock(mob, Blocks.wool) >= 3) return false;
        }

        EntitySheep target = findNearestSheep(mob);
        if (target == null) return false;

        double dx = target.posX - mob.posX;
        double dz = target.posZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        mob.retaliateAttackCooldown--;
        if (distSq <= RETALIATE_ATTACK_RANGE * RETALIATE_ATTACK_RANGE) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            if (mob.retaliateAttackCooldown <= 0) {
                switchToTool(mob, ItemSword.class);
                mob.swingItem();
                target.attackEntityFrom(DamageSource.causePlayerDamage(mob), currentAttackDamage(mob));
                mob.retaliateAttackCooldown = ACTION_HOLD_TICKS;
                if (!target.isEntityAlive()) {
                    BotPlayerManager.onGoalUnitGathered(GoalType.WOOL);
                }
            }
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private EntitySheep findNearestSheep(BotPlayer mob) {
        List<EntitySheep> nearby = mob.worldObj.getEntitiesWithinAABB(EntitySheep.class,
                mob.boundingBox.expand(MOB_SEEK_RADIUS, MOB_SEEK_RADIUS / 2.0, MOB_SEEK_RADIUS));
        EntitySheep best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntitySheep e : nearby) {
            if (!e.isEntityAlive()) continue;
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

    /**
     * Hunts nearby cows/pigs/chickens for meat when genuinely low on food and
     * not already carrying any - confirmed live as a real gap: tryEat can only
     * eat food it already has, nothing was ever going out and getting more, so
     * hunger crashed to 0 and stayed there the bot's entire session. Same
     * chase/attack pattern as tryGetWool/trySeekAndKillMob. Uses the same
     * EAT_FOOD_THRESHOLD as tryEat so hunting kicks in exactly when eating
     * would. Deliberately excludes EntitySheep (and any other EntityAnimal) -
     * confirmed via decompiling EntitySheep.dropFewItems that sheep drop only
     * wool in this version (mutton wasn't added until later Minecraft
     * versions), so hunting one for "food" would be a wasted attack that
     * yields zero food - sheep are tryGetWool's job specifically.
     */
    private boolean tryHuntFood(BotPlayer mob) {
        // A queued "hunt food" goal overrides the normal hunger gating below -
        // same reasoning as tryGetWool's woolGoalActive check.
        Goal activeGoal = BotPlayerManager.peekActiveGoal();
        boolean foodGoalActive = activeGoal != null && activeGoal.type == GoalType.FOOD;
        if (!foodGoalActive) {
            if (mob.getFoodStats().getFoodLevel() >= EAT_FOOD_THRESHOLD) return false;
            if (hasAnyFood(mob)) return false;
        }

        EntityAnimal target = findNearestAnimal(mob);
        if (target == null) return false;

        double dx = target.posX - mob.posX;
        double dz = target.posZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        mob.retaliateAttackCooldown--;
        if (distSq <= RETALIATE_ATTACK_RANGE * RETALIATE_ATTACK_RANGE) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            if (mob.retaliateAttackCooldown <= 0) {
                switchToTool(mob, ItemSword.class);
                mob.swingItem();
                target.attackEntityFrom(DamageSource.causePlayerDamage(mob), currentAttackDamage(mob));
                mob.retaliateAttackCooldown = ACTION_HOLD_TICKS;
                if (!target.isEntityAlive()) {
                    BotPlayerManager.onGoalUnitGathered(GoalType.FOOD);
                }
            }
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private boolean hasAnyFood(BotPlayer mob) {
        for (ItemStack stack : mob.inventory.mainInventory) {
            if (stack != null && stack.getItem() instanceof ItemFood) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean isFoodAnimal(EntityAnimal e) {
        return e instanceof EntityCow || e instanceof EntityPig || e instanceof EntityChicken;
    }

    private EntityAnimal findNearestAnimal(BotPlayer mob) {
        List<EntityAnimal> nearby = mob.worldObj.getEntitiesWithinAABB(EntityAnimal.class,
                mob.boundingBox.expand(MOB_SEEK_RADIUS, MOB_SEEK_RADIUS / 2.0, MOB_SEEK_RADIUS));
        EntityAnimal best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EntityAnimal e : nearby) {
            if (!e.isEntityAlive() || !isFoodAnimal(e)) continue;
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

    /**
     * Lightweight periodic check (like tryEat) - crafts planks/sticks/tools/a
     * chest/a bed directly from inventory contents when it has the ingredients
     * and (for anything bigger than a 2x2 shape) is near a real crafting table,
     * one item per check. The item-consumption itself is still simplified
     * (direct ingredient-check-and-consume, not simulating the actual shaped
     * grid arrangement) - same "instant action" simplification this whole
     * class already uses elsewhere (instant mining, instant tilling, instant
     * door toggling) - but the table requirement itself is real, matching
     * vanilla's actual 2x2-vs-3x3 crafting rules.
     */
    private void tryCraft(BotPlayer mob) {
        mob.craftCheckCooldown--;
        if (mob.craftCheckCooldown > 0) return;
        mob.craftCheckCooldown = CRAFT_CHECK_INTERVAL_TICKS;

        craftPlanksFromLogs(mob);
        craftSticksFromPlanks(mob);
        // Matches real vanilla exactly, not just this mod's own convention:
        // planks, sticks, and the table itself all fit in the 2x2 personal
        // inventory grid and never need a table - only shapes bigger than 2x2
        // (tools, chest, door, bed) genuinely require one. Per explicit "learn
        // how to use a crafting table" request, everything past this point now
        // actually requires being near a placed one instead of crafting from
        // thin air anywhere.
        craftCraftingTableFromPlanks(mob);
        if (!ensureNearbyCraftingTable(mob)) return;

        // Tools before chest/door/bed - pickaxe especially unlocks stone/ore
        // gathering, so bootstrapping it early matters more than furniture.
        craftPickaxeFromPlanksAndSticks(mob);
        craftAxeFromPlanksAndSticks(mob);
        craftShovelFromPlanksAndSticks(mob);
        craftSwordFromPlanksAndSticks(mob);
        craftChestFromPlanks(mob);
        craftDoorFromPlanks(mob);
        craftBedFromWoolAndPlanks(mob);
    }

    private boolean hasNearbyCraftingTable(BotPlayer mob) {
        int cx = MathHelper.floor_double(mob.posX);
        int cy = MathHelper.floor_double(mob.posY);
        int cz = MathHelper.floor_double(mob.posZ);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (mob.worldObj.getBlock(cx + dx, cy + dy, cz + dz) == Blocks.crafting_table) return true;
                }
            }
        }
        return false;
    }

    /**
     * If there's no placed table within reach, plops down the one it's
     * carrying right where it's standing - same "carry a table, place it
     * where needed" pattern a real player uses, rather than only ever being
     * able to craft back at its home base's fixed table. craftCraftingTableFromPlanks
     * (called just before this) already ensures it's carrying one whenever
     * it doesn't have access to a placed one, so this mostly just handles the
     * "have one, need to place it" step.
     */
    private boolean ensureNearbyCraftingTable(BotPlayer mob) {
        if (hasNearbyCraftingTable(mob)) return true;

        ItemStack table = findItemStackOfBlock(mob, Blocks.crafting_table);
        if (table == null) return false;

        int x = MathHelper.floor_double(mob.posX);
        int y = MathHelper.floor_double(mob.posY);
        int z = MathHelper.floor_double(mob.posZ);
        if (mob.worldObj.getBlock(x, y, z).getMaterial() != Material.air) return false;

        mob.worldObj.setBlock(x, y, z, Blocks.crafting_table);
        table.stackSize--;
        if (table.stackSize <= 0) removeStack(mob, table);
        return true;
    }

    private void craftCraftingTableFromPlanks(BotPlayer mob) {
        if (findItemStackOfBlock(mob, Blocks.crafting_table) != null) return;
        if (hasNearbyCraftingTable(mob)) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        if (planks == null || planks.stackSize < 4) return;
        planks.stackSize -= 4;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        mob.inventory.addItemStackToInventory(new ItemStack(Blocks.crafting_table));
    }

    /** True if the bot already has a tool of this class (any tier - wood through diamond), so it doesn't keep re-crafting redundant wooden ones once it has better. */
    private boolean hasAnyOfClass(BotPlayer mob, Class<? extends Item> toolClass) {
        for (ItemStack stack : mob.inventory.mainInventory) {
            if (stack != null && toolClass.isInstance(stack.getItem())) return true;
        }
        return false;
    }

    /**
     * Bootstraps basic wooden tools from planks+sticks it already gathers for
     * other crafting - per explicit "learn to use the right tools for the job"
     * request. Without this, a bot that never got hand-given a pickaxe (e.g. a
     * fresh spawn with nothing but starting inventory) had no way to ever
     * acquire one itself, so tryGatherStone/tryMineOre could never do anything.
     * Same simplified "instant craft from raw ingredient counts" pattern as the
     * existing chest/door/bed crafting - quantities match vanilla's real recipes
     * (3 planks+2 sticks for pickaxe/axe, 1 plank+2 sticks for shovel, 2 planks+1
     * stick for sword) even though the shaped-grid arrangement isn't simulated.
     */
    private void craftPickaxeFromPlanksAndSticks(BotPlayer mob) {
        if (hasAnyOfClass(mob, ItemPickaxe.class)) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        ItemStack sticks = findItemStack(mob, Items.stick);
        if (planks == null || planks.stackSize < 3 || sticks == null || sticks.stackSize < 2) return;
        planks.stackSize -= 3;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        sticks.stackSize -= 2;
        if (sticks.stackSize <= 0) removeStack(mob, sticks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.wooden_pickaxe));
    }

    private void craftAxeFromPlanksAndSticks(BotPlayer mob) {
        if (hasAnyOfClass(mob, ItemAxe.class)) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        ItemStack sticks = findItemStack(mob, Items.stick);
        if (planks == null || planks.stackSize < 3 || sticks == null || sticks.stackSize < 2) return;
        planks.stackSize -= 3;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        sticks.stackSize -= 2;
        if (sticks.stackSize <= 0) removeStack(mob, sticks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.wooden_axe));
    }

    private void craftShovelFromPlanksAndSticks(BotPlayer mob) {
        if (hasAnyOfClass(mob, ItemSpade.class)) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        ItemStack sticks = findItemStack(mob, Items.stick);
        if (planks == null || planks.stackSize < 1 || sticks == null || sticks.stackSize < 2) return;
        planks.stackSize -= 1;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        sticks.stackSize -= 2;
        if (sticks.stackSize <= 0) removeStack(mob, sticks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.wooden_shovel));
    }

    private void craftSwordFromPlanksAndSticks(BotPlayer mob) {
        if (hasAnyOfClass(mob, ItemSword.class)) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        ItemStack sticks = findItemStack(mob, Items.stick);
        if (planks == null || planks.stackSize < 2 || sticks == null || sticks.stackSize < 1) return;
        planks.stackSize -= 2;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        sticks.stackSize -= 1;
        if (sticks.stackSize <= 0) removeStack(mob, sticks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.wooden_sword));
    }

    private void craftDoorFromPlanks(BotPlayer mob) {
        if (findItemStack(mob, Items.wooden_door) != null) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        if (planks == null || planks.stackSize < 2) return;
        planks.stackSize -= 2;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.wooden_door));
    }

    private void craftPlanksFromLogs(BotPlayer mob) {
        ItemStack[] main = mob.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            ItemStack stack = main[i];
            if (stack == null) continue;
            if (isLog(Block.getBlockFromItem(stack.getItem()))) {
                stack.stackSize--;
                if (stack.stackSize <= 0) main[i] = null;
                mob.inventory.addItemStackToInventory(new ItemStack(Blocks.planks, 4, 0));
                return;
            }
        }
    }

    private void craftSticksFromPlanks(BotPlayer mob) {
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        if (planks == null || planks.stackSize < 2) return;
        planks.stackSize -= 2;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.stick, 4));
    }

    private void craftChestFromPlanks(BotPlayer mob) {
        if (findItemStackOfBlock(mob, Blocks.chest) != null) return;
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        if (planks == null || planks.stackSize < 8) return;
        planks.stackSize -= 8;
        if (planks.stackSize <= 0) removeStack(mob, planks);
        mob.inventory.addItemStackToInventory(new ItemStack(Blocks.chest));
    }

    private void craftBedFromWoolAndPlanks(BotPlayer mob) {
        if (findItemStack(mob, Items.bed) != null) return;
        ItemStack wool = findItemStackOfBlock(mob, Blocks.wool);
        ItemStack planks = findItemStackOfBlock(mob, Blocks.planks);
        if (wool == null || wool.stackSize < 3 || planks == null || planks.stackSize < 3) return;
        wool.stackSize -= 3;
        planks.stackSize -= 3;
        if (wool.stackSize <= 0) removeStack(mob, wool);
        if (planks.stackSize <= 0) removeStack(mob, planks);
        mob.inventory.addItemStackToInventory(new ItemStack(Items.bed));
    }

    private ItemStack findItemStackOfBlock(BotPlayer mob, Block block) {
        return findItemStack(mob, Item.getItemFromBlock(block));
    }

    private int countItemOfBlock(BotPlayer mob, Block block) {
        Item item = Item.getItemFromBlock(block);
        int total = 0;
        for (ItemStack stack : mob.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) {
                total += stack.stackSize;
            }
        }
        return total;
    }

    private void removeStack(BotPlayer mob, ItemStack stack) {
        ItemStack[] main = mob.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            if (main[i] == stack) {
                main[i] = null;
                return;
            }
        }
    }

    /**
     * Builds the bot's own base - a simple walled cobblestone room 100 blocks
     * from home (BotPlayerManager.getBaseX()/Z), with a door gap, a bed, and a
     * chest, per the user's explicit request. Same "walk to target, act, advance
     * to next step" pattern as everything else in this class - no real
     * construction planning beyond a fixed blueprint, and no pathfinding beyond
     * straight-line movement (same documented limitation as the rest of the mod).
     * Ground level at the base site is unknown in advance, so it's found once
     * (scanning down from the bot's current Y the first time it gets there) and
     * cached in BotPlayerManager for the rest of the server's uptime.
     */
    private boolean tryBuildBase(BotPlayer mob) {
        if (mob.dimension != BotPlayerManager.HOME_DIMENSION) return false;
        if (BotPlayerManager.isBaseChestPlaced()) return false;

        if (Double.isNaN(BotPlayerManager.getBaseGroundY())) {
            double dx = BotPlayerManager.getBaseX() - mob.posX;
            double dz = BotPlayerManager.getBaseZ() - mob.posZ;
            if (dx * dx + dz * dz > GATHER_SCAN_RADIUS * GATHER_SCAN_RADIUS) {
                // Too far to know the real ground height yet - walk toward the site first.
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                turnToward(mob, yaw);
                mob.moveStrafing = 0.0F;
                mob.moveForward = MOVE_SPEED;
                return true;
            }
            int gy = findGroundY(mob.worldObj,
                    MathHelper.floor_double(BotPlayerManager.getBaseX()), MathHelper.floor_double(mob.posY), MathHelper.floor_double(BotPlayerManager.getBaseZ()));
            BotPlayerManager.setBaseGroundY(gy == Integer.MIN_VALUE ? BotPlayerManager.HOME_Y : gy);
        }
        double baseY = BotPlayerManager.getBaseGroundY();

        List<int[]> blueprint = getBaseWallBlueprint();
        if (BotPlayerManager.getBaseWallIndex() < blueprint.size()) {
            return placeNextWallBlock(mob, blueprint, baseY);
        }
        if (!BotPlayerManager.isBaseDoorPlaced()) {
            return placeBaseDoor(mob, baseY);
        }
        if (!BotPlayerManager.isBaseBedPlaced()) {
            return placeBaseBed(mob, baseY);
        }
        if (!BotPlayerManager.isBaseCraftingTablePlaced()) {
            return placeBaseCraftingTable(mob, baseY);
        }
        return placeBaseChest(mob, baseY);
    }

    /** Scans down (then a little up) from startY for the first solid block - same idea as StateEncoder's slope helper, duplicated locally since that one's private to StateEncoder. */
    private int findGroundY(World world, int x, int startY, int z) {
        for (int dy = 2; dy >= -10; dy--) {
            int checkY = startY + dy;
            if (world.getBlock(x, checkY, z).getMaterial().isSolid()) {
                return checkY;
            }
        }
        return Integer.MIN_VALUE;
    }

    /** (2*BASE_HALF_SIZE+1) square perimeter walls, BASE_WALL_HEIGHT tall, with a 2-high door gap centered on the south wall (dz = -BASE_HALF_SIZE). */
    private List<int[]> getBaseWallBlueprint() {
        List<int[]> blocks = new ArrayList<int[]>();
        for (int dx = -BASE_HALF_SIZE; dx <= BASE_HALF_SIZE; dx++) {
            for (int dz = -BASE_HALF_SIZE; dz <= BASE_HALF_SIZE; dz++) {
                boolean perimeter = dx == -BASE_HALF_SIZE || dx == BASE_HALF_SIZE || dz == -BASE_HALF_SIZE || dz == BASE_HALF_SIZE;
                if (!perimeter) continue;
                for (int dy = 0; dy < BASE_WALL_HEIGHT; dy++) {
                    if (dx == 0 && dz == -BASE_HALF_SIZE && dy < 2) continue; // door gap
                    blocks.add(new int[]{dx, dy, dz});
                }
            }
        }
        return blocks;
    }

    private boolean placeNextWallBlock(BotPlayer mob, List<int[]> blueprint, double baseY) {
        int index = BotPlayerManager.getBaseWallIndex();
        int[] off = blueprint.get(index);
        int tx = MathHelper.floor_double(BotPlayerManager.getBaseX()) + off[0];
        int ty = (int) baseY + off[1];
        int tz = MathHelper.floor_double(BotPlayerManager.getBaseZ()) + off[2];

        if (mob.worldObj.getBlock(tx, ty, tz).getMaterial().isSolid()) {
            BotPlayerManager.setBaseWallIndex(index + 1); // already solid (resumed after a restart, or natural terrain) - nothing to do here
            return true;
        }

        double dx = (tx + 0.5) - mob.posX;
        double dz = (tz + 0.5) - mob.posZ;
        double distSq = dx * dx + dz * dz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            ItemStack cobble = findItemStackOfBlock(mob, Blocks.cobblestone);
            if (cobble == null) return false; // out of material - let gathering resume
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            mob.worldObj.setBlock(tx, ty, tz, Blocks.cobblestone);
            cobble.stackSize--;
            if (cobble.stackSize <= 0) removeStack(mob, cobble);
            BotPlayerManager.setBaseWallIndex(index + 1);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    /** The wall blueprint leaves this 2-high gap open (see getBaseWallBlueprint) - relative (dx,dz) offset from BASE_X/Z, same for both the bottom and top door block. */
    private int[] getBaseDoorOffset() {
        return new int[]{0, -BASE_HALF_SIZE};
    }

    /**
     * Places a real door instead of leaving the gap permanently open, so the
     * bot's existing tryHandleDoors/maybeCloseDoor logic treats its own front
     * door the same as any other door it encounters. Metadata scheme confirmed
     * by decompiling BlockDoor.func_150012_g: bottom block = direction (0-3) in
     * bits 0-1, open flag in bit 2; top block = bit 3 set (marks it as the top
     * half), hinge side in bit 0 (either value is fine functionally).
     */
    private boolean placeBaseDoor(BotPlayer mob, double baseY) {
        ItemStack door = findItemStack(mob, Items.wooden_door);
        if (door == null) return false;

        int[] off = getBaseDoorOffset();
        int tx = MathHelper.floor_double(BotPlayerManager.getBaseX()) + off[0];
        int ty = (int) baseY;
        int tz = MathHelper.floor_double(BotPlayerManager.getBaseZ()) + off[1];

        double dx = (tx + 0.5) - mob.posX;
        double dz = (tz + 0.5) - mob.posZ;
        double distSq = dx * dx + dz * dz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            mob.worldObj.setBlock(tx, ty, tz, Blocks.wooden_door, 0, 3);
            mob.worldObj.setBlock(tx, ty + 1, tz, Blocks.wooden_door, 8, 3);
            removeStack(mob, door);
            BotPlayerManager.setBaseDoorPlaced(true);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    /** Bed placed just inside the base, one block in from the (now-open, since the door gap sits above the walls list) south side. Metadata scheme confirmed via decompiling BlockBed/BlockDirectional: direction = meta & 3, head flag = meta & 8, and BlockBed.field_149981_a[direction] gives the (dx,dz) offset from foot to head - direction 0 is {0,1}. */
    private boolean placeBaseBed(BotPlayer mob, double baseY) {
        ItemStack bed = findItemStack(mob, Items.bed);
        if (bed == null) return false;

        int footX = MathHelper.floor_double(BotPlayerManager.getBaseX());
        int footY = (int) baseY;
        int footZ = MathHelper.floor_double(BotPlayerManager.getBaseZ()) - 1;
        int headZ = footZ + 1;

        double dx = (footX + 0.5) - mob.posX;
        double dz = (footZ + 0.5) - mob.posZ;
        double distSq = dx * dx + dz * dz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            mob.worldObj.setBlock(footX, footY, footZ, Blocks.bed, 0, 3);
            mob.worldObj.setBlock(footX, footY, headZ, Blocks.bed, 8, 3);
            removeStack(mob, bed);
            BotPlayerManager.setBaseBedPlaced(true);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    /** Placed at (-1,-1) relative to BASE_X/Z - mirrors the chest's (+1,-1) slot on the other side of the bed, clear of the bed (0,-1)/(0,0) and door (0,-2) blocks. */
    private boolean placeBaseCraftingTable(BotPlayer mob, double baseY) {
        ItemStack tableItem = findItemStackOfBlock(mob, Blocks.crafting_table);
        if (tableItem == null) return false;

        int tx = MathHelper.floor_double(BotPlayerManager.getBaseX()) - 1;
        int ty = (int) baseY;
        int tz = MathHelper.floor_double(BotPlayerManager.getBaseZ()) - 1;

        double dx = (tx + 0.5) - mob.posX;
        double dz = (tz + 0.5) - mob.posZ;
        double distSq = dx * dx + dz * dz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            mob.worldObj.setBlock(tx, ty, tz, Blocks.crafting_table);
            removeStack(mob, tableItem);
            BotPlayerManager.setBaseCraftingTablePlaced(true);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private boolean placeBaseChest(BotPlayer mob, double baseY) {
        ItemStack chestItem = findItemStackOfBlock(mob, Blocks.chest);
        if (chestItem == null) return false;

        int tx = MathHelper.floor_double(BotPlayerManager.getBaseX()) + 1;
        int ty = (int) baseY;
        int tz = MathHelper.floor_double(BotPlayerManager.getBaseZ()) - 1;

        double dx = (tx + 0.5) - mob.posX;
        double dz = (tz + 0.5) - mob.posZ;
        double distSq = dx * dx + dz * dz;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            mob.worldObj.setBlock(tx, ty, tz, Blocks.chest);
            removeStack(mob, chestItem);
            BotPlayerManager.setBaseChestPlaced(true);
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private ChunkCoordinates findNearbyLog(BotPlayer mob) {
        World world = mob.worldObj;
        int cx = MathHelper.floor_double(mob.posX);
        int cy = MathHelper.floor_double(mob.posY);
        int cz = MathHelper.floor_double(mob.posZ);
        int radius = (int) GATHER_SCAN_RADIUS;

        ChunkCoordinates best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 6; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (isLog(world.getBlock(x, y, z)) && !isNearHome(x, z) && isSafeToMine(world, x, y, z)) {
                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new ChunkCoordinates(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Vanilla logs match directly; modded trees (this pack has BiomesOPlenty,
     * Natura, Forestry) aren't on the compile classpath so their exact Block
     * classes/registry names aren't known here - instead this matches on the
     * same convention essentially every tree-adding mod follows: Material.wood
     * plus "log" somewhere in the unlocalized name. Not 100% guaranteed for every
     * mod, but far better than only ever seeing vanilla trees in a pack this size.
     */
    private boolean isLog(Block block) {
        if (block == Blocks.log || block == Blocks.log2) return true;
        if (block.getMaterial() != Material.wood) return false;
        return block.getUnlocalizedName().toLowerCase().contains("log");
    }

    /** Used to keep wood-gathering away from logs that are actually the player's house, not a wild tree. */
    private boolean isNearHome(int blockX, int blockZ) {
        double dx = blockX + 0.5 - BotPlayerManager.HOME_X;
        double dz = blockZ + 0.5 - BotPlayerManager.HOME_Z;
        return dx * dx + dz * dz <= HOME_PROTECT_RADIUS * HOME_PROTECT_RADIUS;
    }

    /**
     * Same scan/approach/act pattern as tryGatherWood, targeting the nearest
     * inventory-holding block instead of a log. Matches by TileEntity interface
     * (IInventory) rather than the vanilla TileEntityChest class specifically, so
     * it also covers Iron Chests' variants (its own TileEntity class, not a
     * vanilla chest subclass) and most other simple block storage in this pack.
     * Does NOT cover Applied Energistics' ME network storage - that's a digital
     * storage system accessed through AE2's own network/cell API, not a simple
     * per-block IInventory, so it's architecturally out of reach without AE2's
     * API as a compile dependency (not currently set up in this project).
     */
    private boolean tryLootChest(BotPlayer mob) {
        if (mob.hasLootTarget) {
            TileEntity te = mob.worldObj.getTileEntity(
                    MathHelper.floor_double(mob.lootTargetX), MathHelper.floor_double(mob.lootTargetY), MathHelper.floor_double(mob.lootTargetZ));
            if (!(te instanceof IInventory)) {
                mob.hasLootTarget = false;
            }
        }

        if (!mob.hasLootTarget) {
            mob.lootRescanCooldown--;
            if (mob.lootRescanCooldown > 0) return false;
            mob.lootRescanCooldown = GATHER_RESCAN_INTERVAL_TICKS;

            ChunkCoordinates chest = findNearbyInventory(mob);
            if (chest == null) return false;

            mob.hasLootTarget = true;
            mob.lootTargetX = chest.posX + 0.5;
            mob.lootTargetY = chest.posY;
            mob.lootTargetZ = chest.posZ + 0.5;
        }

        double dx = mob.lootTargetX - mob.posX;
        double dz = mob.lootTargetZ - mob.posZ;
        double distSq = dx * dx + dz * dz;

        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        turnToward(mob, yaw);

        if (distSq <= GATHER_REACH * GATHER_REACH) {
            mob.moveStrafing = 0.0F;
            mob.moveForward = 0.0F;
            lootInventoryAt(mob, MathHelper.floor_double(mob.lootTargetX), MathHelper.floor_double(mob.lootTargetY), MathHelper.floor_double(mob.lootTargetZ));
            mob.hasLootTarget = false;
        } else {
            mob.moveStrafing = 0.0F;
            mob.moveForward = MOVE_SPEED;
        }
        return true;
    }

    private void lootInventoryAt(BotPlayer mob, int x, int y, int z) {
        TileEntity te = mob.worldObj.getTileEntity(x, y, z);
        if (!(te instanceof IInventory)) return;

        IInventory inv = (IInventory) te;
        for (int i = 0; i < inv.getSizeInventory(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack == null) continue;
            mob.inventory.addItemStackToInventory(stack);
            inv.setInventorySlotContents(i, stack.stackSize > 0 ? stack : null);
        }
    }

    private ChunkCoordinates findNearbyInventory(BotPlayer mob) {
        World world = mob.worldObj;
        int cx = MathHelper.floor_double(mob.posX);
        int cy = MathHelper.floor_double(mob.posY);
        int cz = MathHelper.floor_double(mob.posZ);
        int radius = (int) GATHER_SCAN_RADIUS;

        ChunkCoordinates best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -4; dy <= 4; dy++) {
                    int x = cx + dx;
                    int y = cy + dy;
                    int z = cz + dz;
                    if (world.getTileEntity(x, y, z) instanceof IInventory && !isNearHome(x, z)) {
                        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            best = new ChunkCoordinates(x, y, z);
                        }
                    }
                }
            }
        }
        return best;
    }

    /**
     * Switches the bot's held item to the first item of the given class found in
     * its own inventory (already-held first, then a scan of mainInventory),
     * leaving currentItem pointed straight at that slot even outside the visual
     * hotbar range 0-8 - InventoryPlayer.getCurrentItem() just indexes the array
     * directly, and that's also what other players' clients render as "held", so
     * this is enough for the bot to actually be seen wielding the right tool
     * without needing to physically shuffle it into the hotbar first.
     * Returns the found stack, or null if it has no such item.
     */
    private ItemStack switchToTool(BotPlayer mob, Class<? extends Item> toolClass) {
        // Never interrupt an in-progress eat - EntityPlayer.onUpdate() aborts
        // setItemInUse the instant the held item stops matching what it started
        // eating, silently cancelling the hunger restore with no error. Confirmed
        // live as a real bug: food repeatedly crashed to 0 and stayed there even
        // with 16 apples in inventory, because combat kept switching to a sword
        // mid-bite every single time it tried to eat.
        if (mob.isUsingItem()) return mob.inventory.getCurrentItem();

        ItemStack current = mob.inventory.getCurrentItem();
        if (current != null && toolClass.isInstance(current.getItem())) {
            return current;
        }
        ItemStack[] main = mob.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            if (main[i] != null && toolClass.isInstance(main[i].getItem())) {
                mob.inventory.currentItem = i;
                return main[i];
            }
        }
        return null;
    }

    /** Finds (without switching to) the first stack of the exact given item in the bot's main inventory. */
    private ItemStack findItemStack(BotPlayer mob, Item item) {
        for (ItemStack stack : mob.inventory.mainInventory) {
            if (stack != null && stack.getItem() == item) {
                return stack;
            }
        }
        return null;
    }

    /** Same idea as switchToTool but for one specific item rather than a whole class - used to force ore mining onto the diamond pickaxe specifically. */
    private ItemStack switchToItem(BotPlayer mob, Item item) {
        if (mob.isUsingItem()) return mob.inventory.getCurrentItem(); // see switchToTool - never interrupt an in-progress eat

        ItemStack current = mob.inventory.getCurrentItem();
        if (current != null && current.getItem() == item) {
            return current;
        }
        ItemStack[] main = mob.inventory.mainInventory;
        for (int i = 0; i < main.length; i++) {
            if (main[i] != null && main[i].getItem() == item) {
                mob.inventory.currentItem = i;
                return main[i];
            }
        }
        return null;
    }

    /** Maps a block's declared harvest tool (Forge's Block.getHarvestTool, e.g. "pickaxe"/"axe"/"shovel") to the matching item class, or null if the block doesn't call for a specific one. */
    private Class<? extends Item> harvestToolClassFor(Block block, int meta) {
        String tool = block.getHarvestTool(meta);
        if (tool == null) return null;
        if (tool.equals("pickaxe")) return ItemPickaxe.class;
        if (tool.equals("axe")) return ItemAxe.class;
        if (tool.equals("shovel")) return ItemSpade.class;
        return null;
    }

    /**
     * Sword total attack damage, mirroring the bonus ItemSword itself computes
     * (base fist damage 4.0F + the tool material's damage bonus - confirmed via
     * decompiling ItemSword's constructor) rather than vanilla's full attribute-
     * modifier pipeline, since this mod's combat already deals damage directly via
     * attackEntityFrom instead of going through that pipeline. Falls back to the
     * existing fixed ATTACK_DAMAGE (fist-equivalent) when no sword is held.
     */
    private float currentAttackDamage(BotPlayer mob) {
        ItemStack held = mob.inventory.getCurrentItem();
        if (held != null && held.getItem() instanceof ItemSword) {
            return 4.0F + ((ItemSword) held.getItem()).func_150931_i();
        }
        return ATTACK_DAMAGE;
    }

    /** Turns at most TURN_SPEED_DEG_PER_TICK toward the desired yaw instead of snapping to it, like a human dragging a mouse. */
    private void turnToward(BotPlayer mob, float desiredYaw) {
        float delta = MathHelper.wrapAngleTo180_float(desiredYaw - mob.rotationYaw);
        delta = MathHelper.clamp_float(delta, -TURN_SPEED_DEG_PER_TICK, TURN_SPEED_DEG_PER_TICK);
        mob.rotationYaw += delta;
        mob.rotationYawHead = mob.rotationYaw;
    }

    /** Re-resolves by name every tick (rather than holding a raw entity reference) so a reconnect doesn't leave a stale target. */
    private EntityPlayer resolvePlayer(String name) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return null;
        EntityPlayerMP player = server.getConfigurationManager().func_152612_a(name);
        return (player != null && !player.isDead) ? player : null;
    }

    /** Returns true if it just went to sleep this tick (caller should stop here). */
    private boolean maybeAutoSleep(BotPlayer mob) {
        mob.sleepCheckCooldown--;
        if (mob.sleepCheckCooldown > 0) return false;
        mob.sleepCheckCooldown = SLEEP_CHECK_INTERVAL_TICKS;

        if (mob.worldObj.isDaytime()) return false;

        return BotPlayerManager.trySleep(mob) == EntityPlayer.EnumStatus.OK;
    }

    /**
     * Only when nobody real is online - there's no ground truth to learn from
     * otherwise, so this stays rare and capped. Deliberately skips IDLE: recording
     * "IDLE was correct" every time the network already leans IDLE just reinforces
     * that same bias circularly (this was the actual root cause of the network
     * predicting IDLE ~97% of the time - it kept being told its own idle guess was
     * right, with nothing pulling it back toward the human-recorded movement data).
     */
    private void maybeRecordSelfSample(BotPlayer mob, double[] state, ActionType action) {
        if (action == ActionType.IDLE) return;

        mob.selfSampleCooldown--;
        if (mob.selfSampleCooldown > 0) return;
        mob.selfSampleCooldown = SELF_SAMPLE_INTERVAL_DECISIONS;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || BotPlayerManager.hasRealPlayerOnline(server)) return;
        if (!isGoodStateForSelfPlay(mob)) return;

        BrainManager.instance.recordSelfSample(state, action);
    }

    /**
     * Layers the network's own jump/sprint judgment on top of the scripted
     * goal-pursuit behaviors (retaliate/follow/seek-mob/return-home/gather/loot/
     * wander) instead of replacing their proven "turn toward target, walk" logic
     * outright - those already reliably reach their targets live on the user's
     * server, and letting the network's own (still IDLE-leaning) predictions
     * choose direction outright risked it freezing goal pursuit entirely. Jumping
     * and sprinting are lower-risk to hand to the network: real players' actual
     * jump/sprint choices are genuine, well-grounded training signal (see
     * PlayerActionRecorder), and getting this wrong just looks a little off
     * rather than breaking navigation.
     */
    private void applyNnMovementFlourish(BotPlayer mob) {
        if (mob.moveForward == 0.0F && mob.moveStrafing == 0.0F) {
            mob.setSprinting(false);
            return;
        }

        double[] state = StateEncoder.encode(mob.worldObj, mob);
        ActionType predicted = ActionType.VALUES[BrainManager.instance.getNetwork().predictAction(state)];

        if (predicted == ActionType.JUMP && mob.onGround) {
            mob.setJumping(true);
        }
        mob.setSprinting(predicted == ActionType.SPRINT_FORWARD && mob.moveForward > 0.0F);
    }

    private void execute(BotPlayer mob, ActionType action) {
        mob.moveStrafing = 0.0F;
        mob.moveForward = 0.0F;
        mob.setSprinting(false);

        switch (action) {
            case MOVE_FORWARD:
                mob.moveForward = MOVE_SPEED;
                break;
            case SPRINT_FORWARD:
                mob.moveForward = MOVE_SPEED;
                mob.setSprinting(true);
                break;
            case MOVE_BACKWARD:
                mob.moveForward = -MOVE_SPEED;
                break;
            case STRAFE_LEFT:
                mob.moveStrafing = MOVE_SPEED;
                break;
            case STRAFE_RIGHT:
                mob.moveStrafing = -MOVE_SPEED;
                break;
            case JUMP:
                if (mob.onGround) {
                    mob.setJumping(true);
                }
                break;
            case MINE_BLOCK:
                mineAhead(mob);
                break;
            case PLACE_BLOCK:
                break;
            case ATTACK:
                attackNearby(mob);
                break;
            case IDLE:
                gatherOrWander(mob);
                break;
            default:
                break;
        }
    }

    private void mineAhead(BotPlayer mob) {
        Vec3 look = mob.getLookVec();
        int x = MathHelper.floor_double(mob.posX + look.xCoord * 1.5);
        int y = MathHelper.floor_double(mob.posY + mob.getEyeHeight() + look.yCoord * 1.5);
        int z = MathHelper.floor_double(mob.posZ + look.zCoord * 1.5);

        World world = mob.worldObj;
        Block block = world.getBlock(x, y, z);
        if (block.getMaterial() == Material.air) {
            return;
        }
        if (isHazardousBlock(block)) {
            return;
        }
        // This is the NN's own MINE_BLOCK action choice, not a scanned gather
        // target like tryGatherWood/tryGatherStone/tryMineOre - those already
        // exclude anything within HOME_PROTECT_RADIUS via isNearHome at the scan
        // step, but this path had no such check at all, so the network could
        // break literally whatever block it happened to be looking at while
        // wandering near the user's actual base. Per explicit request: nothing
        // within 100 blocks of the hardcoded home coordinate gets broken, full stop.
        if (isNearHome(x, z)) {
            return;
        }
        float hardness = block.getBlockHardness(world, x, y, z);
        if (hardness < 0.0F || hardness > 5.0F) {
            return;
        }
        if (isOre(block)) {
            // Ore specifically requires the diamond pickaxe per the user's request
            // (not just any pickaxe) - if the bot doesn't have one, leave the ore
            // alone entirely rather than mining it with the wrong tool.
            if (switchToItem(mob, Items.diamond_pickaxe) == null) {
                return;
            }
        } else {
            int meta = world.getBlockMetadata(x, y, z);
            Class<? extends Item> toolClass = harvestToolClassFor(block, meta);
            // Same reasoning as the ore branch above: a block that declares a
            // required harvest tool (e.g. stone needs a pickaxe) drops nothing if
            // broken without one - don't destroy it for nothing just because the
            // network felt like mining right now.
            if (toolClass != null && switchToTool(mob, toolClass) == null) {
                return;
            }
        }
        // func_147480_a (World.destroyBlock) plays the break effect AND drops the
        // item itself - setBlockToAir used to just delete the block with no drops.
        mob.swingItem();
        world.func_147480_a(x, y, z, true);
    }

    private void attackNearby(BotPlayer mob) {
        @SuppressWarnings("unchecked")
        List<EntityLivingBase> nearby = mob.worldObj.getEntitiesWithinAABB(EntityLivingBase.class, mob.boundingBox.expand(1.5, 1.0, 1.5));
        for (EntityLivingBase other : nearby) {
            if (other == mob) continue;
            switchToTool(mob, ItemSword.class);
            mob.swingItem();
            other.attackEntityFrom(DamageSource.causeMobDamage(mob), currentAttackDamage(mob));
            break;
        }
    }
}
