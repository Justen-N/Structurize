package com.ldtteam.structurize.util;

import com.ldtteam.structurize.Structurize;
import com.ldtteam.structurize.management.Manager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Change storage to store changes to an area to be able to undo them.
 */
public class ChangeStorage
{
    /**
     * Simple int ID creator
     */
    private static int storageIDs = 0;

    /**
     * This storages unique ID
     */
    private final int id;

    /**
     * List of blocks with position.
     */
    private final Map<BlockPos, BlockChangeData> blocks = new HashMap<>();

    /**
     * List of entities in range.
     */
    private final List<CompoundNBT> removedEntities = new ArrayList<>();

    /**
     * List of entities to kill in range.
     */
    private final List<Entity> addedEntities = new ArrayList<>();

    /**
     * The operation which was done
     */
    private final String operation;

    /**
     * Current operation iteration
     */
    private Iterator<Map.Entry<BlockPos, BlockChangeData>> iterator = null;

    private final UUID player;

    /**
     * Initiate an empty changeStorage to manually fill it.
     *
     * @param player the player owner of it.
     */
    public ChangeStorage(final String operation, final UUID player)
    {
        this.player = player;
        this.id = storageIDs++;
        this.operation = operation;
    }

    /**
     * Inititate the change storage with the world to calc the positions.
     *
     * @param world the world.
     * @param from  the first position.
     * @param to    the second position.
     */
    public ChangeStorage(final World world, final BlockPos from, final BlockPos to, final String operation)
    {
        player = UUID.randomUUID();
        this.id = storageIDs++;
        this.operation = operation;
        for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++)
        {
            for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++)
            {
                for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++)
                {
                    final BlockPos place = new BlockPos(x, y, z);
                    blocks.put(place, new BlockChangeData().withPreState(world.getBlockState(place)).withPreTE(world.getBlockEntity(place)));
                }
            }
        }

        final List<Entity> tempEntities = world.getEntitiesOfClass(Entity.class, new AxisAlignedBB(from, to));
        removedEntities.addAll(tempEntities.stream().map(Entity::serializeNBT).collect(Collectors.toList()));
    }

    /**
     * Add a position storage to the list.
     *
     * @param place the place.
     * @param world the world.
     */
    public void addPreviousDataFor(final BlockPos place, final World world)
    {
        blocks.computeIfAbsent(place, p -> new BlockChangeData()).withPreState(world.getBlockState(place)).withPreTE(world.getBlockEntity(place));
    }

    /**
     * Add a position storage to the list.
     *
     * @param place the place.
     * @param world the world.
     */
    public void addPostDataFor(final BlockPos place, final World world)
    {
        blocks.computeIfAbsent(place, p -> new BlockChangeData()).withPostState(world.getBlockState(place)).withPostTE(world.getBlockEntity(place));
    }

    /**
     * Add entities to list to be readded.
     *
     * @param list the list of entities.
     */
    public void addEntities(final List<Entity> list)
    {
        removedEntities.addAll(list.stream().map(Entity::serializeNBT).collect(Collectors.toList()));
    }

    /**
     * Add a entity to be killed to the list.
     *
     * @param entity the place.
     */
    public void addToBeKilledEntity(final Entity entity)
    {
        addedEntities.add(entity);
    }

    /**
     * Reload the previous state of the positions.
     *
     * @param world       the world to manipulate.
     * @param undoStorage
     * @return true if successful.
     */
    public boolean undo(final World world, @Nullable final ChangeStorage undoStorage)
    {
        if (iterator == null)
        {
            iterator = blocks.entrySet().iterator();
        }

        int count = 0;
        while (iterator.hasNext())
        {
            final Map.Entry<BlockPos, BlockChangeData> entry = iterator.next();
            // Only revert block changes which this operation caused
            if (world.getBlockState(entry.getKey()) != entry.getValue().getPostState())
            {
                continue;
            }

            if (undoStorage != null)
            {
                undoStorage.addPreviousDataFor(entry.getKey(), world);
            }
            world.setBlockAndUpdate(entry.getKey(), entry.getValue().getPreState());

            if (entry.getValue().getPreTE() != null)
            {
                world.setBlockEntity(entry.getKey(), entry.getValue().getPreTE());
            }

            if (undoStorage != null)
            {
                undoStorage.addPostDataFor(entry.getKey(), world);
            }

            count++;

            if (count >= Structurize.getConfig().getServer().maxOperationsPerTick.get())
            {
                return false;
            }
        }

        for (final CompoundNBT data : removedEntities)
        {
            final Optional<EntityType<?>> type = EntityType.by(data);
            if (type.isPresent())
            {
                final Entity entity = type.get().create(world);
                if (entity != null)
                {
                    entity.deserializeNBT(data);
                    world.addFreshEntity(entity);
                    if (undoStorage != null)
                    {
                        undoStorage.addedEntities.add(entity);
                    }
                }
            }
        }
        addedEntities.forEach(Entity::remove);

        if (undoStorage != null)
        {
            Manager.addToUndoRedoCache(undoStorage);
        }
        return true;
    }

    /**
     * Reload the previous state of the positions.
     *
     * @param world the world to manipulate.
     * @return true if successful.
     */
    public boolean redo(final World world)
    {
        int count = 0;

        if (iterator == null)
        {
            iterator = blocks.entrySet().iterator();
        }

        while (iterator.hasNext())
        {
            final Map.Entry<BlockPos, BlockChangeData> entry = iterator.next();
            if (world.getBlockState(entry.getKey()) != entry.getValue().getPreState())
            {
                continue;
            }

            world.setBlockAndUpdate(entry.getKey(), entry.getValue().getPostState());
            if (entry.getValue().getPostTE() != null)
            {
                world.setBlockEntity(entry.getKey(), entry.getValue().getPostTE());
            }
            count++;

            if (count >= Structurize.getConfig().getServer().maxOperationsPerTick.get())
            {
                return false;
            }
        }

        return true;
    }

    /**
     * Get the operation of this changestorage
     *
     * @return
     */
    public String getOperation()
    {
        return operation;
    }

    /**
     * Resets the iteration
     */
    public void resetUnRedo()
    {
        iterator = null;
    }

    /**
     * Check whether the current operation on this is done
     *
     * @return
     */
    public boolean isDone()
    {
        return iterator == null || !iterator.hasNext();
    }

    /**
     * Get this change storages unique ID
     *
     * @return
     */
    public int getID()
    {
        return id;
    }

    /**
     * Get the players ID
     *
     * @return
     */
    public UUID getPlayerID()
    {
        return player;
    }
}
