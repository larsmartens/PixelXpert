package sh.siava.pixelxpert.xposed;

import static android.content.Context.CONTEXT_IGNORE_SECURITY;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.BuildConfig.APPLICATION_ID;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;
import static sh.siava.pixelxpert.xposed.utils.BootLoopProtector.isBootLooped;

import android.annotation.SuppressLint;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.annotations.ModPackData;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.IPixelXpertProxy;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.service.PixelXpertProxy;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;

public class XPLauncher extends XposedModule implements ServiceConnection {
	public static String processName = "";
	public static boolean isSystemServer = false;

	public static List<XposedModPack> runningMods = new CopyOnWriteArrayList<>();
	public Context mContext = null;
	@SuppressLint("StaticFieldLeak")
	static XPLauncher instance;

	private CountDownLatch rootProxyCountdown = new CountDownLatch(1);
	private static IPixelXpertProxy rootProxyIPC;
	private static final Queue<ProxyRunnable> proxyQueue = new LinkedList<>();
	private static boolean TELECOM_SERVER_LOADED = false;
	public static Resources moduleResources;
	private static final int PREFS_POLL_INTERVAL_MS = 50;
	private static final int PREFS_READY_TIMEOUT_MS = 5000;
	private static final int PREFS_SINGLE_PROBE_TIMEOUT_MS = 250;
	private static final int BOOT_POLL_INTERVAL_MS = 1000;
	private static final int BOOT_READY_TIMEOUT_MS = 180000;
	private static final String DISABLE_HOOKS_PROPERTY = "persist.pixelxpert.disable_hooks";
	private static final String ANDROID_17_UNSAFE_SCOPES_PROPERTY = "persist.pixelxpert.a17.unsafe_scopes";

	public XPLauncher()
	{
		instance = this;
		Logger.setXposedInterface(this);
	}

	@Override
	public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
		super.onModuleLoaded(param);

