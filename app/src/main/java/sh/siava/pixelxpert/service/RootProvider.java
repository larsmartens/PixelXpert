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
								String.format("select count(*) from scope where mid = %s and user_id = 0 and app_pkg_name = '%s'", mLSPosedMID, packageName)
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
					String.format("insert into scope (mid, app_pkg_name, user_id) values (%s, '%s', 0)", mLSPosedMID, packageName));

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

			appendSection(report, "Managers");
			appendPackageStatus(report, "Magisk", "com.topjohnwu.magisk");
			appendPackageStatus(report, "KernelSU", Constants.KSU_PACKAGE);
			appendPackageStatus(report, "KSU-Next", Constants.KSU_NEXT_PACKAGE);
			appendPackageStatus(report, "APatch", "me.bmax.apatch");
			appendPackageStatus(report, "LSPosed", "org.lsposed.manager");
			appendPackageStatus(report, "Vector", "io.github.vvb2060.mahoshojo");

			appendSection(report, "Module Layout");
			appendPathStatus(report, "PixelXpert module", "/data/adb/modules/PixelXpert");
			appendPathStatus(report, "disable marker", "/data/adb/modules/PixelXpert/disable");
			appendPathStatus(report, "remove marker", "/data/adb/modules/PixelXpert/remove");
			appendPathStatus(report, "skip_mount marker", "/data/adb/modules/PixelXpert/skip_mount");
			appendPathStatus(report, "mount_error marker", "/data/adb/modules/PixelXpert/mount_error");
			appendPathStatus(report, "module.prop", "/data/adb/modules/PixelXpert/module.prop");
			appendPathStatus(report, "service.sh", "/data/adb/modules/PixelXpert/service.sh");
			appendPathStatus(report, "customize.sh", "/data/adb/modules/PixelXpert/customize.sh");
			appendPathStatus(report, "priv-app APK", "/data/adb/modules/PixelXpert/system/priv-app/PixelXpert/PixelXpert.apk");
			appendFilePreview(report, "/data/adb/modules/PixelXpert/module.prop", 20);

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
			appendPathStatus(report, "config", "/data/adb/hybrid-mount/config.toml");
			appendPathStatus(report, "kasumi config", "/data/adb/hybrid-mount/kasumi.toml");
			appendCommand(report, "hybrid-mount binaries", "find /data/adb -maxdepth 5 -type f -name hybrid-mount 2>/dev/null | head -10");
			appendCommand(report, "hybrid-mount modules", "ls -1 /data/adb/modules 2>/dev/null | grep -Ei 'hybrid|mount|overlay|magic|meta' || true");
			appendCommand(report, "hybrid-mount version", "hybrid-mount api version 2>/dev/null || /data/adb/modules/hybrid_mount/bin/hybrid-mount api version 2>/dev/null || true");
			appendCommand(report, "hybrid-mount config", "hybrid-mount api config-get 2>/dev/null || /data/adb/modules/hybrid_mount/bin/hybrid-mount api config-get 2>/dev/null || true");
			appendFilePreview(report, "/data/adb/hybrid-mount/config.toml", 80);
			appendFilePreview(report, "/data/adb/hybrid-mount/kasumi.toml", 80);

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
			runLSposedSQLiteQuery(String.format("update modules set enabled = 1 where mid = %s", mLSPosedMID));
		}

		private void getModuleMID()
		{
			mLSPosedMID = Integer.parseInt(
					runLSposedSQLiteQuery(
							String.format("select mid from modules where module_pkg_name = '%s'", BuildConfig.APPLICATION_ID)
					).get(0));

			mLSPosedEnabled = "1".equals(
					runLSposedSQLiteQuery(
							String.format("select enabled from modules where mid = %s", mLSPosedMID)
					).get(0));
		}

		private List<String> runLSposedSQLiteQuery(String command)
		{
			return Shell.cmd(String.format("%s %s \"%s\"", SQLITE_BIN, lspdDbPath(), command)).exec().getOut();
		}

		private void appendLSPosedState(StringBuilder report) {
			try {
				getModuleMID();
				appendLine(report, "PixelXpert module id: " + mLSPosedMID);
				appendLine(report, "PixelXpert module enabled: " + mLSPosedEnabled);
				appendSQLiteQuery(report, "LSPosed tables", ".tables");
				appendSQLiteQuery(report, "scope system", String.format("select count(*) from scope where mid = %s and user_id = 0 and app_pkg_name = 'system'", mLSPosedMID));
				appendSQLiteQuery(report, "scope SystemUI", String.format("select count(*) from scope where mid = %s and user_id = 0 and app_pkg_name = '%s'", mLSPosedMID, Constants.SYSTEM_UI_PACKAGE));
				appendSQLiteQuery(report, "scope Launcher", String.format("select count(*) from scope where mid = %s and user_id = 0 and app_pkg_name = '%s'", mLSPosedMID, Constants.LAUNCHER_PACKAGE));
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

		private void appendPackageStatus(StringBuilder report, String label, String packageName) {
			try {
				appendLine(report, label + " (" + packageName + "): " + (isPackageInstalled(packageName) ? "installed/enabled" : "not installed or disabled"));
			} catch (Throwable t) {
				appendLine(report, label + " (" + packageName + "): unavailable (" + t.getClass().getSimpleName() + ")");
			}
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
