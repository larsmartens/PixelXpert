package sh.siava.pixelxpert.xposed.utils;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared, process-wide scheduler backed by a small daemon thread pool.
 * <p>
 * Replaces ad-hoc {@code new Timer().schedule(...)} usage: each {@code java.util.Timer} spawns a
 * dedicated thread that lives on (leaking across SystemUI restarts) when the Timer is never
 * cancelled. Reusing one pool avoids per-call thread creation and never leaks. Tasks are wrapped so a
 * thrown exception can't kill the worker thread.
 */
public class Scheduler {
	private static final ScheduledThreadPoolExecutor EXECUTOR;

	static {
		EXECUTOR = new ScheduledThreadPoolExecutor(2, runnable -> {
			Thread thread = new Thread(runnable, "PixelXpert-Scheduler");
			thread.setDaemon(true);
			return thread;
		});
		EXECUTOR.setRemoveOnCancelPolicy(true);
	}

	/** Runs {@code task} once after {@code delayMs}. The returned future can be cancelled. */
	public static ScheduledFuture<?> scheduleOnce(Runnable task, long delayMs) {
		return EXECUTOR.schedule(wrap(task), delayMs, TimeUnit.MILLISECONDS);
	}

	/** Runs {@code task} repeatedly at a fixed period. The returned future can be cancelled. */
	public static ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initialDelayMs, long periodMs) {
		return EXECUTOR.scheduleAtFixedRate(wrap(task), initialDelayMs, periodMs, TimeUnit.MILLISECONDS);
	}

	private static Runnable wrap(Runnable task) {
		return () -> {
			try {
				task.run();
			} catch (Throwable ignored) {
			}
		};
	}

	private Scheduler() {}
}
