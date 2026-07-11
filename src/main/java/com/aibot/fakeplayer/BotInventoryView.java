package com.aibot.fakeplayer;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;

/**
 * Presents a BotPlayer's InventoryPlayer (36 main slots + 4 armor slots) as a
 * single 45-slot (5-row) IInventory so it can be opened with the vanilla
 * chest GUI via EntityPlayer.displayGUIChest() - the client renders this with
 * pure vanilla code (same as opening any chest/modded IInventory block), so
 * no mod is required on connecting players' clients, matching this project's
 * server-side-only design.
 *
 * Layout: slots 0-35 = bot's main inventory (index 0-8 is its hotbar, same as
 * InventoryPlayer itself). Slots 36-39 = armor, mapped 1:1 onto
 * InventoryPlayer.armorInventory (index 0=boots, 1=leggings, 2=chestplate,
 * 3=helmet - confirmed via decompiling ContainerPlayer's constructor, which
 * wires GUI slot i in 0..3 to inventory index getSizeInventory()-1-i with
 * ItemArmor.armorType == i, and InventoryPlayer.getStackInSlot maps
 * index>=36 straight onto armorInventory[index-36] with no reordering).
 * Slots 40-44 are unused padding (rejects everything) so the total is a
 * clean 45 = 5*9, which vanilla's generic container GUI renders correctly.
 */
public class BotInventoryView implements IInventory {

    public static final int MAIN_SIZE = 36;
    public static final int ARMOR_SIZE = 4;
    public static final int SIZE = 45;

    private final InventoryPlayer inv;

    public BotInventoryView(InventoryPlayer inv) {
        this.inv = inv;
    }

    /** armorInventory[k] holds ItemArmor.armorType == (3 - k). */
    private static int armorTypeForSlot(int armorSlot) {
        return 3 - armorSlot;
    }

    @Override
    public int getSizeInventory() {
        return SIZE;
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        if (slot < MAIN_SIZE) return inv.mainInventory[slot];
        int armorSlot = slot - MAIN_SIZE;
        if (armorSlot < ARMOR_SIZE) return inv.armorInventory[armorSlot];
        return null;
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount) {
        ItemStack stack = getStackInSlot(slot);
        if (stack == null) return null;

        ItemStack split;
        if (stack.stackSize <= amount) {
            split = stack;
            setInventorySlotContents(slot, null);
        } else {
            split = stack.splitStack(amount);
            if (stack.stackSize <= 0) {
                setInventorySlotContents(slot, null);
            }
        }
        return split;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot) {
        ItemStack stack = getStackInSlot(slot);
        setInventorySlotContents(slot, null);
        return stack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack stack) {
        if (slot < MAIN_SIZE) {
            inv.mainInventory[slot] = stack;
        } else {
            int armorSlot = slot - MAIN_SIZE;
            if (armorSlot < ARMOR_SIZE) {
                inv.armorInventory[armorSlot] = stack;
            }
            // slots 40-44: padding, silently ignored (isItemValidForSlot already
            // refuses to let the client place anything there in the first place)
        }
    }

    @Override
    public String getInventoryName() {
        return BotPlayerManager.getBotName() + "'s Inventory";
    }

    @Override
    public boolean hasCustomInventoryName() {
        return true;
    }

    @Override
    public int getInventoryStackLimit() {
        return 64;
    }

    @Override
    public void markDirty() {
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer player) {
        return BotPlayerManager.getActive() != null;
    }

    @Override
    public void openInventory() {
    }

    @Override
    public void closeInventory() {
    }

    @Override
    public boolean isItemValidForSlot(int slot, ItemStack stack) {
        if (slot < MAIN_SIZE) return true;
        int armorSlot = slot - MAIN_SIZE;
        if (armorSlot >= ARMOR_SIZE) return false; // padding slots take nothing
        if (stack == null || !(stack.getItem() instanceof ItemArmor)) return false;
        return ((ItemArmor) stack.getItem()).armorType == armorTypeForSlot(armorSlot);
    }
}
