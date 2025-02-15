package com.ldtteam.structurize.network;

import com.ldtteam.structurize.api.util.Log;
import com.ldtteam.structurize.api.util.constant.Constants;
import com.ldtteam.structurize.network.messages.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.network.NetworkEvent.Context;
import net.minecraftforge.fml.network.NetworkRegistry;
import net.minecraftforge.fml.network.PacketDistributor;
import net.minecraftforge.fml.network.PacketDistributor.TargetPoint;
import net.minecraftforge.fml.network.simple.SimpleChannel;

import java.util.function.Function;

/**
 * Our wrapper for Forge network layer
 */
public class NetworkChannel
{
    private static final String LATEST_PROTO_VER = "1.1";
    private static final String ACCEPTED_PROTO_VERS = LATEST_PROTO_VER;
    /**
     * Forge network channel
     */
    private final SimpleChannel rawChannel;

    /**
     * Creates a new instance of network channel.
     *
     * @param channelName unique channel name
     * @throws IllegalArgumentException if channelName already exists
     */
    public NetworkChannel(final String channelName)
    {
        rawChannel = NetworkRegistry.newSimpleChannel(new ResourceLocation(Constants.MOD_ID, channelName), () -> LATEST_PROTO_VER, ACCEPTED_PROTO_VERS::equals, ACCEPTED_PROTO_VERS::equals);
    }

    /**
     * Registers all common messages.
     */
    public void registerCommonMessages()
    {
        int idx = 0;
        registerMessage(++idx, MultiBlockChangeMessage.class, MultiBlockChangeMessage::new);
        registerMessage(++idx, BuildToolPasteMessage.class, BuildToolPasteMessage::new);
        registerMessage(++idx, GenerateAndPasteMessage.class, GenerateAndPasteMessage::new);
        registerMessage(++idx, GenerateAndSaveMessage.class, GenerateAndSaveMessage::new);
        registerMessage(++idx, LSStructureDisplayerMessage.class, LSStructureDisplayerMessage::new);
        registerMessage(++idx, RemoveBlockMessage.class, RemoveBlockMessage::new);
        registerMessage(++idx, RemoveEntityMessage.class, RemoveEntityMessage::new);
        registerMessage(++idx, SaveScanMessage.class, SaveScanMessage::new);
        registerMessage(++idx, ReplaceBlockMessage.class, ReplaceBlockMessage::new);
        registerMessage(++idx, ScanOnServerMessage.class, ScanOnServerMessage::new);
        registerMessage(++idx, SchematicRequestMessage.class, SchematicRequestMessage::new);
        registerMessage(++idx, SchematicSaveMessage.class, SchematicSaveMessage::new);
        registerMessage(++idx, ServerUUIDMessage.class, ServerUUIDMessage::new);
        registerMessage(++idx, StructurizeStylesMessage.class, StructurizeStylesMessage::new);
        registerMessage(++idx, UndoRedoMessage.class, UndoRedoMessage::new);
        registerMessage(++idx, UpdateScanToolMessage.class, UpdateScanToolMessage::new);
        registerMessage(++idx, AddRemoveTagMessage.class, AddRemoveTagMessage::new);
        registerMessage(++idx, SetTagInTool.class, SetTagInTool::new);
        registerMessage(++idx, OperationHistoryMessage.class, OperationHistoryMessage::new);
    }

    /**
     * Register a message into rawChannel.
     *
     * @param <MSG>    message class type
     * @param id       network id
     * @param msgClazz message class
     */
    private <MSG extends IMessage> void registerMessage(final int id, final Class<MSG> msgClazz, final Function<PacketBuffer, MSG> initializer)
    {
        rawChannel.registerMessage(id, msgClazz, IMessage::toBytes, initializer, (msg, ctxIn) -> {
            final Context ctx = ctxIn.get();
            final LogicalSide packetOrigin = ctx.getDirection().getOriginationSide();
            ctx.setPacketHandled(true);
            if (msg.getExecutionSide() != null && packetOrigin.equals(msg.getExecutionSide()))
            {
                Log.getLogger().warn("Receving {} at wrong side!", msg.getClass().getName());
                return;
            }
            // boolean param MUST equals true if packet arrived at logical server
            ctx.enqueueWork(() -> msg.onExecute(ctx, packetOrigin.equals(LogicalSide.CLIENT)));
        });
    }

    /**
     * Sends to server.
     *
     * @param msg message to send
     */
    public void sendToServer(final IMessage msg)
    {
        rawChannel.sendToServer(msg);
    }

    /**
     * Sends to player.
     *
     * @param msg    message to send
     * @param player target player
     */
    public void sendToPlayer(final IMessage msg, final ServerPlayerEntity player)
    {
        rawChannel.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /**
     * Sends to origin client.
     *
     * @param msg message to send
     * @param ctx network context
     */
    public void sendToOrigin(final IMessage msg, final Context ctx)
    {
        final ServerPlayerEntity player = ctx.getSender();
        if (player != null) // side check
        {
            sendToPlayer(msg, player);
        }
        else
        {
            sendToServer(msg);
        }
    }

    /**
     * Sends to everyone in dimension.
     *
     * @param msg message to send
     * @param dim target dimension
     */
    /*
     * TODO: 1.16 waiting forge update
     * public void sendToDimension(final IMessage msg, final DimensionType dim)
     * {
     * rawChannel.send(PacketDistributor.DIMENSION.with(() -> dim), msg);
     * }
     */

    /**
     * Sends to everyone in circle made using given target point.
     *
     * @param msg message to send
     * @param pos target position and radius
     * @see TargetPoint
     */
    public void sendToPosition(final IMessage msg, final TargetPoint pos)
    {
        rawChannel.send(PacketDistributor.NEAR.with(() -> pos), msg);
    }

    /**
     * Sends to everyone.
     *
     * @param msg message to send
     */
    public void sendToEveryone(final IMessage msg)
    {
        rawChannel.send(PacketDistributor.ALL.noArg(), msg);
    }

    /**
     * Sends to everyone who is in range from entity's pos using formula below.
     *
     * <pre>
     * Math.min(Entity.getType().getTrackingRange(), ChunkManager.this.viewDistance - 1) * 16;
     * </pre>
     *
     * as of 24-06-2019
     *
     * @param msg    message to send
     * @param entity target entity to look at
     */
    public void sendToTrackingEntity(final IMessage msg, final Entity entity)
    {
        rawChannel.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), msg);
    }

    /**
     * Sends to everyone (including given entity) who is in range from entity's pos using formula below.
     *
     * <pre>
     * Math.min(Entity.getType().getTrackingRange(), ChunkManager.this.viewDistance - 1) * 16;
     * </pre>
     *
     * as of 24-06-2019
     *
     * @param msg    message to send
     * @param entity target entity to look at
     */
    public void sendToTrackingEntityAndSelf(final IMessage msg, final Entity entity)
    {
        rawChannel.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), msg);
    }

    /**
     * Sends to everyone in given chunk.
     *
     * @param msg   message to send
     * @param chunk target chunk to look at
     */
    public void sendToTrackingChunk(final IMessage msg, final Chunk chunk)
    {
        rawChannel.send(PacketDistributor.TRACKING_CHUNK.with(() -> chunk), msg);
    }
}
