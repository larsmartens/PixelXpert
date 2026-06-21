package sh.siava.pixelxpert.xposed.modpacks.ksu;

import static android.content.Context.RECEIVER_EXPORTED;


import static de.robv.android.xposed.XposedHelpers.callMethod;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import com.topjohnwu.superuser.Shell;

import org.objenesis.ObjenesisHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.xposed.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.KSUModPack;
import sh.siava.pixelxpert.xposed.annotations.KSUNextModPack;
import sh.siava.pixelxpert.xposed.utils.SystemUtils;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

/**
 * @noinspection RedundantThrows
 */
@KSUModPack
@KSUNextModPack
public class KSUInjector extends XposedModPack {
	private ReflectedClass NativesClass;
	private ReflectedClass ProfileClass;

	public KSUInjector(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... Key) {}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		String packageName = PRParam.getPackageName(); // Can be KSU or KSU Next
		ReflectedClass MainActivityClass = ReflectedClass.ofIfPossible(packageName + ".ui.MainActivity");
		NativesClass = ReflectedClass.ofIfPossible(packageName + ".Natives");
		ProfileClass = ReflectedClass.ofIfPossible(packageName + ".Natives$Profile");

		if (NativesClass.getClazz() == null || ProfileClass.getClazz() == null) {
			log("KSUInjector: KSU Natives/Profile classes not found in " + packageName + "; cannot auto-grant root.");
			return;
		}

		BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				grantRootToPX(intent);
			}
		};

		//In case ksu is running already, it won't understand the onCreate intent we send. broadcast it is then
		mContext.registerReceiver(broadcastReceiver, new IntentFilter(Constants.PX_ROOT_EXTRA), RECEIVER_EXPORTED);

		if (MainActivityClass.getClazz() != null) {
			MainActivityClass
					.after("onCreate")
					.runSafe(param -> {
						Intent launchIntent = ((Activity) param.thisObject).getIntent();
						if (launchIntent.hasExtra(Constants.PX_ROOT_EXTRA)) {
							grantRootToPX(launchIntent);
						}
					});
		}
	}

	private void grantRootToPX(Intent launchIntent) {
		new Thread(() -> {
			try {
				Object nativeObject = ObjenesisHelper.newInstance(NativesClass.getClazz());
				int[] rootUIDs = (int[]) callMethod(nativeObject, "getAllowList");

				PackageManager packageManager = mContext.getPackageManager();
				int ownUID = packageManager.getPackageUid(BuildConfig.APPLICATION_ID, PackageManager.GET_ACTIVITIES);

				boolean haveRoot = Arrays.stream(rootUIDs).anyMatch(uid -> uid == ownUID);

				if (!haveRoot) {
					Object ownRootProfile = buildOwnRootProfile(ownUID);
					if (ownRootProfile == null) {
						// KSU/KSU-Next changed the Profile signature; leave it to the user to grant manually.
						return;
					}

					callMethod(nativeObject, "setAppProfile", ownRootProfile);

					restartPX(launchIntent.hasExtra("launchApp"));
				}
				Thread.sleep(2000);
				SystemUtils.killSelf();
			} catch (Throwable t) {
				log("KSUInjector: failed to grant root to PixelXpert", t);
			}
		}).start();
	}

	/**
	 * Builds the KSU/KSU-Next root {@code Natives$Profile} for PixelXpert. The constructor signature
	 * has been stable across both managers, but if a manager version changes it this returns null
	 * (instead of crashing) so the user can grant root manually in the KSU app.
	 */
	private Object buildOwnRootProfile(int ownUID) {
		try {
			return ProfileClass.getClazz().getConstructor(String.class, int.class, boolean.class, boolean.class, String.class, int.class, int.class, List.class, List.class, String.class, int.class, boolean.class, boolean.class, String.class)
					.newInstance(BuildConfig.APPLICATION_ID, ownUID, true, true, null, 0, 0, new ArrayList<>(), new ArrayList<>(), "u:r:su:s0", 0, true, true, "");
		} catch (NoSuchMethodException signatureChanged) {
			log("KSUInjector: KSU Profile constructor signature changed (" + ProfileClass.getClazz().getName() + "); please grant root to PixelXpert manually in the KSU manager.");
			return null;
		} catch (Throwable t) {
			log("KSUInjector: could not build KSU root profile", t);
			return null;
		}
	}

	private void restartPX(boolean launch) throws InterruptedException {
		Shell.cmd("killall " + BuildConfig.APPLICATION_ID).exec();

		if (launch) {
			Thread.sleep(1000);
			//noinspection DataFlowIssue
			mContext.startActivity(
					mContext
							.getPackageManager()
							.getLaunchIntentForPackage(BuildConfig.APPLICATION_ID)
							.putExtra("FromKSU", 1));
		}
	}
}