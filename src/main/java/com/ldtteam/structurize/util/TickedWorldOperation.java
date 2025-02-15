package com.ldtteam.structurize.util;

import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.placement.BlockPlacementResult;
import com.ldtteam.structurize.placement.StructurePhasePlacementResult;
import com.ldtteam.structurize.placement.StructurePlacer;
import com.mojang.authlib.GameProfile;
import net.minecraft.block.*;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.properties.BedPart;
import net.minecraft.state.properties.DoubleBlockHalf;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.util.FakePlayer;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static com.ldtteam.structurize.placement.AbstractBlueprintIterator.NULL_POS;

/**
 * Contains an operation, as remove block, replace block, place structure, etc.
 */
public class TickedWorldOperation
{
    /**
     * Scan tool operation types.
     */
    public enum OperationType
    {
        REMOVE_BLOCK,
        REPLACE_BLOCK,
        REMOVE_ENTITY,
        SCAN,
        PLACE_STRUCTURE,
        UNDO,
        REDO
    }

    /**
     * The operation type.
     */
    private final OperationType operation;

    /**
     * The current position to start iterating.
     */
    private BlockPos startPos;

    /**
     * The current position to start iterating.
     */
    private BlockPos currentPos;

    /**
     * The end position.
     */
    private final BlockPos endPos;

    /**
     * The creator of the operation.
     */
    @Nullable
    private PlayerEntity player = null;

    /**
     * The changeStorage associated to this operation..
     */
    private final ChangeStorage storage;

    /**
     * The undostorage for undo's
     */
    private ChangeStorage undoStorage;

    /**
     * The block to remove or to replace.
     */
    private final ItemStack firstBlock;

    /**
     * The block to replace it with.
     */
    private final ItemStack secondBlock;

    /**
     * The structure wrapper if structure place.
     */
    private final StructurePlacer placer;

    /**
     * The phase the placmeent is in.
     */
    private int structurePhase = 0;

    /**
     * Operation percentage.
     */
    private int pct;

    /**
     * Create a ScanToolOperation.
     *
     * @param type        the type.
     * @param startPos    the start position.
     * @param endPos      the end position.
     * @param player      the player who triggered the event.
     * @param firstBlock  the block being altered.
     * @param secondBlock the block it will be replaced with.
     * @param pct         the percentage of positions to execute the operation on.
     */
    public TickedWorldOperation(
      final OperationType type,
      final BlockPos startPos,
      final BlockPos endPos,
      @Nullable final PlayerEntity player,
      final ItemStack firstBlock,
      final ItemStack secondBlock,
      final int pct)
    {
        this.operation = type;
        this.startPos = new BlockPos(Math.min(startPos.getX(), endPos.getX()), Math.min(startPos.getY(), endPos.getY()), Math.min(startPos.getZ(), endPos.getZ()));
        this.currentPos = new BlockPos(Math.min(startPos.getX(), endPos.getX()), Math.min(startPos.getY(), endPos.getY()), Math.min(startPos.getZ(), endPos.getZ()));
        this.endPos = new BlockPos(Math.max(startPos.getX(), endPos.getX()), Math.max(startPos.getY(), endPos.getY()), Math.max(startPos.getZ(), endPos.getZ()));
        this.player = player;
        this.firstBlock = firstBlock;
        this.secondBlock = secondBlock;
        this.storage = new ChangeStorage(type.toString(), player != null ? player.getUUID() : UUID.randomUUID());
        this.placer = null;
        this.pct = pct;
    }

