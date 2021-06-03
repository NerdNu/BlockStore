package net.sothatsit.blockstore.chunkstore;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.bukkit.World;

import net.sothatsit.blockstore.util.Checks;

/**
 * A {@link ChunkStore} implementation that delegates to an asynchronously
 * loaded {@link LoadedChunkStore}.
 *
 * LoadingChunkStore has a {@link #delegate} member that refers to a
 * {@link LoadedChunkStore}. That reference is null until the
 * {@link LoadedChunkStore} is fully loaded.
 *
 * {@link ChunkManager#loadStore(ChunkLoc)} loads the delegate
 * {@link LoadedChunkStore} in another thread (see
 * {@link ChunkManager#loadStoreSync(ChunkLoc)}, and then sets the
 * {@link #delegate} reference to point to it, signifying that this
 * {@link LoadingChunkStore} can now use the delegate.
 */
public class LoadingChunkStore extends ChunkStore {
    /**
     * Synchronises access to internal state (fields).
     */
    private final Object lock = new Object();

    private final CountDownLatch latch = new CountDownLatch(1);

    private final AtomicReference<ChunkStore> delegate = new AtomicReference<>();
    private List<Action> pendingActions = new ArrayList<>();
    private List<Consumer<ChunkStore>> onLoad = new ArrayList<>();

    public LoadingChunkStore(World world, ChunkLoc chunkLoc) {
        super(world, chunkLoc);
    }

    public void await() {
        try {
            boolean success = latch.await(1, TimeUnit.SECONDS);

            if (!success) {
                throw new RuntimeException("Over one second elapsed waiting for the store to load");
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Thread interrupted waiting for store to load", e);
        }
    }

    public boolean hasLoaded() {
        return delegate.get() != null;
    }

    public void onLoad(Consumer<ChunkStore> consumer) {
        Checks.ensureNonNull(consumer, "consumer");

        boolean run;

        synchronized (lock) {
            run = hasLoaded();

            if (!run) {
                onLoad.add(consumer);
            }
        }

        if (run) {
            consumer.accept(getDelegate());
        }
    }

    public ChunkStore getDelegate() {
        return delegate.get();
    }

    protected void setDelegate(ChunkStore delegate) {
        Checks.ensureNonNull(delegate, "delegate");
        Checks.ensureTrue(!hasLoaded(), "Already has a delegate");
        Checks.ensureTrue(getWorld() == delegate.getWorld(), "Must be in the same world");
        Checks.ensureTrue(getChunkLoc().equals(delegate.getChunkLoc()), "Must be the same chunk");

        List<Action> pendingActions;
        List<Consumer<ChunkStore>> onLoad;

        synchronized (lock) {
            this.delegate.set(delegate);

            pendingActions = this.pendingActions;
            this.pendingActions = null;

            onLoad = this.onLoad;
            this.onLoad = null;
        }

        for (Action action : pendingActions) {
            action.apply(delegate);
        }

        latch.countDown();

        for (Consumer<ChunkStore> consumer : onLoad) {
            try {
                consumer.accept(delegate);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void queueAction(Action action) {
        synchronized (lock) {
            if (hasLoaded()) {
                action.apply(getDelegate());
                return;
            }

            pendingActions.add(action);
        }
    }

    @Override
    protected void setLastUse() {
        synchronized (lock) {
            if (hasLoaded()) {
                getDelegate().setLastUse();
                return;
            }

            super.setLastUse();
        }
    }

    @Override
    public long getTimeSinceUse() {
        synchronized (lock) {
            return (hasLoaded() ? getDelegate().getTimeSinceUse() : super.getTimeSinceUse());
        }
    }

    @Override
    public boolean isDirty() {
        synchronized (lock) {
            return (hasLoaded() ? getDelegate().isDirty() : pendingActions.size() > 0);
        }
    }

    @Override
    public boolean isPlaced(BlockLoc location) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        await();

        return getDelegate().isPlaced(location);
    }

    @Override
    public void setPlaced(BlockLoc location, boolean value) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        setLastUse();

        queueAction(new SetPlacedAction(location, value));
    }

    @Override
    public Object getMetaValue(BlockLoc location, int plugin, int key) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        await();

        return getDelegate().getMetaValue(location, plugin, key);
    }

    @Override
    public Map<Integer, Object> getMetaValues(BlockLoc location, int plugin) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        await();

        return getDelegate().getMetaValues(location, plugin);
    }

    @Override
    public Map<Integer, Map<Integer, Object>> getMetaValues(BlockLoc location) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        await();

        return getDelegate().getMetaValues(location);
    }

    @Override
    public void setMetaValue(BlockLoc location, int plugin, int key, Object value) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        setLastUse();

        queueAction(new SetMetaValueAction(location, plugin, key, value));
    }

    @Override
    public void removeMetaValue(BlockLoc location, int plugin, int key) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        setLastUse();

        queueAction(new RemoveMetaValueAction(location, plugin, key));
    }

    @Override
    protected BlockMeta getBlockState(BlockLoc location) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");

        await();

        return getDelegate().getBlockState(location);
    }

    @Override
    protected void setBlockState(BlockLoc location, BlockMeta meta) {
        Checks.ensureTrue(isInChunk(location), "location is not in this chunk");
        Checks.ensureNonNull(meta, "meta");

        setLastUse();

        queueAction(new SetBlockStateAction(location, meta));
    }

    @Override
    public boolean isEmpty() {
        await();

        return getDelegate().isEmpty();
    }

    @Override
    public void write(ObjectOutputStream stream) throws IOException {
        await();

        getDelegate().write(stream);
    }

    private interface Action {

        public void apply(ChunkStore store);

    }

    private class SetPlacedAction implements Action {

        private final BlockLoc location;
        private final boolean value;

        public SetPlacedAction(BlockLoc location, boolean value) {
            this.location = location;
            this.value = value;
        }

        @Override
        public void apply(ChunkStore store) {
            store.setPlaced(location, value);
        }

    }

    private class SetMetaValueAction implements Action {

        private final BlockLoc location;
        private final int plugin;
        private final int key;
        private final Object value;

        public SetMetaValueAction(BlockLoc location, int plugin, int key, Object value) {
            this.location = location;
            this.plugin = plugin;
            this.key = key;
            this.value = value;
        }

        @Override
        public void apply(ChunkStore store) {
            store.setMetaValue(location, plugin, key, value);
        }

    }

    private class RemoveMetaValueAction implements Action {

        private final BlockLoc location;
        private final int plugin;
        private final int key;

        public RemoveMetaValueAction(BlockLoc location, int plugin, int key) {
            this.location = location;
            this.plugin = plugin;
            this.key = key;
        }

        @Override
        public void apply(ChunkStore store) {
            store.removeMetaValue(location, plugin, key);
        }

    }

    private class SetBlockStateAction implements Action {

        private final BlockLoc location;
        private final BlockMeta state;

        public SetBlockStateAction(BlockLoc location, BlockMeta state) {
            this.location = location;
            this.state = state;
        }

        @Override
        public void apply(ChunkStore store) {
            store.setBlockState(location, state);
        }

    }

}
