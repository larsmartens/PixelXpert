package sh.siava.pixelxpert.service;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import com.topjohnwu.superuser.nio.FileSystemManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import sh.siava.pixelxpert.BuildConfig;
import sh.siava.pixelxpert.IRootProviderService;
import sh.siava.pixelxpert.Constants;

public class RootProvider extends RootService {
	/** @noinspection unused*/
	String TAG = getClass().getSimpleName();

	static final String LSPD_DB_DEFAULT_PATH = "/data/adb/lspd/config/modules_config.db";
	static final String SQLITE_BIN = "/data/adb/modules/PixelXpert/sqlite3";
	static final String MODULE_PATH = "/data/adb/modules/PixelXpert";
	static final String PRIV_APP_APK_PATH = "/system/priv-app/PixelXpert/PixelXpert.apk";
	static final String MAGISK_PACKAGE = "com.topjohnwu.magisk";
	static final String APATCH_PACKAGE = "me.bmax.apatch";
	static final String LSPOSED_PACKAGE = "org.lsposed.manager";
	static final String VECTOR_PACKAGE = "io.github.vvb2060.mahoshojo";
	static final String[] HYBRID_MOUNT_CONFIG_PATHS = {
			"/data/adb/hybrid-mount/config.toml",
			"/data/adb/hybrid-mount/kasumi.toml"
	};
	static final String[] HYBRID_MOUNT_BINARY_PATHS = {
			"/data/adb/modules/hybrid_mount/bin/hybrid-mount",
			"/data/adb/modules/ksu_overlayfs/bin/hybrid-mount",
			"/data/adb/modules/magic_overlayfs/bin/hybrid-mount"
	};

	private static String resolvedLspdDbPath = null;

	/**
	 * Resolves the LSPosed/Vector config DB. The manager was renamed (LSPosed -> Vector) and may
	 * live under a differently named directory, so fall back to scanning /data/adb for an
	 * {@code *lsp*} folder before giving up on the canonical path.
	 */
	static String lspdDbPath() {
		if (resolvedLspdDbPath != null) return resolvedLspdDbPath;

		if (new File(LSPD_DB_DEFAULT_PATH).exists()) {
			resolvedLspdDbPath = LSPD_DB_DEFAULT_PATH;
			return resolvedLspdDbPath;
		}

		File[] dirs = new File("/data/adb").listFiles();
		if (dirs != null) {
			for (File dir : dirs) {
				if (dir.isDirectory() && dir.getName().toLowerCase().contains("lsp")) {
					File db = new File(dir, "config/modules_config.db");
					if (db.exists()) {
						resolvedLspdDbPath = db.getAbsolutePath();
						return resolvedLspdDbPath;
					}
				}
			}
		}

		resolvedLspdDbPath = LSPD_DB_DEFAULT_PATH;
		return resolvedLspdDbPath;
	}

	@Override
	public IBinder onBind(@NonNull Intent intent) {
		return new RootServicesIPC();
	}

	/** @noinspection RedundantThrows*/
	class RootServicesIPC extends IRootProviderService.Stub
	{
		int mLSPosedMID = -1;
		private boolean mLSPosedEnabled = false;
		private Boolean mLegacyLSPosedSchema = null;


		@Override
		public boolean checkLSPosedDB(String packageName) {
			if(Constants.SYSTEM_FRAMEWORK_PACKAGE.equals(packageName))
				packageName = "system";

			try
			{
				if(mLSPosedMID < 0 || !mLSPosedEnabled)
					getModuleMID();

				if(!mLSPosedEnabled)
					return false;


				return "1".equals(
						runLSposedSQLiteQuery(
								scopeCountQuery(packageName)
						).get(0));
			}
			catch (Throwable ignored) {
				return false;
			}
		}

		@Override
		public boolean isPackageInstalled(String packageName) throws RemoteException {
			PackageManager pm = getPackageManager();
			try {
				pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);
				return pm.getApplicationInfo(packageName, 0).enabled;
			} catch (PackageManager.NameNotFoundException ignored) {
				return false;
			}
		}

		@Override
		public boolean activateInLSPosed(String packageName) throws RemoteException {
			if (Constants.SYSTEM_FRAMEWORK_PACKAGE.equals(packageName)) //new LSPosed versions renamed framework
				packageName = "system";

			if (checkLSPosedDB(packageName))
				return true;

			getModuleMID();

			if (!mLSPosedEnabled) {
				enableModuleLSPosed();

				if (checkLSPosedDB(packageName))
					return true;
			}

			runLSposedSQLiteQuery(
					scopeInsertQuery(packageName));

			return checkLSPosedDB(packageName);
		}

