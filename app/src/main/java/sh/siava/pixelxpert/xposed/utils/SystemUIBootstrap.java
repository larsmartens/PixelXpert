package sh.siava.pixelxpert.xposed.utils;

import static de.robv.android.xposed.XposedHelpers.getObjectField;

import android.widget.FrameLayout;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;

/**
 * Captures boot-created SystemUI objects before preference-backed modpacks are loaded.
 * Hook callbacks only retain weak references and never access module preferences.
 */
public final class SystemUIBootstrap {
	public static final String ACTIVITY_STARTER = "activity_starter";
	public static final String AOD_ICON_VIEW_MODEL = "aod_icon_view_model";
	public static final String ATTACHED_STATUS_BAR_VIEW = "attached_status_bar_view";
	public static final String EDGE_BACK_GESTURE_HANDLER = "edge_back_gesture_handler";
	public static final String KEYGUARD_INTERACTOR = "keyguard_interactor";
	public static final String SHADE_HEADER_CONTROLLER = "shade_header_controller";
	public static final String SHADE_INTERACTOR = "shade_interactor";
	public static final String STATUS_BAR_ICON_CONTROLLER = "status_bar_icon_controller";
	public static final String STATUS_BAR_ICON_VIEW_MODEL = "status_bar_icon_view_model";
	public static final String TUNER_SERVICE = "tuner_service";

	private static final String PHONE_STATUS_BAR_VIEW = "phone_status_bar_view";
	private static final AtomicBoolean installed = new AtomicBoolean(false);
	private static final Map<String, WeakReference<Object>> instances = new ConcurrentHashMap<>();
	private static final Map<String, CopyOnWriteArrayList<Consumer<Object>>> listeners =
			new ConcurrentHashMap<>();

	private SystemUIBootstrap() {}

	public static void install(ClassLoader classLoader) {
		if (!installed.compareAndSet(false, true)) {
			return;
		}

		captureAfterConstruction(classLoader,
				"com.android.systemui.statusbar.phone.PhoneStatusBarView",
				PHONE_STATUS_BAR_VIEW);
		captureAfterConstruction(classLoader,
				"com.android.systemui.statusbar.phone.ui.StatusBarIconControllerImpl",
				STATUS_BAR_ICON_CONTROLLER);
		captureAfterConstruction(classLoader,
				"com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerAlwaysOnDisplayViewModel",
				AOD_ICON_VIEW_MODEL);
		captureAfterConstruction(classLoader,
				"com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconContainerStatusBarViewModel",
				STATUS_BAR_ICON_VIEW_MODEL);
		captureAfterConstruction(classLoader,
				"com.android.systemui.tuner.TunerServiceImpl",
				TUNER_SERVICE);
		captureAfterConstruction(classLoader,
				"com.android.systemui.statusbar.phone.ActivityStarterImpl",
				ACTIVITY_STARTER);
		captureAfterConstruction(classLoader,
				"com.android.systemui.keyguard.domain.interactor.KeyguardInteractor",
				KEYGUARD_INTERACTOR);
		captureAfterConstruction(classLoader,
				"com.android.systemui.shade.domain.interactor.ShadeInteractorSceneContainerImpl",
				SHADE_INTERACTOR);
		captureAfterMethod(classLoader,
				"com.android.systemui.shade.ShadeHeaderController",
				"onInit",
				SHADE_HEADER_CONTROLLER);
		captureAfterMethod(classLoader,
				"com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler",
				"updateIsEnabled",
				EDGE_BACK_GESTURE_HANDLER);

		ReflectedClass controllerClass = ReflectedClass.ofIfPossible(
				"com.android.systemui.statusbar.phone.PhoneStatusBarViewController", classLoader);
		if (controllerClass.getClazz() != null) {
			controllerClass.after("onViewAttached").runSafe(param -> {
				Object view = null;
				try {
					view = getObjectField(param.thisObject, "mView");
				} catch (Throwable ignored) {
				}
				if (view == null) {
					WeakReference<Object> reference = instances.get(PHONE_STATUS_BAR_VIEW);
					view = reference == null ? null : reference.get();
				}

				if (view instanceof FrameLayout) {
					capture(ATTACHED_STATUS_BAR_VIEW, view);
				} else {
					Logger.log("PixelXpert: could not resolve the attached PhoneStatusBarView");
				}
			});
		}

		Logger.log("PixelXpert: installed early SystemUI lifecycle bootstrap");
	}

	public static void register(String key, Consumer<Object> listener) {
		listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(listener);

		WeakReference<Object> reference = instances.get(key);
		Object instance = reference == null ? null : reference.get();
		if (instance != null) {
			dispatch(key, listener, instance);
		}
	}

	private static void captureAfterConstruction(ClassLoader classLoader, String className, String key) {
		ReflectedClass reflectedClass = ReflectedClass.ofIfPossible(className, classLoader);
		if (reflectedClass.getClazz() != null) {
			reflectedClass.afterConstruction().runSafe(param -> capture(key, param.thisObject));
		}
	}

	private static void captureAfterMethod(
			ClassLoader classLoader, String className, String methodName, String key) {
		ReflectedClass reflectedClass = ReflectedClass.ofIfPossible(className, classLoader);
		if (reflectedClass.getClazz() != null) {
			reflectedClass.after(methodName).runSafe(param -> capture(key, param.thisObject));
		}
	}

	private static void capture(String key, Object instance) {
		if (instance == null) {
			return;
		}

		instances.put(key, new WeakReference<>(instance));
		CopyOnWriteArrayList<Consumer<Object>> keyListeners = listeners.get(key);
		if (keyListeners == null) {
			return;
		}
		for (Consumer<Object> listener : keyListeners) {
			dispatch(key, listener, instance);
		}
	}

	private static void dispatch(String key, Consumer<Object> listener, Object instance) {
		try {
			listener.accept(instance);
		} catch (Throwable t) {
			Logger.logHook("SystemUIBootstrap." + key, t);
		}
	}
}
