package sh.siava.pixelxpert.xposed.utils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * Centralized, lazily-resolved feature detection for version-fragile Android internals.
 * <p>
 * PixelXpert intentionally avoids {@code Build.VERSION.SDK_INT} branching in favour of probing for
 * the presence of specific framework/SystemUI classes (the "class-availability" pattern). This helper
 * keeps that philosophy but resolves each probe once and caches the result, so the same class isn't
 * looked up repeatedly across modpacks, and there is a single place to update when class names move
 * between Android releases.
 * <p>
 * State is per-process: LSPosed loads the module separately into each hooked process, so the cache is
 * scoped to one classloader. {@link #reset()} is available for completeness.
 */
public class PlatformVersion {
	private static final Map<String, Boolean> classPresence = new ConcurrentHashMap<>();

	/** @return true if a class with the given (binary) name is resolvable in the current process. */
	public static boolean hasClass(String className) {
		Boolean cached = classPresence.get(className);
		if (cached != null) return cached;

		boolean present;
		try {
			present = ReflectedClass.ofIfPossible(className).getClazz() != null;
		} catch (Throwable t) {
			present = false;
		}
		classPresence.put(className, present);
		return present;
	}

	/** First class name found resolvable from the given candidates, or null if none resolve. */
	public static String firstResolvable(String... classNames) {
		for (String name : classNames) {
			if (hasClass(name)) return name;
		}
		return null;
	}

	// ---- Semantic feature flags (SystemUI / framework) ----

	/** Screenshot CaptureArgs moved to ScreenCaptureInternal in A16 QPR2. */
	public static boolean hasScreenCaptureInternal() {
		return hasClass("android.window.ScreenCaptureInternal$CaptureArgs")
				|| hasClass("android.window.ScreenCaptureInternal.CaptureArgs");
	}

	/** Compose-based QS paginated grid (A15+). */
	public static boolean hasPaginatedQsGrid() {
		return hasClass("com.android.systemui.qs.panels.ui.compose.PaginatedGridLayout");
	}

	/** Statusbar icon manager moved under ...phone.ui in A15 beta3. */
	public static boolean hasNewIconManager() {
		return hasClass("com.android.systemui.statusbar.phone.ui.IconManager");
	}

	/** Clears the cached probe results. Normally unnecessary (statics are per-process). */
	public static void reset() {
		classPresence.clear();
	}

	private PlatformVersion() {}
}
