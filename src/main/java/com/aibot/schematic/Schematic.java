package com.aibot.schematic;

import java.util.List;

/** A captured build blueprint - a name plus a list of relative-offset block placements. */
public class Schematic {

    /** Block identity stored by registry name (not numeric ID) - IDs aren't guaranteed stable across different mod sets/world saves, registry names are. */
    public static class BlockEntry {
        public final int dx;
        public final int dy;
        public final int dz;
        public final String blockName;
        public final int meta;

        public BlockEntry(int dx, int dy, int dz, String blockName, int meta) {
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.blockName = blockName;
            this.meta = meta;
        }
    }

    public final String name;
    public final List<BlockEntry> blocks;

    public Schematic(String name, List<BlockEntry> blocks) {
        this.name = name;
        this.blocks = blocks;
    }
}