		processName = param.getProcessName();
		isSystemServer = param.isSystemServer();
	}

	@Override
	public void onSystemServerStarting(@NonNull XposedModuleInterface.SystemServerStartingParam SSSP)
	{
		ReflectedClass.setFrameworkClassloader(SSSP.getClassLoader());
	}

	private static void hook17BetaAudioManagerSRWorkaround(PackageReadyParam PRParam) {
		if (Build.VERSION.SDK_INT < 37) return;

		ReflectedClass.of("android.media.AudioManager", PRParam.getClassLoader())
				.before("requestAudioFocus")
				.runSafe(instance,param -> {
					if(getObjectField(param.thisObject, "mApplicationContext") == null) {
						setObjectField(param.thisObject, "mApplicationContext", getObjectField(param.thisObject, "mOriginalContext"));
					}
				});
	}

	@Override
	public void onPackageReady(@NonNull PackageReadyParam PRParam){
		ReflectedClass.setDefaultXposedInterface(this);

		if (areHooksDisabled()) {
			Logger.log("PixelXpert: hooks disabled by " + DISABLE_HOOKS_PROPERTY);
			return;
		}

		if (isSystemServer && Build.VERSION.SDK_INT >= 37) {
			Logger.log("PixelXpert: skipping system_server hooks on Android 17+ until preferences are boot-safe there");
			return;
		}

		hook17BetaAudioManagerSRWorkaround(PRParam);

		if (isSystemServer && !PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE)) {
			ReflectedClass PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager");

			PhoneWindowManagerClass
					.before("init")
					.runSafe(instance,param -> {
						try {
							if (mContext == null) {
								mContext = (Context) param.args[0];

								moduleResources = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
										.getResources();

								XPrefs.init(mContext);

								loadWhenPrefsReady(PRParam);
							}
						} catch (Throwable t) {
							Logger.log(t);
						}
					});
		}

		if(!isSystemServer || PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE)) {
			ReflectedClass.of(Instrumentation.class)
					.after("newApplication")
					.runSafe(this, param -> {
				try {
					if (mContext == null || (PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE) && !TELECOM_SERVER_LOADED)) {
						if (PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE))
							TELECOM_SERVER_LOADED = true;

						mContext = (Context) param.args[param.args.length - 1];

						moduleResources = mContext.createPackageContext(APPLICATION_ID, CONTEXT_IGNORE_SECURITY)
								                  .getResources();

						XPrefs.init(mContext);

						if (isSystemServer || shouldDeferHookLoading(PRParam)) {
							loadWhenBootAndPrefsReady(PRParam);
						} else {
							waitForXprefsLoad(PRParam);
						}
					}
				} catch (Throwable t) {
					Logger.log(t);
				}
			});
		}
	}

	private void loadWhenPrefsReady(PackageReadyParam PRParam) {
		CompletableFuture.runAsync(() -> waitForXprefsLoad(PRParam));
	}

	private void loadWhenBootAndPrefsReady(PackageReadyParam PRParam) {
		CompletableFuture.runAsync(() -> {
			if (!awaitBootCompleted(PRParam)) {
				return;
			}

			waitForXprefsLoad(PRParam);
		});
	}

	private boolean shouldDeferHookLoading(PackageReadyParam PRParam) {
		return Build.VERSION.SDK_INT >= 37 && !PRParam.getPackageName().equals(APPLICATION_ID);
	}

	private boolean awaitBootCompleted(PackageReadyParam PRParam) {
		if (isBootCompleted()) {
			return true;
		}

		int waited = 0;
		while (waited < BOOT_READY_TIMEOUT_MS) {
			SystemUtils.threadSleep(BOOT_POLL_INTERVAL_MS);
			waited += BOOT_POLL_INTERVAL_MS;

			if (isBootCompleted()) {
				Logger.log("PixelXpert: boot completed, loading deferred hooks for " + PRParam.getPackageName());
				return true;
			}
		}

		Logger.log("PixelXpert: timed out waiting for boot completion in " + PRParam.getPackageName());
		return false;
	}

	private boolean isBootCompleted() {
		try {
			Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
			Object bootCompleted = systemPropertiesClass
					.getMethod("get", String.class, String.class)
					.invoke(null, "sys.boot_completed", "0");

			return "1".equals(bootCompleted);
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void waitForXprefsLoad(PackageReadyParam PRParam) {
		if (!awaitXprefsReady(PRParam)) {
			return;
		}

		Logger.log(String.format("Loading PixelXpert version: %s on %s", BuildConfig.VERSION_NAME, PRParam.getPackageName()));
		try {
			Logger.log("PixelXpert Records: " + Xprefs.getAll().size());
		} catch (Throwable ignored) {
		}

		onXPrefsReady(PRParam);
	}

	private boolean awaitXprefsReady(PackageReadyParam PRParam) {
		int waited = 0;
		while (waited < PREFS_READY_TIMEOUT_MS) {
			CompletableFuture<Void> probe = CompletableFuture.runAsync(() -> Xprefs.getBoolean("LoadTestBooleanValue", false));
			try {
				probe.get(PREFS_SINGLE_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
				return true;
			} catch (TimeoutException e) {
				probe.cancel(true);
				Logger.log("PixelXpert: preference provider probe timed out in " + PRParam.getPackageName());
				return false;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				Logger.log("PixelXpert: interrupted while waiting for preferences in " + PRParam.getPackageName());
				return false;
			} catch (ExecutionException ignored) {
				SystemUtils.threadSleep(PREFS_POLL_INTERVAL_MS);
				waited += PREFS_POLL_INTERVAL_MS;
			}
		}

		Logger.log("PixelXpert: timed out waiting for preferences in " + PRParam.getPackageName());
		return false;
	}

	private void onXPrefsReady(PackageReadyParam PRParam) {
		if (isBootLooped(PRParam.getPackageName())) {
			Logger.log(String.format("PixelXpert: Possible bootloop in %s. Will not load for now", PRParam.getPackageName()));
			return;
		}

		new SystemUtils(mContext);
		XPrefs.setPackagePrefs(PRParam.getPackageName());

		loadModPacks(PRParam);

		XPrefs.onContentProviderLoaded();
	}

	private void loadModPacks(PackageReadyParam PRParam) {
		ReflectedClass.setDefaultClassloader(PRParam.getClassLoader());

		if (Arrays.asList(moduleResources.getStringArray(R.array.root_requirement)).contains(PRParam.getPackageName())) {
			forceConnectRootService();
		}

		ModPacks.getModPacks()
				.forEach(modPackData -> {
					if (shouldSkipModPackOnAndroid17(PRParam, modPackData)) {
						return;
					}

					String partOfProcessName = modPackData.targetsMainProcess ? "" : modPackData.childProcessName;

					if((modPackData.targetPackage.equals(PRParam.getPackageName()) || modPackData.targetPackage.isEmpty() /*common mod packs*/ || (modPackData.targetPackage.equals(Constants.SYSTEM_FRAMEWORK_PACKAGE) && isSystemServer))
							   && processName.contains(partOfProcessName))
					{
						//noinspection unchecked
						loadModPack((Class<? extends XposedModPack>) modPackData.clazz, PRParam);
					}
					});
	}

	private boolean shouldSkipModPackOnAndroid17(PackageReadyParam PRParam, ModPackData modPackData) {
		if (Build.VERSION.SDK_INT < 37 || isPropertyEnabled(ANDROID_17_UNSAFE_SCOPES_PROPERTY)) {
			return false;
		}

		if (modPackData.targetPackage.isEmpty()) {
			return true;
		}

		if (modPackData.targetPackage.equals(Constants.SYSTEM_FRAMEWORK_PACKAGE)) {
			return true;
		}

		return PRParam.getPackageName().equals(Constants.TELECOM_SERVER_PACKAGE);
	}

	private boolean areHooksDisabled() {
		return isPropertyEnabled(DISABLE_HOOKS_PROPERTY);
	}

	private boolean isPropertyEnabled(String propertyName) {
		try {
			Class<?> systemPropertiesClass = Class.forName("android.os.SystemProperties");
			Object value = systemPropertiesClass
					.getMethod("get", String.class, String.class)
					.invoke(null, propertyName, "0");

			return "1".equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
		} catch (Throwable ignored) {
			return false;
		}
	}

	private void loadModPack(Class<? extends XposedModPack> thisClass, PackageReadyParam PRParam) {
		try {
			XposedModPack instance = thisClass.getConstructor(Context.class).newInstance(mContext);
			try {
				instance.onPreferenceUpdated();
			} catch (Throwable ignored) {
			}

			instance.onPackageLoaded(PRParam);
			runningMods.add(instance);
		} catch (Throwable T) {
			Logger.log("Start Error Dump - Occurred in " + thisClass.getName());
			Logger.log(T);
		}
	}

	private void forceConnectRootService() {
		new Thread(() -> {
			while (SystemUtils.UserManager() == null
					       || !SystemUtils.UserManager().isUserUnlocked()) //device is still CE encrypted
			{
				SystemUtils.threadSleep(2000);
			}
			SystemUtils.threadSleep(5000); //wait for the unlocked account to settle down a bit

			while (rootProxyIPC == null) {
				connectRootService();
				SystemUtils.threadSleep(5000);
			}
		}).start();
	}

	private void connectRootService() {
		try {
			Intent intent = new Intent();
			intent.setComponent(new ComponentName(APPLICATION_ID, PixelXpertProxy.class.getName()));
			mContext.bindService(intent, instance, Context.BIND_AUTO_CREATE | Context.BIND_ADJUST_WITH_ACTIVITY);
		} catch (Throwable t) {
			Logger.log(t);
		}
	}

	@Override
	public void onServiceConnected(ComponentName name, IBinder service) {
		rootProxyIPC = IPixelXpertProxy.Stub.asInterface(service);
		rootProxyCountdown.countDown();

		synchronized (proxyQueue) {
			while (!proxyQueue.isEmpty()) {
				try {
					Objects.requireNonNull(proxyQueue.poll()).run(rootProxyIPC);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	@Override
	public void onServiceDisconnected(ComponentName name) {
		rootProxyIPC = null;

		forceConnectRootService();
	}

	public static IPixelXpertProxy getRootProviderProxy() {
		if (rootProxyIPC == null) {
			instance.rootProxyCountdown = new CountDownLatch(1);
			instance.forceConnectRootService();
			try {
				//noinspection ResultOfMethodCallIgnored
				instance.rootProxyCountdown.await(5, TimeUnit.SECONDS);
			} catch (Throwable ignored) {
			}
		}
		return rootProxyIPC;
	}

	public static void enqueueProxyCommand(ProxyRunnable runnable) {
		if (rootProxyIPC != null) {
			try {
				runnable.run(rootProxyIPC);
			} catch (RemoteException ignored) {
			}
		} else {
			synchronized (proxyQueue) {
				proxyQueue.add(runnable);
			}
			instance.forceConnectRootService();
		}
	}

	public interface ProxyRunnable {
		void run(IPixelXpertProxy proxy) throws RemoteException;
	}
}
