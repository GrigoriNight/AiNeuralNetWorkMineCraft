package com.aibot.schematic;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-player schematic corner selections (pos1/pos2) - UI-only state, like a
 * WorldEdit wand selection, deliberately not persisted to disk (losing a
 * selection on server restart is fine, the player just re-marks it). Shared
 * by /brain schem pos1/pos2 (CommandBrain) and the ToolAI wand's block clicks
 * (PlayerActionRecorder.onPlayerInteract) so both ways of marking corners
 * feed the same save step.
 */
public class SchematicSelection {

    private static final Map<String, int[]> pos1 = new HashMap<String, int[]>();
    private static final Map<String, int[]> pos2 = new HashMap<String, int[]>();

    public static void setPos1(String playerName, int x, int y, int z) {
        pos1.put(playerName, new int[]{x, y, z});
    }

    public static void setPos2(String playerName, int x, int y, int z) {
        pos2.put(playerName, new int[]{x, y, z});
    }

    public static int[] getPos1(String playerName) {
        return pos1.get(playerName);
    }

    public static int[] getPos2(String playerName) {
        return pos2.get(playerName);
    }
}
