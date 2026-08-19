package org.teacon.neb.network.chunk.preshare.repo;

import it.unimi.dsi.fastutil.objects.ObjectIterators;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.NonNull;
import org.teacon.neb.utils.GridPos;

import java.util.Iterator;

import static org.teacon.neb.utils.GridPos.GRID_SIZE;

/* package-private */ final class GridIndexer extends ObjectIterators.AbstractIndexBasedIterator<GridIndexer> implements Iterable<GridIndexer> {
    private final int gridX, gridZ;

    public static GridIndexer of(GridPos grid) {
        return new GridIndexer(grid.x(), grid.z());
    }

    public static GridIndexer of(long gridXZ) {
        return new GridIndexer(GridPos.getX(gridXZ), GridPos.getZ(gridXZ));
    }

    public static GridIndexer of(int gridX, int gridZ) {
        return new GridIndexer(gridX, gridZ);
    }

    private GridIndexer(int gridX, int gridZ) {
        super(0, 0);
        this.gridX = gridX;
        this.gridZ = gridZ;
    }

    @NonNull
    @Override
    public Iterator<GridIndexer> iterator() {
        return this;
    }

    private int chunkX, chunkZ, index;

    @Override
    protected GridIndexer get(int location) {
        int dx = location / GRID_SIZE, dz = location % GRID_SIZE;

        index = location;
        chunkX = gridX * GRID_SIZE + dx;
        chunkZ = gridZ * GRID_SIZE + dz;
        return this;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public ChunkPos chunkPos() {
        return new ChunkPos(chunkX, chunkZ);
    }

    public int index() {
        return index;
    }

    @Override
    protected void remove(int location) {
        throw new UnsupportedOperationException();
    }

    @Override
    protected int getMaxPos() {
        return GRID_SIZE * GRID_SIZE;
    }
}
