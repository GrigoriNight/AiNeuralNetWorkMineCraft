package com.aibot.schematic;

import cpw.mods.fml.common.FMLCommonHandler;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Capture/save/load for schematics - <server_root>/aibot/schematics/<name>.schem.
 * Same FORMAT_MAGIC-guarded binary format as BrainDataset/BotPlayerManager's other
 * .dat files in this project (see project history: an earlier unversioned format
 * change once silently corrupted a live save by misreading old-format bytes) - any
 * file not starting with FORMAT_MAGIC is discarded as unreadable rather than parsed.
 */
public class SchematicManager {

    private static final int FORMAT_MAGIC = 0x41494253; // "AIBS"

    /**
     * Scans the axis-aligned region between the two given corners (inclusive) and
     * records every non-air block, offset relative to the region's minimum corner.
     * Air is skipped deliberately - replay only ever places blocks, never clears
     * space, so capturing air would do nothing but bloat the file.
     */
    public static Schematic capture(World world, int x1, int y1, int z1, int x2, int y2, int z2, String name) {
        int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);

        List<Schematic.BlockEntry> blocks = new ArrayList<Schematic.BlockEntry>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block block = world.getBlock(x, y, z);
                    if (block.getMaterial() == Material.air) continue;
                    int meta = world.getBlockMetadata(x, y, z);
                    String regName = Block.blockRegistry.getNameForObject(block);
                    blocks.add(new Schematic.BlockEntry(x - minX, y - minY, z - minZ, regName, meta));
                }
            }
        }
        return new Schematic(name, blocks);
    }

    public static void save(Schematic schem) {
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(getDir(), schem.name + ".schem"))));
            out.writeInt(FORMAT_MAGIC);
            out.writeUTF(schem.name);
            out.writeInt(schem.blocks.size());
            for (Schematic.BlockEntry entry : schem.blocks) {
                out.writeInt(entry.dx);
                out.writeInt(entry.dy);
                out.writeInt(entry.dz);
                out.writeUTF(entry.blockName);
                out.writeInt(entry.meta);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static Schematic load(String name) {
        File file = new File(getDir(), name + ".schem");
        if (!file.exists()) return null;

        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
            if (in.readInt() != FORMAT_MAGIC) return null;
            String storedName = in.readUTF();
            int count = in.readInt();
            List<Schematic.BlockEntry> blocks = new ArrayList<Schematic.BlockEntry>(count);
            for (int i = 0; i < count; i++) {
                int dx = in.readInt();
                int dy = in.readInt();
                int dz = in.readInt();
                String blockName = in.readUTF();
                int meta = in.readInt();
                blocks.add(new Schematic.BlockEntry(dx, dy, dz, blockName, meta));
            }
            return new Schematic(storedName, blocks);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static List<String> listNames() {
        List<String> names = new ArrayList<String>();
        File[] files = getDir().listFiles();
        if (files == null) return names;
        for (File f : files) {
            String n = f.getName();
            if (n.endsWith(".schem")) {
                names.add(n.substring(0, n.length() - ".schem".length()));
            }
        }
        return names;
    }

    private static File getDir() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        File base = server != null ? server.getFile("aibot") : new File("aibot");
        File dir = new File(base, "schematics");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }
}
