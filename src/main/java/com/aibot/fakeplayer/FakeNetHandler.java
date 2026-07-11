package com.aibot.fakeplayer;

import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;

/**
 * Stands in for a real client connection. Backed by a Netty EmbeddedChannel
 * instead of a real socket - this matters because plenty of mods broadcast
 * packets to "all players" (SimpleNetworkWrapper.sendToAll/sendToDimension)
 * every tick, and that code reaches into each player's NetworkManager channel
 * directly. A null channel there throws mid-broadcast and can take down other
 * players' connections with it; an EmbeddedChannel is non-null and safely
 * swallows whatever gets written to it instead.
 */
public class FakeNetHandler extends NetHandlerPlayServer {

    private final EmbeddedChannel channel;

    public FakeNetHandler(MinecraftServer server, EntityPlayerMP player) {
        this(server, player, new NetworkManager(false));
    }

    private FakeNetHandler(MinecraftServer server, EntityPlayerMP player, NetworkManager networkManager) {
        super(server, networkManager, player);
        this.channel = new EmbeddedChannel(networkManager);
    }

    /** Call every tick: discards whatever other mods wrote to this fake connection so it can't leak memory. */
    public void drainOutbound() {
        channel.outboundMessages().clear();
    }

    @Override
    public void sendPacket(Packet packet) {
    }

    @Override
    public void onNetworkTick() {
    }
}
