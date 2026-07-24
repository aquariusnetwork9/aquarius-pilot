package com.aquariuspilot.module;

import com.zenith.feature.player.World;
import dev.babbaj.pathfinder.NetherPathfinder;
import dev.babbaj.pathfinder.PathSegment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Full-route nether planning via babbaj/nether-pathfinder — the native C++ A* Baritone's elytra uses. Ported
 * from AquariusProxy's {@code com.aquarius.module.impl.NetherRouter} with one deliberate change: the
 * observed-chunk tracking set uses plain {@code java.util.HashSet<Long>} instead of the fork's
 * {@code it.unimi.dsi.fastutil.longs.LongOpenHashSet} — that fastutil "longs" package isn't a dependency this
 * plugin can casually add (the fork only had it because ZenithProxy core bundles a slimmed fastutil-maps
 * artifact set for its own use), and the router thread only touches this set a few times a second, so boxing
 * is not a meaningful cost here.
 */
public final class NetherRouter {

    public static final NetherRouter INSTANCE = new NetherRouter();

    public record Route(List<int[]> points, boolean finished) { }

    private static final int MAX_HEIGHT = 128;
    private static final double FAKE_CHUNK_COST = 8.0;
    private static final int CULL_DISTANCE_BLOCKS = 64000;

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "NetherRouter");
        t.setDaemon(true);
        return t;
    });

    private final ReentrantReadWriteLock rwl = new ReentrantReadWriteLock();
    private volatile long context;
    private long contextSeed;
    private final Set<Long> fedChunks = new HashSet<>();
    private int feedErrors;

    private NetherRouter() {}

    public boolean isSupported() {
        return NetherPathfinder.isThisSystemSupported();
    }

    public CompletableFuture<Route> requestRoute(int sx, int sy, int sz, int tx, int ty, int tz,
                                                 long seed, int timeoutMs) {
        final CompletableFuture<Route> f = new CompletableFuture<>();
        exec.execute(() -> {
            rwl.writeLock().lock();
            try {
                ensureContext(seed);
                NetherPathfinder.cullFarChunks(context, sx >> 4, sz >> 4, CULL_DISTANCE_BLOCKS);
                final PathSegment seg = NetherPathfinder.pathFind(context, sx, sy, sz, tx, ty, tz,
                    true, false, timeoutMs, false, FAKE_CHUNK_COST);
                if (seg == null || seg.packed.length == 0) {
                    f.complete(null);
                    return;
                }
                final List<int[]> pts = new ArrayList<>(seg.packed.length);
                for (final long p : seg.packed) {
                    pts.add(new int[]{ (int) (p >> 38), (int) (p << 26 >> 52), (int) (p << 38 >> 38) });
                }
                f.complete(new Route(pts, seg.finished));
            } catch (final Throwable t) {
                f.completeExceptionally(t);
            } finally {
                rwl.writeLock().unlock();
            }
        });
        return f;
    }

    public void submitChunk(int chunkX, int chunkZ, long seed) {
        exec.execute(() -> {
            rwl.writeLock().lock();
            try {
                ensureContext(seed);
                if (World.getChunk(chunkX, chunkZ) == null) return;
                final boolean[] data = new boolean[16 * 16 * 256];
                final int bx0 = chunkX << 4, bz0 = chunkZ << 4;
                for (int y = 0; y < MAX_HEIGHT; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (!World.getBlock(bx0 + x, y, bz0 + z).isAir()) {
                                data[y << 8 | z << 4 | x] = true;
                            }
                        }
                    }
                }
                NetherPathfinder.insertChunkData(context, chunkX, chunkZ, data);
                fedChunks.add(((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL));
            } catch (final Throwable t) {
                if (feedErrors++ == 0) {
                    com.zenith.Globals.MODULE_LOG.warn("[NetherRouter] chunk feed failed (logging first only): {}", t.toString());
                }
            } finally {
                rwl.writeLock().unlock();
            }
        });
    }

    public Boolean tryIsVisible(double sx, double sy, double sz, double ex, double ey, double ez) {
        if (!rwl.readLock().tryLock()) return null;
        try {
            if (context == 0) return null;
            if (sx == ex && sy == ey && sz == ez) return Boolean.TRUE;
            return NetherPathfinder.isVisible(context, NetherPathfinder.CACHE_MISS_SOLID, sx, sy, sz, ex, ey, ez);
        } finally {
            rwl.readLock().unlock();
        }
    }

    public boolean allClear(int count, double[] src, double[] dst) {
        rwl.readLock().lock();
        try {
            if (context == 0) return false;
            return NetherPathfinder.isVisibleMulti(context, NetherPathfinder.CACHE_MISS_SOLID, count, src, dst, false) == -1;
        } finally {
            rwl.readLock().unlock();
        }
    }

    public int feedErrorCount() { return feedErrors; }
    public int fedChunkCount() { return fedChunks.size(); }

    private void ensureContext(long seed) {
        if (context != 0 && contextSeed == seed) return;
        if (context != 0) {
            NetherPathfinder.freeContext(context);
            fedChunks.clear();
        }
        context = NetherPathfinder.newContext(seed, null, NetherPathfinder.DIMENSION_NETHER, MAX_HEIGHT, true);
        contextSeed = seed;
    }
}
