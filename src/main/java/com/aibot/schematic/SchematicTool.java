package com.aibot.schematic;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;

/**
 * "ToolAI" - a plain vanilla stick (Items.stick) with a custom display name,
 * used as a WorldEdit-style selection wand for marking schematic corners by
 * clicking blocks instead of typing /brain schem pos1/pos2. Deliberately
 * reuses an existing vanilla item rather than registering a brand-new custom
 * Item - a new Item needs a texture/model, which (like every other feature in
 * this mod) would require players to install something client-side, breaking
 * the project's server-side-only design.
 */
public class SchematicTool {

    public static final String TOOL_NAME = "ToolAI";

    public static ItemStack createToolStack() {
        ItemStack stack = new ItemStack(Items.stick);
        stack.setStackDisplayName(TOOL_NAME);
        return stack;
    }

    public static boolean isToolAI(ItemStack stack) {
        return stack != null && stack.getItem() == Items.stick
                && stack.hasDisplayName() && TOOL_NAME.equals(stack.getDisplayName());
    }
}