		@Override
		public String buildDiagnosticsReport() {
			StringBuilder report = new StringBuilder(8192);

			appendLine(report, "PixelXpert Diagnostics");
			appendLine(report, "Generated: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()));
			appendLine(report, "Application ID: " + BuildConfig.APPLICATION_ID);
			appendLine(report, "Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
			appendLine(report, "Android SDK: " + Build.VERSION.SDK_INT);
			appendLine(report, "Device: " + Build.MANUFACTURER + " " + Build.MODEL);
			appendLine(report, "");

			appendSection(report, "Root");
			appendCommand(report, "id", "id");
			appendCommand(report, "su path", "command -v su 2>/dev/null || which su 2>/dev/null");
			appendCommand(report, "kernel", "uname -a");
			appendCommand(report, "build fingerprint", "getprop ro.build.fingerprint");
			appendCommand(report, "boot completed", "getprop sys.boot_completed");

			appendEnvironmentSummary(report);

			appendSection(report, "Managers");
			appendPackageStatus(report, "Magisk", MAGISK_PACKAGE);
			appendPackageStatus(report, "KernelSU", Constants.KSU_PACKAGE);
			appendPackageStatus(report, "KSU-Next", Constants.KSU_NEXT_PACKAGE);
			appendPackageStatus(report, "APatch", APATCH_PACKAGE);
			appendPackageStatus(report, "LSPosed", LSPOSED_PACKAGE);
			appendPackageStatus(report, "Vector", VECTOR_PACKAGE);

			appendSection(report, "Module Layout");
			appendPathStatus(report, "PixelXpert module", MODULE_PATH);
			appendPathStatus(report, "disable marker", MODULE_PATH + "/disable");
			appendPathStatus(report, "remove marker", MODULE_PATH + "/remove");
			appendPathStatus(report, "skip_mount marker", MODULE_PATH + "/skip_mount");
			appendPathStatus(report, "mount_error marker", MODULE_PATH + "/mount_error");
			appendPathStatus(report, "module.prop", MODULE_PATH + "/module.prop");
			appendPathStatus(report, "service.sh", MODULE_PATH + "/service.sh");
			appendPathStatus(report, "customize.sh", MODULE_PATH + "/customize.sh");
			appendPathStatus(report, "priv-app APK", MODULE_PATH + "/system/priv-app/PixelXpert/PixelXpert.apk");
			appendLine(report, "installed APK path: " + moduleApkPath());
			appendFilePreview(report, MODULE_PATH + "/module.prop", 20);

			appendSection(report, "Root Stack Files");
			appendPathStatus(report, "Magisk DB", "/data/adb/magisk.db");
			appendPathStatus(report, "KernelSU", "/data/adb/ksu");
			appendPathStatus(report, "APatch", "/data/adb/ap");
			appendPathStatus(report, "APatch alt", "/data/adb/apatch");
			appendCommand(report, "module dirs", "ls -1 /data/adb/modules 2>/dev/null | sort | head -80");

			appendSection(report, "Zygisk / LSPosed / Vector");
			appendPathStatus(report, "LSPosed DB", lspdDbPath());
			appendPathStatus(report, "sqlite3", SQLITE_BIN);
			appendLSPosedState(report);
			appendCommand(report, "zygisk modules", "ls -1 /data/adb/modules 2>/dev/null | grep -Ei 'zygisk|neo|lsposed|vector' || true");

			appendSection(report, "Hybrid-Mount");
			appendPathStatus(report, "config", HYBRID_MOUNT_CONFIG_PATHS[0]);
			appendPathStatus(report, "kasumi config", HYBRID_MOUNT_CONFIG_PATHS[1]);
			appendCommand(report, "hybrid-mount binaries", "find /data/adb -maxdepth 5 -type f -name hybrid-mount 2>/dev/null | head -10");
			appendCommand(report, "hybrid-mount modules", "ls -1 /data/adb/modules 2>/dev/null | grep -Ei 'hybrid|mount|overlay|magic|meta' || true");
			appendCommand(report, "hybrid-mount version", "hybrid-mount api version 2>/dev/null || /data/adb/modules/hybrid_mount/bin/hybrid-mount api version 2>/dev/null || true");
			appendCommand(report, "hybrid-mount config", "hybrid-mount api config-get 2>/dev/null || /data/adb/modules/hybrid_mount/bin/hybrid-mount api config-get 2>/dev/null || true");
			appendFilePreview(report, HYBRID_MOUNT_CONFIG_PATHS[0], 80);
			appendFilePreview(report, HYBRID_MOUNT_CONFIG_PATHS[1], 80);

			appendSection(report, "Mount State");
			appendCommand(report, "PixelXpert mountinfo", "grep -i PixelXpert /proc/self/mountinfo 2>/dev/null || true");
			appendCommand(report, "system mount summary", "mount 2>/dev/null | grep -E ' /system |PixelXpert|overlay|hybrid|magic' | head -80 || true");

			appendSection(report, "Failure Pointers");
			appendCommand(report, "recent dropbox", "ls -1t /data/system/dropbox/system_server_* /data/system/dropbox/*watchdog* /data/system/dropbox/*anr* 2>/dev/null | head -20");
			appendCommand(report, "recent tombstones", "ls -1t /data/tombstones/tombstone_* 2>/dev/null | head -20");
			appendCommand(report, "recent PixelXpert logcat", "logcat -d -t 400 2>/dev/null | grep -i PixelXpert | tail -80 || true");

			return report.toString();
		}

		private void enableModuleLSPosed() {
			if (usesLegacyLSPosedSchema()) {
				runLSposedSQLiteQuery(String.format("update modules set enabled = 1 where mid = %s", mLSPosedMID));
			} else {
				runLSposedSQLiteQuery(
						String.format("insert or replace into modules (module_pkg_name, apk_path) values ('%s','%s')",
								sql(BuildConfig.APPLICATION_ID), sql(moduleApkPath())));
				runLSposedSQLiteQuery(
						String.format("insert or replace into modules_state (module_pkg_name, user_id, enabled, scope_request_blocked) values ('%s',0,1,0)",
								sql(BuildConfig.APPLICATION_ID)));
				mLSPosedEnabled = true;
			}
		}

		private void getModuleMID()
		{
			if (usesLegacyLSPosedSchema()) {
				mLSPosedMID = Integer.parseInt(
						queryScalar(
								String.format("select mid from modules where module_pkg_name = '%s'", sql(BuildConfig.APPLICATION_ID))
						));

				mLSPosedEnabled = "1".equals(
						queryScalar(
								String.format("select enabled from modules where mid = %s", mLSPosedMID)
						));
			} else {
				mLSPosedMID = -1;
				mLSPosedEnabled = "1".equals(
						queryScalar(
								String.format("select enabled from modules_state where module_pkg_name = '%s' and user_id = 0", sql(BuildConfig.APPLICATION_ID))
						));
			}
		}

		private List<String> runLSposedSQLiteQuery(String command)
		{
			return Shell.cmd(String.format("%s %s \"%s\"", SQLITE_BIN, lspdDbPath(), command)).exec().getOut();
		}

		private String queryScalar(String command) {
			List<String> result = runLSposedSQLiteQuery(command);
			return result.isEmpty() ? "" : result.get(0).trim();
		}

		private boolean usesLegacyLSPosedSchema() {
			if (mLegacyLSPosedSchema != null) {
				return mLegacyLSPosedSchema;
			}

			String moduleTable = String.join("\n", runLSposedSQLiteQuery("PRAGMA table_info(modules);"));
			mLegacyLSPosedSchema = moduleTable.contains("|mid|");
			return mLegacyLSPosedSchema;
		}

		private String scopeCountQuery(String packageName) {
			if (usesLegacyLSPosedSchema()) {
				return String.format("select count(*) from scope where mid = %s and user_id = 0 and app_pkg_name = '%s'",
						mLSPosedMID, sql(packageName));
			}

			return String.format("select count(*) from scope where module_pkg_name = '%s' and user_id = 0 and app_pkg_name = '%s'",
					sql(BuildConfig.APPLICATION_ID), sql(packageName));
		}

		private String scopeInsertQuery(String packageName) {
			if (usesLegacyLSPosedSchema()) {
				return String.format("insert into scope (mid, app_pkg_name, user_id) values (%s, '%s', 0)",
						mLSPosedMID, sql(packageName));
			}

			return String.format("insert or ignore into scope (module_pkg_name, app_pkg_name, user_id) values ('%s', '%s', 0)",
					sql(BuildConfig.APPLICATION_ID), sql(packageName));
		}

		private String sql(String value) {
			return value.replace("'", "''");
		}

		private String moduleApkPath() {
			try {
				return getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).sourceDir;
			} catch (Throwable ignored) {
				return PRIV_APP_APK_PATH;
			}
		}

		private void appendEnvironmentSummary(StringBuilder report) {
			appendSection(report, "Environment Summary");

			List<String> neoZygiskModules = matchingModuleNames("neozygisk", "neo_zygisk");
			List<String> zygiskNextModules = matchingModuleNames("zygisknext", "zygisk_next", "zygisksu");
			List<String> hybridMountModules = matchingModuleNames("hybrid", "overlayfs", "magic_mount", "magicmount", "mountify");

			appendDetection(report, "KSU-Next",
					isPackageInstalledQuiet(Constants.KSU_NEXT_PACKAGE),
					"package " + Constants.KSU_NEXT_PACKAGE);
			appendDetection(report, "KernelSU",
					isPackageInstalledQuiet(Constants.KSU_PACKAGE) || pathExists("/data/adb/ksu"),
					"package " + Constants.KSU_PACKAGE + "; /data/adb/ksu=" + status(pathExists("/data/adb/ksu")));
			appendDetection(report, "Magisk",
					isPackageInstalledQuiet(MAGISK_PACKAGE) || anyPathExists("/data/adb/magisk.db", "/data/adb/magisk"),
					"package " + MAGISK_PACKAGE + "; magisk.db=" + status(pathExists("/data/adb/magisk.db")));
			appendDetection(report, "APatch",
					isPackageInstalledQuiet(APATCH_PACKAGE) || anyPathExists("/data/adb/ap", "/data/adb/apatch"),
					"package " + APATCH_PACKAGE + "; /data/adb/ap=" + status(pathExists("/data/adb/ap")));
			appendDetection(report, "NeoZygisk",
					!neoZygiskModules.isEmpty(),
					"modules=" + joinOrNone(neoZygiskModules));
			appendDetection(report, "ZygiskNext",
					!zygiskNextModules.isEmpty(),
					"modules=" + joinOrNone(zygiskNextModules));
			appendDetection(report, "LSPosed/Vector",
					pathExists(lspdDbPath()) || isPackageInstalledQuiet(LSPOSED_PACKAGE) || isPackageInstalledQuiet(VECTOR_PACKAGE),
					"db=" + lspdDbPath() + " " + status(pathExists(lspdDbPath()))
							+ "; managers=" + installedManagers(LSPOSED_PACKAGE, VECTOR_PACKAGE));
			appendDetection(report, "Hybrid-Mount",
					anyPathExists(HYBRID_MOUNT_CONFIG_PATHS) || anyPathExists(HYBRID_MOUNT_BINARY_PATHS) || !hybridMountModules.isEmpty(),
					"config=" + firstPresentPath(HYBRID_MOUNT_CONFIG_PATHS)
							+ "; binary=" + firstPresentPath(HYBRID_MOUNT_BINARY_PATHS)
							+ "; modules=" + joinOrNone(hybridMountModules));
			appendDetection(report, "PixelXpert rollback marker",
					pathExists(MODULE_PATH + "/disable"),
					MODULE_PATH + "/disable");
		}

		private void appendLSPosedState(StringBuilder report) {
			try {
				getModuleMID();
				appendLine(report, "LSPosed schema: " + (usesLegacyLSPosedSchema() ? "legacy mid" : "module_pkg_name"));
				if (usesLegacyLSPosedSchema()) {
					appendLine(report, "PixelXpert module id: " + mLSPosedMID);
				}
				appendLine(report, "PixelXpert module enabled: " + mLSPosedEnabled);
				appendSQLiteQuery(report, "LSPosed tables", ".tables");
				appendSQLiteQuery(report, "scope system", scopeCountQuery("system"));
				appendSQLiteQuery(report, "scope SystemUI", scopeCountQuery(Constants.SYSTEM_UI_PACKAGE));
				appendSQLiteQuery(report, "scope Launcher", scopeCountQuery(Constants.LAUNCHER_PACKAGE));
			} catch (Throwable t) {
				appendLine(report, "LSPosed/Vector state unavailable: " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()));
			}
		}