    /**
     * Create a ScanToolOperation for an UNDO.
     *
     * @param storage the storage for the UNDO.
     * @param player  the player.
     */
    public TickedWorldOperation(final ChangeStorage storage, @Nullable final PlayerEntity player, final OperationType operation)
    {
        this.operation = operation;
        this.startPos = BlockPos.ZERO;
        this.currentPos = BlockPos.ZERO;
        this.endPos = BlockPos.ZERO;
        this.player = player;
        this.firstBlock = ItemStack.EMPTY;
        this.secondBlock = ItemStack.EMPTY;
        this.storage = storage;
        storage.resetUnRedo();
        if (operation == OperationType.UNDO && storage.getOperation().indexOf(TickedWorldOperation.OperationType.UNDO.toString()) != 0)
        {
            undoStorage = new ChangeStorage(operation.toString() + ":" + storage.getOperation(), player != null ? player.getUUID() : UUID.randomUUID());
        }
        this.placer = null;
    }

    /**
     * Create a ScanToolOperation for an structure placement.
     *
     * @param placer the structure for the placement..
     * @param player the player.
     */
    public TickedWorldOperation(final StructurePlacer placer, @Nullable final PlayerEntity player)
    {
        this.operation = OperationType.PLACE_STRUCTURE;
        this.startPos = BlockPos.ZERO;
        this.currentPos = NULL_POS;
        this.endPos = BlockPos.ZERO;
        this.player = player;
        this.firstBlock = ItemStack.EMPTY;
        this.secondBlock = ItemStack.EMPTY;
        this.storage = new ChangeStorage(operation + ":" + placer.getHandler().getBluePrint().getName(), player != null ? player.getUUID() : UUID.randomUUID());
        this.placer = placer;
    }

    /**
     * Apply the operation on the world.
     *
     * @param world the world to apply them on.
     * @return true if finished.
     */
    public boolean apply(final ServerWorld world)
    {
        if (player != null && player.level.dimension() != world.dimension())
        {
            return false;
        }

        if (operation == OperationType.UNDO)
        {
            return storage.undo(world, undoStorage);
        }

        if (operation == OperationType.REDO)
        {
            return storage.redo(world);
        }

        if (operation == OperationType.PLACE_STRUCTURE)
        {
            if (placer.getHandler().getWorld().dimension().location().equals(world.dimension().location()))
            {
                StructurePhasePlacementResult result;
                switch (structurePhase)
                {
                    case 0:
                        //water
                        result = placer.executeStructureStep(world, storage, currentPos, StructurePlacer.Operation.WATER_REMOVAL,
                          () -> placer.getIterator().decrement((info, pos, handler) -> info.getBlockInfo().getState().canOcclude()), false);

                        currentPos = result.getIteratorPos();
                        break;
                    case 1:
                        //structure
                        result = placer.executeStructureStep(world, storage, currentPos, StructurePlacer.Operation.BLOCK_PLACEMENT,
                          () -> placer.getIterator().increment((info, pos, handler) -> !info.getBlockInfo().getState().getMaterial().isSolid()), false);

                        currentPos = result.getIteratorPos();
                        break;
                    case 2:
                        // not solid
                        result = placer.executeStructureStep(world, storage, currentPos, StructurePlacer.Operation.BLOCK_PLACEMENT,
                          () -> placer.getIterator().increment((info, pos, handler) -> info.getBlockInfo().getState().getMaterial().isSolid()), false);
                        currentPos = result.getIteratorPos();
                        break;
                    default:
                        // entities
                        result = placer.executeStructureStep(world, storage, currentPos, StructurePlacer.Operation.BLOCK_PLACEMENT,
                          () -> placer.getIterator().increment((info, pos, handler) -> info.getEntities().length == 0), true);
                        currentPos = result.getIteratorPos();
                        break;
                }

                if (result.getBlockResult().getResult() == BlockPlacementResult.Result.FINISHED)
                {
                    structurePhase++;
                    if (structurePhase > 3)
                    {
                        structurePhase = 0;
                        currentPos = null;
                    }
                }

                return currentPos == null;
            }
            return false;
        }

        return run(world);
    }

