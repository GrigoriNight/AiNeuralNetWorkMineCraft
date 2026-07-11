package com.aibot.command;

import com.aibot.brain.ActionType;
import com.aibot.brain.BrainManager;
import com.aibot.brain.StateEncoder;
import com.aibot.fakeplayer.BotInventoryView;
import com.aibot.fakeplayer.BotPlayer;
import com.aibot.fakeplayer.BotPlayerManager;
import com.aibot.fakeplayer.Goal;
import com.aibot.fakeplayer.GoalType;
import com.aibot.web.ChatAI;
import com.aibot.web.WebDashboardServer;
import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.event.ClickEvent;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;

import java.util.List;

public class CommandBrain extends CommandBase {

    @Override
    public String getCommandName() {
        return "brain";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/brain <spawn|spawnplayer|despawnplayer/hide|rename <name>|sleep|home|follow [player]|unfollow|copy|mine|gui|items|equip|status|base|scan|weburl|apikey|save|load|stats|test|goal <wood|stone|ore|wool|food> <amount>|goals|goal clear|chat|chat model <name>|chat url <url>>";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
            return;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("spawn")) {
            if (!(sender instanceof EntityPlayer)) {
                sender.addChatMessage(new ChatComponentText("Only a player can spawn a brain mob."));
                return;
            }
            BrainActions.spawnZombie((EntityPlayer) sender);
            sender.addChatMessage(new ChatComponentText("Spawned AI brain mob (vanilla zombie, no client mod needed)."));
        } else if (sub.equals("spawnplayer")) {
            if (!(sender instanceof EntityPlayer)) {
                sender.addChatMessage(new ChatComponentText("Only a player can spawn the brain bot."));
                return;
            }
            if (BotPlayerManager.getActive() != null) {
                sender.addChatMessage(new ChatComponentText("A brain bot is already active. Use /brain despawnplayer first."));
                return;
            }
            BotPlayerManager.setHiddenIntent(false);
            BotPlayerManager.spawn((EntityPlayer) sender);
        } else if (sub.equals("despawnplayer") || sub.equals("hide")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            // No extra confirmation text here on purpose - despawn() already
            // broadcasts the standard yellow "X left the game" message to
            // everyone, same as any real player disconnecting. An additional
            // "is hidden and will stay offline" line only the command sender
            // could see was a dead giveaway this isn't a real player.
            BotPlayerManager.setHiddenIntent(true);
            BotPlayerManager.despawn(bot);
        } else if (sub.equals("rename")) {
            if (args.length < 2) {
                sender.addChatMessage(new ChatComponentText("Usage: /brain rename <name>"));
                return;
            }
            String oldName = BotPlayerManager.getBotName();
            BotPlayerManager.rename(args[1]);
            sender.addChatMessage(new ChatComponentText("Renamed " + oldName + " to " + args[1] + "."));
        } else if (sub.equals("sleep")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                bot = spawnForCommand(sender);
                if (bot == null) {
                    sender.addChatMessage(new ChatComponentText("Couldn't spawn the brain bot to put it to sleep."));
                    return;
                }
            }
            EntityPlayer.EnumStatus status = BotPlayerManager.trySleep(bot);
            sender.addChatMessage(new ChatComponentText(describeSleepStatus(status)));
        } else if (sub.equals("home")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            if (bot.dimension == BotPlayerManager.HOME_DIMENSION) {
                bot.forcedGoingHome = true;
                sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + " is walking home."));
            } else {
                sender.addChatMessage(new ChatComponentText("Can't go home - the bot isn't in the home dimension."));
            }
        } else if (sub.equals("follow")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            String targetName;
            if (args.length >= 2) {
                targetName = args[1];
            } else if (sender instanceof EntityPlayer) {
                targetName = ((EntityPlayer) sender).getCommandSenderName();
            } else {
                sender.addChatMessage(new ChatComponentText("Usage: /brain follow <player>"));
                return;
            }
            bot.followPlayerName = targetName;
            bot.copyPlayerName = null;
            sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + " is now following " + targetName + "."));
        } else if (sub.equals("unfollow")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            bot.followPlayerName = null;
            sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + " stopped following."));
        } else if (sub.equals("copy")) {
            if (!(sender instanceof EntityPlayer)) {
                sender.addChatMessage(new ChatComponentText("Only a player can be copied."));
                return;
            }
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            String name = ((EntityPlayer) sender).getCommandSenderName();
            if (name.equals(bot.copyPlayerName)) {
                bot.copyPlayerName = null;
                sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + " stopped copying you."));
            } else {
                bot.copyPlayerName = name;
                bot.followPlayerName = null;
                sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + " is now copying your movement. Run /brain copy again to stop."));
            }
        } else if (sub.equals("scan")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            World world = bot.worldObj;
            int x = MathHelper.floor_double(bot.posX);
            int y = MathHelper.floor_double(bot.boundingBox.minY);
            int z = MathHelper.floor_double(bot.posZ);

            double yawRad = Math.toRadians(bot.rotationYaw);
            int aheadX = MathHelper.floor_double(bot.posX - Math.sin(yawRad));
            int aheadZ = MathHelper.floor_double(bot.posZ + Math.cos(yawRad));
            Block feetAhead = world.getBlock(aheadX, y, aheadZ);
            Block headAhead = world.getBlock(aheadX, y + 1, aheadZ);
            Block below = world.getBlock(x, y - 1, z);
            Block standingIn = world.getBlock(x, y, z);

            sender.addChatMessage(new ChatComponentText(String.format(
                    "Ahead(feet): %s | Ahead(head): %s | Below: %s | In water: %s | In lava: %s | On ground: %s",
                    Block.blockRegistry.getNameForObject(feetAhead), Block.blockRegistry.getNameForObject(headAhead),
                    Block.blockRegistry.getNameForObject(below), bot.isInWater() ? "yes" : "no",
                    standingIn.getMaterial() == Material.lava ? "yes" : "no", bot.onGround ? "yes" : "no")));

            AxisAlignedBB area = bot.boundingBox.expand(10.0, 5.0, 10.0);
            @SuppressWarnings("unchecked")
            List<Entity> nearby = world.getEntitiesWithinAABB(Entity.class, area);
            StringBuilder entities = new StringBuilder();
            int count = 0;
            for (Entity e : nearby) {
                if (e == bot) continue;
                if (count >= 8) break;
                if (count > 0) entities.append(", ");
                entities.append(e.getClass().getSimpleName()).append(" ").append(String.format("%.1fb", e.getDistanceToEntity(bot)));
                count++;
            }
            sender.addChatMessage(new ChatComponentText("Nearby entities (" + nearby.size() + " total, listing up to 8): " + (count == 0 ? "none" : entities.toString())));
        } else if (sub.equals("base")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            double baseY = BotPlayerManager.getBaseGroundY();
            sender.addChatMessage(new ChatComponentText(String.format(
                    "Base site: %.0f, %s, %.0f | Walls placed: %d | Door: %s | Bed: %s | Chest: %s",
                    BotPlayerManager.getBaseX(), Double.isNaN(baseY) ? "ground level unknown yet" : String.valueOf((int) baseY), BotPlayerManager.getBaseZ(),
                    BotPlayerManager.getBaseWallIndex(), BotPlayerManager.isBaseDoorPlaced() ? "yes" : "no",
                    BotPlayerManager.isBaseBedPlaced() ? "yes" : "no", BotPlayerManager.isBaseChestPlaced() ? "yes" : "no")));

            if (BotPlayerManager.isBaseChestPlaced() && !Double.isNaN(baseY)) {
                World world = bot.worldObj;
                int cx = MathHelper.floor_double(BotPlayerManager.getBaseX()) + 1;
                int cy = (int) baseY;
                int cz = MathHelper.floor_double(BotPlayerManager.getBaseZ()) - 1;
                TileEntity te = world.getTileEntity(cx, cy, cz);
                if (te instanceof IInventory) {
                    IInventory inv = (IInventory) te;
                    StringBuilder contents = new StringBuilder();
                    int count = 0;
                    for (int i = 0; i < inv.getSizeInventory(); i++) {
                        ItemStack stack = inv.getStackInSlot(i);
                        if (stack == null) continue;
                        if (count > 0) contents.append(", ");
                        contents.append(stack.getDisplayName()).append(" x").append(stack.stackSize);
                        count++;
                    }
                    sender.addChatMessage(new ChatComponentText("Base chest (" + count + " stacks): " + (count == 0 ? "empty" : contents.toString())));
                }
            }
        } else if (sub.equals("status")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            sender.addChatMessage(new ChatComponentText(String.format(
                    "Doing: %s | Health: %.1f/%.1f | Food: %d/20 | Mining mode: %s | Position: %.0f, %.0f, %.0f (dim %d)",
                    bot.currentBehavior, bot.getHealth(), bot.getMaxHealth(), bot.getFoodStats().getFoodLevel(),
                    bot.miningMode ? "ON" : "off", bot.posX, bot.posY, bot.posZ, bot.dimension)));

            StringBuilder targets = new StringBuilder();
            if (bot.hasGatherTarget) targets.append("wood target ").append(fmtCoords(bot.gatherTargetX, bot.gatherTargetY, bot.gatherTargetZ)).append(" | ");
            if (bot.hasStoneTarget) targets.append("stone target ").append(fmtCoords(bot.stoneTargetX, bot.stoneTargetY, bot.stoneTargetZ)).append(" | ");
            if (bot.hasOreTarget) targets.append("ore target ").append(fmtCoords(bot.oreTargetX, bot.oreTargetY, bot.oreTargetZ)).append(" | ");
            if (bot.hasLootTarget) targets.append("loot target ").append(fmtCoords(bot.lootTargetX, bot.lootTargetY, bot.lootTargetZ)).append(" | ");
            if (bot.hasFarmTarget) targets.append("farm target ").append(fmtCoords(bot.farmTargetX, bot.farmTargetY, bot.farmTargetZ)).append(" | ");
            if (bot.followPlayerName != null) targets.append("following ").append(bot.followPlayerName).append(" | ");
            if (bot.copyPlayerName != null) targets.append("copying ").append(bot.copyPlayerName).append(" | ");
            if (bot.forcedGoingHome) targets.append("forced home-walk active | ");
            if (targets.length() > 0) {
                sender.addChatMessage(new ChatComponentText(targets.substring(0, targets.length() - 3)));
            }
        } else if (sub.equals("mine")) {
            boolean newState = !BotPlayerManager.isMiningModeIntent();
            BotPlayerManager.setMiningModeIntent(newState);
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot != null) {
                bot.miningMode = newState;
            }
            sender.addChatMessage(new ChatComponentText(newState
                    ? BotPlayerManager.getBotName() + " will actively seek out and mine ore now (needs a diamond pickaxe - give it one via /brain gui if it doesn't have one). Stays on even across despawns/respawns until you run /brain mine again."
                    : BotPlayerManager.getBotName() + " stopped actively mining."));
        } else if (sub.equals("goal")) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("clear")) {
                BotPlayerManager.clearGoals();
                sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + "'s to-do list cleared."));
                return;
            }
            if (args.length < 3) {
                sender.addChatMessage(new ChatComponentText("Usage: /brain goal <wood|stone|ore|wool|food> <amount>, or /brain goal clear"));
                return;
            }
            GoalType type = GoalType.fromCommand(args[1]);
            if (type == null) {
                sender.addChatMessage(new ChatComponentText("Unknown goal type '" + args[1] + "' - use wood, stone, ore, wool, or food."));
                return;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                sender.addChatMessage(new ChatComponentText("'" + args[2] + "' isn't a number."));
                return;
            }
            if (amount <= 0) {
                sender.addChatMessage(new ChatComponentText("Amount must be positive."));
                return;
            }
            BotPlayerManager.addGoal(type, amount);
            sender.addChatMessage(new ChatComponentText("Added to " + BotPlayerManager.getBotName() + "'s to-do list: "
                    + new Goal(type, amount) + ". " + BotPlayerManager.listGoals().size() + " goal(s) queued."));
        } else if (sub.equals("goals")) {
            List<Goal> goals = BotPlayerManager.listGoals();
            if (goals.isEmpty()) {
                sender.addChatMessage(new ChatComponentText(BotPlayerManager.getBotName() + "'s to-do list is empty."));
                return;
            }
            StringBuilder sb = new StringBuilder(BotPlayerManager.getBotName() + "'s to-do list: ");
            for (int i = 0; i < goals.size(); i++) {
                if (i > 0) sb.append(" -> ");
                sb.append(goals.get(i));
                if (i == 0) sb.append(" [").append(BotPlayerManager.getGoalProgress()).append("/").append(goals.get(i).targetAmount).append("]");
            }
            sender.addChatMessage(new ChatComponentText(sb.toString()));
        } else if (sub.equals("chat")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("model")) {
                ChatAI.setModel(args[2]);
                sender.addChatMessage(new ChatComponentText("Chat AI model set to '" + args[2] + "'."));
                return;
            }
            if (args.length >= 3 && args[1].equalsIgnoreCase("url")) {
                ChatAI.setUrl(args[2]);
                sender.addChatMessage(new ChatComponentText("Chat AI endpoint set to " + ChatAI.getUrl() + "."));
                return;
            }
            boolean chatState = !ChatAI.isEnabled();
            ChatAI.setEnabled(chatState);
            sender.addChatMessage(new ChatComponentText(chatState
                    ? BotPlayerManager.getBotName() + " will reply in chat when mentioned by name, using model '" + ChatAI.getModel()
                        + "' at " + ChatAI.getUrl() + " (change with /brain chat model <name> or /brain chat url <url>). Stays silent if it's not reachable. Run /brain chat again to disable."
                    : BotPlayerManager.getBotName() + " will no longer reply in chat."));
        } else if (sub.equals("equip")) {
            if (!(sender instanceof EntityPlayer)) {
                sender.addChatMessage(new ChatComponentText("Only a player can equip the bot."));
                return;
            }
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            EntityPlayer player = (EntityPlayer) sender;
            ItemStack held = player.getHeldItem();
            if (held == null) {
                sender.addChatMessage(new ChatComponentText("Hold the armor piece you want to give " + BotPlayerManager.getBotName() + ", then run /brain equip."));
                return;
            }
            if (!(held.getItem() instanceof ItemArmor)) {
                sender.addChatMessage(new ChatComponentText("That's not armor - hold a helmet, chestplate, leggings, or boots and try again."));
                return;
            }

            // Matches BotInventoryView's armorInventory[slot] <-> armorType mapping
            // (confirmed by decompiling ContainerPlayer's constructor - see project memory).
            int armorType = ((ItemArmor) held.getItem()).armorType;
            int slot = 3 - armorType;
            ItemStack previous = bot.inventory.armorInventory[slot];
            bot.inventory.armorInventory[slot] = held.splitStack(1);
            if (held.stackSize <= 0) {
                player.inventory.setInventorySlotContents(player.inventory.currentItem, null);
            }
            if (previous != null && !player.inventory.addItemStackToInventory(previous)) {
                player.entityDropItem(previous, 0.5F);
            }
            sender.addChatMessage(new ChatComponentText("Equipped " + bot.inventory.armorInventory[slot].getDisplayName() + " on " + BotPlayerManager.getBotName() + "."));
        } else if (sub.equals("gui")) {
            if (!(sender instanceof EntityPlayer)) {
                sender.addChatMessage(new ChatComponentText("Only a player can open the bot's inventory GUI."));
                return;
            }
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            ((EntityPlayer) sender).displayGUIChest(new BotInventoryView(bot));
        } else if (sub.equals("items")) {
            BotPlayer bot = BotPlayerManager.getActive();
            if (bot == null) {
                sender.addChatMessage(new ChatComponentText("No brain bot is currently active."));
                return;
            }
            sendItemsSummary(sender, bot);
        } else if (sub.equals("weburl")) {
            WebDashboardServer web = WebDashboardServer.getRunning();
            if (web == null) {
                sender.addChatMessage(new ChatComponentText("Web dashboard is not running."));
                return;
            }
            String url = "http://" + resolveHost() + ":" + WebDashboardServer.getPort() + "/?token=" + web.getAccessToken();
            ChatComponentText link = new ChatComponentText(url);
            link.setChatStyle(new ChatStyle()
                    .setColor(EnumChatFormatting.AQUA)
                    .setUnderlined(true)
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url)));
            sender.addChatMessage(new ChatComponentText("Dashboard: ").appendSibling(link));
        } else if (sub.equals("apikey")) {
            WebDashboardServer web = WebDashboardServer.getRunning();
            if (web == null) {
                sender.addChatMessage(new ChatComponentText("Web dashboard is not running."));
                return;
            }
            sender.addChatMessage(new ChatComponentText("API base: http://" + resolveHost() + ":" + WebDashboardServer.getPort() + "/api/v1/"));
            sender.addChatMessage(new ChatComponentText("API key (persists across restarts): " + web.getApiKey()));
        } else if (sub.equals("save")) {
            BrainManager.instance.save();
            sender.addChatMessage(new ChatComponentText("Brain saved."));
        } else if (sub.equals("load")) {
            BrainManager.instance.load();
            sender.addChatMessage(new ChatComponentText("Brain loaded."));
        } else if (sub.equals("stats")) {
            sender.addChatMessage(new ChatComponentText(
                    "Training samples: " + BrainManager.instance.getDataset().size()
                            + " (" + BrainManager.instance.getDataset().selfGeneratedSize() + " self-generated)"
                            + " | Training steps: " + BrainManager.instance.getTotalTrainingSteps()
                            + " | Loss (last batch): " + String.format("%.4f", BrainManager.instance.getLastAverageLoss())
                            + " | Loss (smoothed trend): " + String.format("%.4f", BrainManager.instance.getSmoothedLoss())
                            + " | Loss (eval, 200-sample): " + (BrainManager.instance.hasEvalLoss()
                                ? String.format("%.4f", BrainManager.instance.getEvalLoss()) : "not run yet")
                            + " | Learning rate: " + String.format("%.5f", BrainManager.instance.getCurrentLearningRate())));
        } else if (sub.equals("test")) {
            if (!(sender instanceof EntityPlayer)) {
                sender.addChatMessage(new ChatComponentText("Only a player can test the brain - it needs a position/surroundings to evaluate."));
                return;
            }
            EntityPlayer player = (EntityPlayer) sender;
            double[] state = StateEncoder.encode(player.worldObj, player);
            double[] probs = BrainManager.instance.getNetwork().predict(state);
            int best = 0;
            for (int i = 1; i < probs.length; i++) {
                if (probs[i] > probs[best]) best = i;
            }
            sender.addChatMessage(new ChatComponentText(
                    "Brain says, from where you're standing: " + ActionType.VALUES[best]));
            StringBuilder breakdown = new StringBuilder();
            for (int i = 0; i < probs.length; i++) {
                if (i > 0) breakdown.append(", ");
                breakdown.append(ActionType.VALUES[i]).append("=").append(String.format("%.0f%%", probs[i] * 100.0));
            }
            sender.addChatMessage(new ChatComponentText(breakdown.toString()));
        } else {
            sender.addChatMessage(new ChatComponentText(getCommandUsage(sender)));
        }
    }

    // server.properties' server-ip is usually blank/0.0.0.0 on hosted servers, which isn't a
    // usable address for a link - falls back to this deployment's known real address instead.
    private static final String KNOWN_HOST_FALLBACK = "104.128.72.33";

    private static String resolveHost() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        String host = server != null ? server.getServerHostname() : null;
        boolean usable = host != null && !host.isEmpty() && !host.equals("0.0.0.0") && !host.equals("localhost");
        return usable ? host : KNOWN_HOST_FALLBACK;
    }

    /** Spawns near the invoking player if there is one, otherwise at the world spawn point. */
    private static BotPlayer spawnForCommand(ICommandSender sender) {
        if (sender instanceof EntityPlayer) {
            return BotPlayerManager.spawn((EntityPlayer) sender);
        }
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return null;
        WorldServer world = server.worldServerForDimension(0);
        return world != null ? BotPlayerManager.spawn(world) : null;
    }

    /** Text summary for /brain items - the quick chat-readable alternative to /brain gui's drag-and-drop view. */
    private static void sendItemsSummary(ICommandSender sender, BotPlayer bot) {
        String[] armorLabels = {"Boots", "Leggings", "Chestplate", "Helmet"};
        StringBuilder armor = new StringBuilder("Armor: ");
        boolean anyArmor = false;
        for (int i = 0; i < bot.inventory.armorInventory.length; i++) {
            ItemStack stack = bot.inventory.armorInventory[i];
            if (stack == null) continue;
            if (anyArmor) armor.append(", ");
            armor.append(armorLabels[i]).append("=").append(describeStack(stack));
            anyArmor = true;
        }
        sender.addChatMessage(new ChatComponentText(anyArmor ? armor.toString() : "Armor: none"));

        ItemStack held = bot.inventory.getCurrentItem();
        sender.addChatMessage(new ChatComponentText("Holding: " + (held == null ? "nothing" : describeStack(held))));

        StringBuilder items = new StringBuilder();
        int count = 0;
        for (ItemStack stack : bot.inventory.mainInventory) {
            if (stack == null) continue;
            if (count > 0) items.append(", ");
            items.append(describeStack(stack));
            count++;
        }
        sender.addChatMessage(new ChatComponentText("Inventory (" + count + " stacks): " + (count == 0 ? "empty" : items.toString())));
    }

    private static String fmtCoords(double x, double y, double z) {
        return String.format("(%.0f, %.0f, %.0f)", x, y, z);
    }

    private static String describeStack(ItemStack stack) {
        return stack.getDisplayName() + " x" + stack.stackSize;
    }

    private static String describeSleepStatus(EntityPlayer.EnumStatus status) {
        switch (status) {
            case OK:
                return "Went to sleep.";
            case NOT_POSSIBLE_HERE:
                return "No bed found nearby.";
            case NOT_POSSIBLE_NOW:
                return "It's daytime - nothing to sleep through.";
            case NOT_SAFE:
                return "Not safe to sleep - hostile mobs are nearby.";
            case TOO_FAR_AWAY:
                return "Bed is too far away.";
            default:
                return "Couldn't sleep right now.";
        }
    }
}