		private void appendSQLiteQuery(StringBuilder report, String label, String query) {
			try {
				appendLines(report, label, runLSposedSQLiteQuery(query), 20);
			} catch (Throwable t) {
				appendLine(report, label + ": unavailable (" + t.getClass().getSimpleName() + ")");
			}
		}

		private void appendDetection(StringBuilder report, String label, boolean detected, String evidence) {
			appendLine(report, label + ": " + (detected ? "detected" : "not detected") + " (" + evidence + ")");
		}

		private void appendPackageStatus(StringBuilder report, String label, String packageName) {
			try {
				appendLine(report, label + " (" + packageName + "): " + (isPackageInstalled(packageName) ? "installed/enabled" : "not installed or disabled"));
			} catch (Throwable t) {
				appendLine(report, label + " (" + packageName + "): unavailable (" + t.getClass().getSimpleName() + ")");
			}
		}

		private boolean isPackageInstalledQuiet(String packageName) {
			try {
				return isPackageInstalled(packageName);
			} catch (Throwable ignored) {
				return false;
			}
		}

		private boolean anyPathExists(String... paths) {
			for (String path : paths) {
				if (pathExists(path)) {
					return true;
				}
			}
			return false;
		}

		private boolean pathExists(String path) {
			return new File(path).exists();
		}

