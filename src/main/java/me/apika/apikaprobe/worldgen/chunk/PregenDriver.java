package me.apika.apikaprobe.worldgen.chunk;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Driver loop for radius pre-generation.
 *
 * <p>Walks a {@link ConcentricChunkIterator} in expanding rings around
 * (centerX, centerZ) and feeds each chunk to {@link ChunkForcer#submitAsync}
 * with a {@link Semaphore}-bounded inflight cap. Per-chunk submissions are
 * bounced onto the server thread to satisfy ChunkForcer's contract; the
 * iterator loop itself runs on a dedicated daemon thread so the server
 * thread stays free to tick and to service chunk-system housekeeping.
 *
 * <p>Only one driver may be active at a time; concurrent {@code run}
 * calls return a failed future. Cancel via {@link #cancelActive()}; the
 * driver finishes draining in-flight chunks then fires
 * {@link PregenProgressListener#onCancelled}.
 *
 * <p>An optional {@link PregenCheckpointer} is invoked every
 * {@value #CHECKPOINT_INTERVAL} submissions and once at the end (with
 * {@code completedNormally} reflecting cancel vs full completion). The
 * lifecycle uses this to persist a resume snapshot.
 */
public final class PregenDriver {
	private static final int MAX_INFLIGHT = 50;
	public static final int CHECKPOINT_INTERVAL = 100;

	private static final AtomicReference<PregenDriver> ACTIVE = new AtomicReference<>();

	private final ServerLevel world;
	private final int centerX;
	private final int centerZ;
	private final int radius;
	private final PregenProgressListener progress;
	private final PregenCheckpointer checkpointer;
	private final ConcentricChunkIterator iterator;
	private final int total;
	private final Semaphore inflight = new Semaphore(MAX_INFLIGHT);
	private final SlidingWindowRate rate = new SlidingWindowRate();
	private final AtomicInteger done = new AtomicInteger();
	private final CompletableFuture<Void> completion = new CompletableFuture<>();
	private volatile boolean cancelled = false;

	private PregenDriver(ServerLevel world, int centerX, int centerZ, int radius,
			ConcentricChunkIterator iterator, PregenProgressListener progress,
			PregenCheckpointer checkpointer) {
		this.world = world;
		this.centerX = centerX;
		this.centerZ = centerZ;
		this.radius = radius;
		this.iterator = iterator;
		this.progress = progress;
		this.checkpointer = checkpointer;
		this.total = iterator.totalChunks();
		// Resume case: 'done' starts at the iterator's already-emitted
		// count so progress / ETA reflect overall world progress, not
		// just this resume session.
		this.done.set(iterator.snapshot().emitted());
	}

	public static CompletableFuture<Void> run(ServerLevel world, int centerX, int centerZ,
			int radius, PregenProgressListener progress) {
		return run(world, centerX, centerZ, radius, progress, null);
	}

	public static CompletableFuture<Void> run(ServerLevel world, int centerX, int centerZ,
			int radius, PregenProgressListener progress, PregenCheckpointer checkpointer) {
		ConcentricChunkIterator iter = new ConcentricChunkIterator(centerX, centerZ, radius);
		return start(new PregenDriver(world, centerX, centerZ, radius, iter, progress, checkpointer));
	}

	public static CompletableFuture<Void> runFromSnapshot(ServerLevel world,
			PregenSnapshot snap, PregenProgressListener progress,
			PregenCheckpointer checkpointer) {
		ConcentricChunkIterator iter = new ConcentricChunkIterator(
				snap.centerX(), snap.centerZ(), snap.radius(), snap.iteratorState());
		return start(new PregenDriver(world, snap.centerX(), snap.centerZ(),
				snap.radius(), iter, progress, checkpointer));
	}

	private static CompletableFuture<Void> start(PregenDriver driver) {
		if (!ACTIVE.compareAndSet(null, driver)) {
			return CompletableFuture.failedFuture(
					new IllegalStateException("pre-gen already in progress"));
		}
		Thread t = new Thread(driver::loop, "ferrite-pregen-driver");
		t.setDaemon(true);
		t.start();
		return driver.completion;
	}

	public static boolean cancelActive() {
		PregenDriver d = ACTIVE.get();
		if (d == null) return false;
		d.cancelled = true;
		return true;
	}

	public static PregenDriver active() {
		return ACTIVE.get();
	}

	public int doneCount() { return done.get(); }
	public int totalCount() { return total; }
	public double chunksPerSecond() { return rate.chunksPerSecond(); }
	public boolean isCancelled() { return cancelled; }

	private void loop() {
		try {
			int sinceLastCheckpoint = 0;
			while (iterator.hasNext() && !cancelled) {
				final ChunkPos pos = iterator.next();
				inflight.acquireUninterruptibly();
				if (cancelled) {
					inflight.release();
					break;
				}
				CompletableFuture
						.supplyAsync(() -> ChunkForcer.submitAsync(world, pos.x(), pos.z()),
								world.getServer())
						.thenCompose(f -> f)
						.whenComplete((v, err) -> {
							inflight.release();
							int n = done.incrementAndGet();
							rate.record(n);
							progress.onProgress(n, total, rate.chunksPerSecond());
						});
				if (++sinceLastCheckpoint >= CHECKPOINT_INTERVAL) {
					sinceLastCheckpoint = 0;
					if (checkpointer != null) {
						checkpointer.onCheckpoint(iterator.snapshot());
					}
				}
			}
			inflight.acquireUninterruptibly(MAX_INFLIGHT);
			if (cancelled) {
				if (checkpointer != null) {
					checkpointer.onCheckpoint(iterator.snapshot());
					checkpointer.onFinalize(false);
				}
				progress.onCancelled(done.get(), total);
			} else {
				if (checkpointer != null) {
					checkpointer.onFinalize(true);
				}
				progress.onComplete(total);
			}
			completion.complete(null);
		} catch (Throwable t) {
			completion.completeExceptionally(t);
		} finally {
			ACTIVE.compareAndSet(this, null);
		}
	}
}