    /**
     * Run the operation up to a max count.
     *
     * @param world the world to run it in.
     * @return true if finished.
     */
    private boolean run(final ServerWorld world)
    {
        final FakePlayer fakePlayer = new FakePlayer(world, new GameProfile(player == null ? UUID.randomUUID() : player.getUUID(), "structurizefakeplayer"));
        int count = 0;
        for (int y = currentPos.getY(); y <= endPos.getY(); y++)
        {
            for (int x = currentPos.getX(); x <= endPos.getX(); x++)
            {
                for (int z = currentPos.getZ(); z <= endPos.getZ(); z++)
                {
                    final BlockPos here = new BlockPos(x, y, z);
                    final BlockState blockState = world.getBlockState(here);
                    final ItemStack stack = BlockUtils.getItemStackFromBlockState(blockState);
                    if (correctBlockToRemoveOrReplace(stack, blockState, firstBlock))
                    {
                        if (pct < 100 && fakePlayer.getRandom().nextInt(100) > pct)
                        {
                            continue;
                        }
                        if ((blockState.getBlock() instanceof DoorBlock && blockState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER)
                              || (blockState.getBlock() instanceof BedBlock && blockState.getValue(BedBlock.PART) == BedPart.HEAD))
                        {
                            continue;
                        }
                        count++;

                        storage.addPreviousDataFor(here, world);
                        if (operation != OperationType.REPLACE_BLOCK && (blockState.getBlock() instanceof IBucketPickupHandler
                                                                           || blockState.getBlock() instanceof FlowingFluidBlock))
                        {
                            BlockUtils.removeFluid(world, here);
                            if (firstBlock.getItem() instanceof BucketItem && !(blockState.getBlock() instanceof FlowingFluidBlock))
                            {
                                if (count >= Structurize.getConfig().getServer().maxOperationsPerTick.get())
                                {
                                    currentPos = new BlockPos(x, y, z);
                                    return false;
                                }
                                else
                                {
                                    continue;
                                }
                            }
                        }

                        if (operation == OperationType.REPLACE_BLOCK)
                        {
                            BlockUtils.handleCorrectBlockPlacement(world, fakePlayer, secondBlock, blockState, here);
                        }
                        else
                        {
                            world.removeBlock(here, false);
                        }

                        storage.addPostDataFor(here, world);

                        if (count >= Structurize.getConfig().getServer().maxOperationsPerTick.get())
                        {
                            currentPos = new BlockPos(x, y, z);
                            return false;
                        }
                    }
                }
                currentPos = new BlockPos(x, y, startPos.getZ());
            }
            currentPos = new BlockPos(startPos.getX(), y, startPos.getZ());
        }
        return true;
    }

    /**
     * Is this the correct block to remove it or replace it.
     *
     * @param replacementStack the world stack to check.
     * @param worldState       the world state to check.
     * @param compareStack     the comparison stack.
     * @return true if so.
     */
    private static boolean correctBlockToRemoveOrReplace(final ItemStack replacementStack, final BlockState worldState, final ItemStack compareStack)
    {
        return (replacementStack != null && replacementStack.sameItem(compareStack)
                  || (compareStack.getItem() instanceof BucketItem && ((BucketItem) compareStack.getItem()).getFluid() == worldState.getFluidState().getType())
                  || (compareStack.getItem() instanceof BucketItem && worldState.getBlock() instanceof FlowingFluidBlock
                        && ((BucketItem) compareStack.getItem()).getFluid() == ((FlowingFluidBlock) worldState.getBlock()).getFluid())
                  || (compareStack.getItem() == Items.AIR && (worldState.getBlock() == Blocks.AIR)));
    }

    /**
     * Get the current change storage of this operation.
     *
     * @return the ChangeStorage object.
     */
    public ChangeStorage getChangeStorage()
    {
        return this.storage;
    }

    /**
     * Check if operation is an undo already.
     *
     * @return true if so.
     */
    public boolean isUndoRedo()
    {
        return operation == OperationType.UNDO || operation == OperationType.REDO;
    }
}