		private String firstPresentPath(String... paths) {
			for (String path : paths) {
				if (pathExists(path)) {
					return path;
				}
			}
			return "none";
		}

		private String installedManagers(String... packageNames) {
			List<String> installed = new ArrayList<>();
			for (String packageName : packageNames) {
				if (isPackageInstalledQuiet(packageName)) {
					installed.add(packageName);
				}
			}
			return joinOrNone(installed);
		}

		private List<String> matchingModuleNames(String... nameFragments) {
			List<String> matches = new ArrayList<>();
			File modulesDir = new File("/data/adb/modules");
			File[] moduleDirs = modulesDir.listFiles();
			if (moduleDirs == null) {
				return matches;
			}

			for (File moduleDir : moduleDirs) {
				if (!moduleDir.isDirectory()) {
					continue;
				}

				String moduleName = moduleDir.getName().toLowerCase(Locale.US);
				for (String nameFragment : nameFragments) {
					if (moduleName.contains(nameFragment)) {
						matches.add(moduleDir.getName());
						break;
					}
				}
			}
			return matches;
		}

		private String joinOrNone(List<String> values) {
			return values.isEmpty() ? "none" : String.join(", ", values);
		}

		private String status(boolean present) {
			return present ? "present" : "missing";
		}

		private void appendPathStatus(StringBuilder report, String label, String path) {
			File file = new File(path);
			appendLine(report, label + ": " + path + " [" + (file.exists() ? "present" : "missing") + "]");
		}

