package com.aibot.fakeplayer;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Real A* pathfinding over a bounded local voxel grid, per explicit "make
 * something like [SoulFire's real A* pathfinding], for the network to feed
 * it" request. SoulFire itself (a scripted multi-bot server-testing tool,
 * AGPL-3.0 licensed) wasn't ported or copied from in any way - just the
 * general idea of "actually search for a walkable route instead of walking
 * in a straight line and reacting to obstacles after the fact," which is
 * this mod's existing, documented limitation (see BotPlayerAI's class
 * comment) and was the direct cause of a real bug found earlier tonight (a
 * bot stuck standing in water for minutes, unable to route around it).
 *
 * Bounded by both a search radius and a node-expansion budget specifically
 * because this runs on the single-threaded server tick loop and a real
 * search has to stay cheap enough not to cause lag, unlike SoulFire's
 * standalone client-side bot processes with no such constraint. Callers are
 * expected to compute a path once (not every tick) and follow the returned
 * waypoints over many subsequent ticks - see BotPlayerAI's path-following
 * helper.
 *
 * A node is the block position a bot's feet occupy. Standable means the feet
 * and head blocks are passable and non-hazardous, and the block below is
 * solid ground. Same hazard rule as BotPlayerAI.isHazardousBlock (all
 * liquids, fire, TNT, cactus, and trap/mine/ward/taint/flux/rift/explosive-
 * named blocks) duplicated locally so this stays usable independent of
 * BotPlayerAI, same reasoning as TrainingBotAI's own local hazard check.
 */
public final class Pathfinder {

    private static final int MAX_SEARCH_RADIUS = 48;
    private static final int MAX_NODES_EXPANDED = 3000;
    private static final int MAX_FALL = 3;

    private static final int[][] HORIZONTAL_DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private Pathfinder() {
    }

    /**
     * Returns a list of waypoints (feet positions) from just after the start
     * to the goal, or null if no route was found within the search radius/
     * node budget - callers must have a straight-line (or other) fallback for
     * the null case, same as every other target-seeking behavior in this mod
     * already does for its own failure modes.
     */
    public static List<ChunkCoordinates> findPath(World world, int startX, int startY, int startZ, int goalX, int goalY, int goalZ) {
        Node startNode = new Node(startX, startY, startZ, null, 0.0);
        PriorityQueue<Node> open = new PriorityQueue<Node>();
        Map<Long, Node> bestKnown = new HashMap<Long, Node>();
        open.add(startNode);
        bestKnown.put(key(startX, startY, startZ), startNode);

        int expanded = 0;
        List<int[]> neighborBuffer = new ArrayList<int[]>(10);

        while (!open.isEmpty() && expanded < MAX_NODES_EXPANDED) {
            Node current = open.poll();
            if (current.closed) continue;
            current.closed = true;
            expanded++;

            if (current.x == goalX && current.y == goalY && current.z == goalZ) {
                return reconstruct(current);
            }
            if (Math.abs(current.x - startX) > MAX_SEARCH_RADIUS || Math.abs(current.z - startZ) > MAX_SEARCH_RADIUS) {
                continue;
            }

            neighborBuffer.clear();
            collectNeighbors(world, current.x, current.y, current.z, neighborBuffer);

            for (int[] n : neighborBuffer) {
                int nx = n[0], ny = n[1], nz = n[2];
                double dx = nx - current.x, dy = ny - current.y, dz = nz - current.z;
                double stepCost = Math.sqrt(dx * dx + dy * dy + dz * dz);
                double g = current.g + stepCost;

                long k = key(nx, ny, nz);
                Node existing = bestKnown.get(k);
                if (existing != null && existing.g <= g) continue;

                Node next = new Node(nx, ny, nz, current, g);
                next.f = g + heuristic(nx, ny, nz, goalX, goalY, goalZ);
                bestKnown.put(k, next);
                open.add(next);
            }
        }
        return null;
    }

    private static void collectNeighbors(World world, int x, int y, int z, List<int[]> out) {
        for (int[] dir : HORIZONTAL_DIRS) {
            int nx = x + dir[0];
            int nz = z + dir[1];

            // Prevent cutting corners diagonally through a solid wall.
            if (dir[0] != 0 && dir[1] != 0) {
                if (!isPassable(world, x + dir[0], y, z) || !isPassable(world, x, y, z + dir[1])) {
                    continue;
                }
            }

            if (isStandable(world, nx, y, nz)) {
                out.add(new int[]{nx, y, nz});
                continue;
            }
            if (isStandable(world, nx, y + 1, nz) && isPassable(world, x, y + 2, z)) {
                out.add(new int[]{nx, y + 1, nz});
                continue;
            }
            for (int dy = 1; dy <= MAX_FALL; dy++) {
                if (isStandable(world, nx, y - dy, nz)) {
                    out.add(new int[]{nx, y - dy, nz});
                    break;
                }
                if (!isPassable(world, nx, y - dy, nz)) break;
            }
        }
    }

    private static boolean isStandable(World world, int x, int y, int z) {
        if (!isPassable(world, x, y, z) || !isPassable(world, x, y + 1, z)) return false;
        Block below = world.getBlock(x, y - 1, z);
        return below.getMaterial().isSolid() && !isHazardous(below);
    }

    private static boolean isPassable(World world, int x, int y, int z) {
        Block block = world.getBlock(x, y, z);
        if (isHazardous(block)) return false;
        return !block.getMaterial().isSolid();
    }

    private static boolean isHazardous(Block block) {
        Material m = block.getMaterial();
        if (m.isLiquid() || m == Material.fire || m == Material.tnt || m == Material.cactus) {
            return true;
        }
        String name = block.getUnlocalizedName().toLowerCase();
        return name.contains("trap") || name.contains("mine") || name.contains("ward")
                || name.contains("taint") || name.contains("flux") || name.contains("rift")
                || name.contains("explos");
    }

    private static double heuristic(int x, int y, int z, int goalX, int goalY, int goalZ) {
        double dx = goalX - x, dy = goalY - y, dz = goalZ - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long key(int x, int y, int z) {
        return (((long) (x & 0x3FFFFF)) << 42) | (((long) (z & 0x3FFFFF)) << 20) | (y & 0xFFFFF);
    }

    private static List<ChunkCoordinates> reconstruct(Node end) {
        List<ChunkCoordinates> path = new ArrayList<ChunkCoordinates>();
        Node n = end;
        while (n != null) {
            path.add(new ChunkCoordinates(n.x, n.y, n.z));
            n = n.parent;
        }
        Collections.reverse(path);
        return path;
    }

    private static final class Node implements Comparable<Node> {
        final int x, y, z;
        final Node parent;
        final double g;
        double f;
        boolean closed = false;

        Node(int x, int y, int z, Node parent, double g) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.parent = parent;
            this.g = g;
            this.f = g;
        }

        @Override
        public int compareTo(Node other) {
            return Double.compare(this.f, other.f);
        }
    }
}
