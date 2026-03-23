package sh.siava.pixelxpert.xposed.modpacks.settings;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static sh.siava.pixelxpert.xposed.utils.SystemUtils.idOf;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Process;
import android.view.Menu;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.XPLauncher;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SettingsModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SuppressWarnings({"RedundantThrows"})
@SettingsModPack
public class AppCloneEnabler extends XposedModPack {
	private static final int AVAILABLE = 0;
	private static final int LIST_TYPE_CLONED_APPS = 17;
	private ReflectedClass UtilsClass;

	public AppCloneEnabler(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {
	}

	@SuppressLint("ResourceType")
	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {

		ReflectedClass ClonedAppsPreferenceControllerClass = ReflectedClass.of("com.android.settings.applications.ClonedAppsPreferenceController");
		ReflectedClass AppStateClonedAppsBridgeClass = ReflectedClass.of("com.android.settings.applications.AppStateClonedAppsBridge");
		ReflectedClass ApplicationPackageManagerClass = ReflectedClass.of("android.app.ApplicationPackageManager");
		ReflectedClass ManageApplicationsClass = ReflectedClass.of("com.android.settings.applications.manageapplications.ManageApplications");
		UtilsClass = ReflectedClass.of("com.android.settings.Utils");

		ManageApplicationsClass
				.after("updateOptionsMenu")
				.run(param -> {
					if (getObjectField(param.thisObject, "mListType").equals(LIST_TYPE_CLONED_APPS) && getCloneUserID() > 0) {
						Menu mOptionsMenu = (Menu) getObjectField(param.thisObject, "mOptionsMenu");
						if (mOptionsMenu != null) {
							mOptionsMenu.findItem(idOf("delete_all_app_clones")).setVisible(true);
						}
					}
				});

		/* Private Space
		ReflectedClass FlagsClass = ReflectedClass.of("android.os.Flags");

		hookAllMethods(FlagsClass, "allowPrivateProfile", new XC_MethodHook() {
			@Override
			protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
				param.setResult(true);
			}
		});*/

			AppStateClonedAppsBridgeClass
					.afterConstruction()
					.run(param -> {
					ArrayList<String> packageList = new ArrayList<>();
					PackageManager packageManager = mContext.getPackageManager();

					int cloneUserID = getCloneUserID();

					Set<String> clonePackageNames = new HashSet<>();
					if (cloneUserID > 0) {
						//noinspection unchecked
						List<PackageInfo> cloneUserPackages = (List<PackageInfo>) callMethod(packageManager, "getInstalledPackagesAsUser", PackageManager.GET_ACTIVITIES, cloneUserID);

						cloneUserPackages.forEach(clonePackage -> {
							if (clonePackage.packageName != null && isPackageInstalledForUser(packageManager, clonePackage.packageName, cloneUserID))
								clonePackageNames.add(clonePackage.packageName);
						});
					}

					for (PackageInfo installedPackage : packageManager.getInstalledPackages(PackageManager.GET_ACTIVITIES)) {
						if (installedPackage.packageName != null && !installedPackage.packageName.isEmpty()) {
							ApplicationInfo applicationInfo = getApplicationInfoSafely(packageManager, installedPackage.packageName);
							if (applicationInfo == null) {
								continue;
							}
							//Clone user profile is present and many system apps are auto-cloned. We don't need to display them.
							// For some reason, some system apps are not auto-cloned. We don't remove them from the list
							if ((applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && clonePackageNames.contains(installedPackage.packageName)) {
								continue;
							}

							packageList.add(installedPackage.packageName);
						}
					}

						setObjectField(param.thisObject, "mAllowedApps", packageList);
					});

			ApplicationPackageManagerClass
					.after("getApplicationInfo")
					.run(param -> {
						Throwable throwable = param.getThrowable();
						if (!(throwable instanceof PackageManager.NameNotFoundException)) {
							return;
						}
						if (param.args.length < 2 || !(param.args[0] instanceof String)) {
							return;
						}
						if (!isAppInfoStorageLookup()) {
							return;
						}

						ApplicationInfo applicationInfo = getApplicationInfoIncludingUninstalled(
								(PackageManager) param.thisObject,
								(String) param.args[0],
								extractFlags(param.args[1])
						);
						if (applicationInfo != null) {
							param.setResult(applicationInfo);
						}
					});

		//the way to manually clone the app
/*		ReflectedClass CloneBackendClass = ReflectedClass.of("com.android.settings.applications.manageapplications.CloneBackend");

		Object cb = callStaticMethod(CloneBackendClass, "getInstance", mContext);
		callMethod(cb, "installCloneApp", "com.whatsapp");*/

		//Adding the menu to settings app
		ClonedAppsPreferenceControllerClass
				.before("getAvailabilityStatus")
				.run(param -> param.setResult(AVAILABLE));

		ClonedAppsPreferenceControllerClass
				.after("updateSummary")
				.run(param -> {
					callMethod(
							getObjectField(param.thisObject, "mPreference"),
							"setSummary",
							XPLauncher.moduleResources.getText(R.string.settings_cloned_apps_active));

					param.setResult(null);
				});
	}

	private int getCloneUserID() {
		return (int) UtilsClass.callStaticMethod("getCloneUserId", mContext);
	}

	private ApplicationInfo getApplicationInfoSafely(PackageManager packageManager, String packageName) {
		try {
			return packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
		} catch (PackageManager.NameNotFoundException ignored) {
			return null;
		}
	}

	private boolean isPackageInstalledForUser(PackageManager packageManager, String packageName, int userId) {
		try {
			return callMethod(packageManager, "getApplicationInfoAsUser", packageName, PackageManager.GET_META_DATA, userId) != null;
		} catch (Throwable ignored) {
			return false;
		}
	}

	private ApplicationInfo getApplicationInfoIncludingUninstalled(PackageManager packageManager, String packageName, int flags) {
		try {
			return (ApplicationInfo) callMethod(
					packageManager,
					"getApplicationInfoAsUser",
					packageName,
					flags | PackageManager.MATCH_UNINSTALLED_PACKAGES,
					getCurrentUserId()
			);
		} catch (Throwable ignored) {
			return null;
		}
	}

	private int getCurrentUserId() {
		try {
			return (int) callMethod(Process.myUserHandle(), "getIdentifier");
		} catch (Throwable ignored) {
			return 0;
		}
	}

	private int extractFlags(Object flagsArgument) {
		if (flagsArgument instanceof Integer) {
			return (int) flagsArgument;
		}
		return 0;
	}

	private boolean isAppInfoStorageLookup() {
		for (StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
			if ("com.android.settings.spa.app.catalyst.AppInfoStorageScreen".equals(stackTraceElement.getClassName())) {
				return true;
			}
		}
		return false;
	}
}