		private void appendFilePreview(StringBuilder report, String path, int maxLines) {
			File file = new File(path);
			if (!file.exists() || !file.isFile()) {
				return;
			}

			appendLine(report, path + ":");
			try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
				String line;
				int lineCount = 0;
				while ((line = reader.readLine()) != null && lineCount < maxLines) {
					appendLine(report, "  " + line);
					lineCount++;
				}
				if (reader.readLine() != null) {
					appendLine(report, "  ...");
				}
			} catch (Throwable t) {
				appendLine(report, "  unavailable (" + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()) + ")");
			}
		}

		private void appendCommand(StringBuilder report, String label, String command) {
			try {
				appendLines(report, label, Shell.cmd(command).exec().getOut(), 120);
			} catch (Throwable t) {
				appendLine(report, label + ": unavailable (" + t.getClass().getSimpleName() + ")");
			}
		}

		private void appendLines(StringBuilder report, String label, List<String> lines, int maxLines) {
			appendLine(report, label + ":");
			if (lines == null || lines.isEmpty()) {
				appendLine(report, "  (no output)");
				return;
			}
			for (int i = 0; i < lines.size() && i < maxLines; i++) {
				appendLine(report, "  " + lines.get(i));
			}
			if (lines.size() > maxLines) {
				appendLine(report, "  ...");
			}
		}

		private void appendSection(StringBuilder report, String title) {
			appendLine(report, "");
			appendLine(report, "## " + title);
		}

		private void appendLine(StringBuilder report, String line) {
			report.append(line).append('\n');
		}

		@Override
		public IBinder getFileSystemService(){
			return FileSystemManager.getService();
		}
	}
}
