package sh.siava.pixelxpert.xposed.utils.toolkit;

import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Logger {
	public static String TAG = "PixelXpert Lsposed Module";

	// Rate-limiting for repeated hook failures, keyed by hook-site id, so a hook that
	// fails on every frame can't flood logcat before it is quarantined.
	private static final long HOOK_LOG_MIN_INTERVAL_MS = 10_000L;
	private static final Map<String, Long> hookLogTimestamps = new ConcurrentHashMap<>();

	/**
	 * Logs to logcat. tagged with {@link #TAG}
	 * @param text
	 */
	public static synchronized void log(String text) {
		Log.w(TAG, text);
	}
	/**
	 * Logs to logcat. tagged with {@link #TAG}
	 * @param text
	 */
	public static synchronized void log(String text, Throwable t) {
		Log.e(TAG, text, t);
	}
	/**
	 * Logs to logcat. tagged with {@link #TAG}
	 * @param t
	 */
	public static synchronized void log(Throwable t) {
		Log.e(TAG, "", t);
	}

	/**
	 * Logs a failure that happened inside a hook callback, identified by its hook-site id.
	 * Repeated failures from the same site are rate-limited to avoid flooding logcat.
	 * @param siteId identifier of the hook site (caller + target)
	 * @param t the throwable raised inside the hook callback
	 */
	public static void logHook(String siteId, Throwable t) {
		long now = System.currentTimeMillis();
		Long last = hookLogTimestamps.get(siteId);
		if (last != null && now - last < HOOK_LOG_MIN_INTERVAL_MS) {
			return;
		}
		hookLogTimestamps.put(siteId, now);
		Log.e(TAG, "Hook failure at " + siteId, t);
	}
}
