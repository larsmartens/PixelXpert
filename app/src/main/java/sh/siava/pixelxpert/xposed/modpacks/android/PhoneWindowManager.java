package sh.siava.pixelxpert.xposed.modpacks.android;

import static android.content.Context.RECEIVER_EXPORTED;


import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.PackageManager;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.view.Display;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.Constants;
import sh.siava.pixelxpert.xposed.XPrefs;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.FrameworkModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings("RedundantThrows")
@FrameworkModPack
public class PhoneWindowManager extends XposedModPack {
	private Object windowMan = null;
	private static boolean broadcastRegistered = false;
	private List<UserHandle> userHandleList;
	private String currentPackage = "";
	private int currentUser = -1;
	private boolean appProfileSwitchEnabled = true;
	private final ExecutorService profileExecutor = Executors.newSingleThreadExecutor();
	private final Map<Integer, Set<String>> packageAvailabilityCache = new ConcurrentHashMap<>();

	public PhoneWindowManager(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
		boolean newEnabled = XPrefs.Xprefs.getBoolean("AppProfileSwitchEnabled", true);
		if (!newEnabled && appProfileSwitchEnabled) {
			sendAppProfileSwitchAvailable(false);
		}
		appProfileSwitchEnabled = newEnabled;
	}

	final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			try {
				String action = intent.getAction();
				//noinspection DataFlowIssue
				switch (action) {
					case Constants.ACTION_HOME:
						callMethod(windowMan, "launchHomeFromHotKey", Display.DEFAULT_DISPLAY);
						break;
					case Constants.ACTION_BACK:
						callMethod(windowMan, "backKeyPress");
						break;
					case Constants.ACTION_SLEEP:
						SystemUtils.sleep();
						break;
					case Constants.ACTION_SWITCH_APP_PROFILE:
						switchAppProfile();
						break;
				}
			} catch (Throwable ignored) {
			}
		}
	};

	private void switchAppProfile() {
		if (currentUser < 0 || currentPackage.isEmpty()) return;

		int startIndex = 0;
		for (int i = 0; i < userHandleList.size(); i++) {
			int userID = getIntField(userHandleList.get(i), "mHandle");
			if (userID == currentUser) {
				startIndex = i;
				break;
			}
		}

		boolean looped = false;
		for (int i = startIndex; i < userHandleList.size(); ) {
			i++;
			if (i > userHandleList.size() - 1 && !looped) {
				i = 0;
				looped = true;
			}

			if (isPackageAvailableForUser(currentPackage, userHandleList.get(i))) {
				switchAppToProfile(currentPackage, userHandleList.get(i));
				break;
			}
		}
	}

	private void switchAppToProfile(String packageName, UserHandle userHandle) {
		try {
			callMethod(getObjectField(windowMan, "mActivityTaskManagerInternal"),
					"startActivityAsUser",
					callMethod(mContext, "getIApplicationThread"),
					packageName,
					null,
					PackageManager().getLaunchIntentForPackage(packageName),
					null,
					0,
					null,
					getObjectField(userHandle, "mHandle"));
		} catch (Throwable ignored) {
		}
	}

	private void invalidatePackageCache() {
		packageAvailabilityCache.clear();
	}

	private Set<String> getPackagesForUser(int userId) {
		return packageAvailabilityCache.computeIfAbsent(userId, uid -> {
			Set<String> packages = new HashSet<>();
			try {
				//noinspection unchecked
				List<PackageInfo> pkgs = (List<PackageInfo>) callMethod(
						mContext.getPackageManager(),
						"getInstalledPackagesAsUser",
						PackageManager.PackageInfoFlags.of(0),
						uid);
				for (PackageInfo pi : pkgs) {
					if (pi.applicationInfo != null && pi.applicationInfo.enabled) {
						packages.add(pi.packageName);
					}
				}
			} catch (Throwable ignored) {
			}
			return packages;
		});
	}

	private boolean isPackageAvailableForUser(String packageName, UserHandle userHandle) {
		int userId = (int) getObjectField(userHandle, "mHandle");
		return getPackagesForUser(userId).contains(packageName);
	}

	@SuppressLint("WrongConstant")
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		//noinspection unchecked
		userHandleList = (List<UserHandle>) callMethod(SystemUtils.UserManager(), "getProfiles", true);

		if (!broadcastRegistered) {
			broadcastRegistered = true;

			IntentFilter intentFilter = new IntentFilter();
			intentFilter.addAction(Constants.ACTION_HOME);
			intentFilter.addAction(Constants.ACTION_BACK);
			intentFilter.addAction(Constants.ACTION_SLEEP);
			intentFilter.addAction(Constants.ACTION_SWITCH_APP_PROFILE);
			mContext.registerReceiver(broadcastReceiver, intentFilter, RECEIVER_EXPORTED);

			IntentFilter pkgFilter = new IntentFilter();
			pkgFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
			pkgFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
			pkgFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
			pkgFilter.addDataScheme("package");
			mContext.registerReceiver(new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					invalidatePackageCache();
				}
			}, pkgFilter, RECEIVER_EXPORTED);
		}

		try {
			ReflectedClass PhoneWindowManagerClass = ReflectedClass.ofIfPossible("com.android.server.policy.PhoneWindowManager");

			PhoneWindowManagerClass
					.before("onDefaultDisplayFocusChangedLw")
					.runSafe(param -> {
						if (param.args[0] == null) return;
						if (!appProfileSwitchEnabled || userHandleList.size() <= 1) return;

						profileExecutor.execute(() -> {
							try {
								if (callMethod(param.args[0], "getBaseType").equals(WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW)) {
									String newPackageName = (String) callMethod(param.args[0], "getOwningPackage");
									int newUserID = (int) getObjectField(callMethod(param.args[0], "getTask"), "mUserId");
									if (!newPackageName.equals(currentPackage) || newUserID != currentUser) {
										currentPackage = newPackageName;
										currentUser = newUserID;

										boolean availableOnOtherUsers = false;
										for (UserHandle userHandle : userHandleList) {
											int thisUserID = (int) getObjectField(userHandle, "mHandle");
											if (thisUserID != currentUser) {
												if (isPackageAvailableForUser(currentPackage, userHandle)) {
													availableOnOtherUsers = true;
													break;
												}
											}
										}
										sendAppProfileSwitchAvailable(availableOnOtherUsers);
									}
								}
							} catch (Throwable ignored) {
							}
						});
					});

			PhoneWindowManagerClass
					.after("enableScreen")
					.runSafe(param -> windowMan = param.thisObject);
		} catch (Throwable ignored) {
		}
	}

	@SuppressLint("MissingPermission")
	private void sendAppProfileSwitchAvailable(boolean isAvailable) {
		Intent broadcast = new Intent();
		broadcast.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
		broadcast.setAction(Constants.ACTION_PROFILE_SWITCH_AVAILABLE);
		broadcast.putExtra("available", isAvailable);
		broadcast.setPackage(sh.siava.pixelxpert.BuildConfig.APPLICATION_ID);
		mContext.sendBroadcast(broadcast);
	}
}
