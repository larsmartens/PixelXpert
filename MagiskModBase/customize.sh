PKGNAME="sh.siava.pixelxpert"
PKGPATH="/system/priv-app/PixelXpert/PixelXpert.apk"
MODID="PixelXpert"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"

# Locate the LSPosed/Vector config DB. The manager was renamed (LSPosed -> Vector) and may live
# under a differently named directory, so fall back to a glob instead of a single hardcoded path.
resolveLspdDb(){
	for candidate in /data/adb/lspd/config/modules_config.db /data/adb/*lsp*/config/modules_config.db; do
		if [ -f "$candidate" ]; then
			LSPDDBPATH="$candidate"
			return 0
		fi
	done
	return 1
}

# PixelXpert ships its APK as a system priv-app, so the module's system/ tree must stay mounted for
# SystemUI to load it. This integrates with the mount layer optimally where possible and otherwise
# falls back to the root solution's default mounting (which already works for a standard module).
integrateMount(){
	for dir in "/data/adb/modules/$MODID" "/data/adb/modules_update/$MODID"; do
		[ -d "$dir" ] || continue
		# Clear transient mount errors only. A disable marker is the durable rollback control.
		for flag in skip_mount mount_error; do
			[ -f "$dir/$flag" ] && rm -f "$dir/$flag"
		done
	done

	# Hybrid-Mount (Full/Lite): register an explicit rule so our priv-app is mounted deterministically
	# via overlay (its default). Only added if absent, so user settings are never clobbered. When
	# Hybrid-Mount is not installed this is skipped and standard module mounting applies (fallback).
	HM_CONFIG="/data/adb/hybrid-mount/config.toml"
	if [ -f "$HM_CONFIG" ] && [ -w "$HM_CONFIG" ] && ! grep -q "rules.$MODID" "$HM_CONFIG" 2>/dev/null; then
		cp -f "$HM_CONFIG" "$HM_CONFIG.pxbak" 2>/dev/null
		printf '\n[rules.%s]\ndefault_mode = "overlay"\n' "$MODID" >> "$HM_CONFIG"
		ui_print "- Registered PixelXpert with Hybrid-Mount"
	fi
}

waitForMountedPackage(){
	ui_print "- 	Waiting for $PKGNAME package mount..."
	i=0
	while [ $i -lt 60 ]; do
		PMPATH=$(pm path $PKGNAME 2>/dev/null | sed 's/package://g' | head -1)
		if [ "$PMPATH" = "$PKGPATH" ] && [ -f "$PKGPATH" ]; then
			ui_print "- 	Package mount verified at $PKGPATH"
			return 0
		fi
		i=$((i + 1))
		sleep 1
	done

	ui_print "! 	$PKGNAME is not available at $PKGPATH; skipping activation until next boot"
	return 1
}

prepareSQL(){
	unzip $ZIPFILE sqlite3 -d $TMPDIR/ > /dev/null
	chmod +x $TMPDIR/sqlite3

	SQLITEPATH="$TMPDIR/sqlite3"
}

# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome
runSQL(){
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

#grant silent root access to given UID
grantRootUID(){
	DBPATH=$MAGISKDBPATH

	#new record - older magisk compatibility
	CMD="insert into policies (uid, package_name, policy, until, logging, notification) values ($1, '$2', 2, 0, 1, 0);" && runSQL
	#new record
	CMD="insert into policies (uid, policy, until, logging, notification) values ($1, 2, 0, 1, 0);" && runSQL
	#previously present record
	CMD="update policies set policy = 2, until = 0, logging = 1, notification = 0 where uid = $1;" && runSQL
}


#grant root access to given package name
grantRootPkg(){
	ui_print "- 	Granting root access to $1..."
	UID=$(pm list packages -U $1 --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)
	if [ -z "$UID" ]; then
		ui_print "! 	Package $1 is not installed for user 0; skipping root policy"
		return
	fi

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

getDefaultScopes(){
	SDK="$(getprop ro.build.version.sdk 2>/dev/null)"
	if [ "${SDK:-0}" -ge 37 ] 2>/dev/null; then
		if [ -f "/data/adb/modules/$MODID/a17_enable_default_scopes" ] || [ -f "/data/adb/modules_update/$MODID/a17_enable_default_scopes" ]; then
			echo "com.android.systemui com.google.android.apps.nexuslauncher com.google.android.dialer $PKGNAME"
		else
			echo "com.android.systemui $PKGNAME"
		fi
	else
		echo "android system com.android.systemui com.google.android.apps.nexuslauncher com.google.android.dialer com.android.phone com.android.settings me.weishu.kernelsu com.rifsxd.ksunext $PKGNAME"
	fi
}
migratePrefs(){
  am start -n "$PKGNAME/.ui.activities.SettingsActivity" -e migratePrefs true > /dev/null
}

#activate PKGNAME in Lsposed
activateModuleLSPD()
{
	DBPATH=$LSPDDBPATH

	ui_print '- Trying to activate the module in Lsposed...'

	if ! waitForMountedPackage; then
		return
	fi

	CMD="PRAGMA table_info(modules);" && runSQL
	if echo "$SQLRESULT" | grep -q "|mid|"; then
		activateModuleLSPDOld
	else
		activateModuleLSPDVector
	fi
}

activateModuleLSPDOld()
{
	DBPATH=$LSPDDBPATH

	CMD="select mid from modules where module_pkg_name like \"$PKGNAME\";" && runSQL
	OLDMID=$(echo $SQLRESULT | xargs)


	if [ $(($OLDMID+0)) -gt 0 ]; then
		CMD="select mid from modules where mid = $OLDMID and apk_path like \"$PKGPATH\" and enabled = 1;" && runSQL
		REALMID=$(echo $SQLRESULT | xargs)

		if [ $(($REALMID+0)) = 0 ]; then
			CMD="delete from scope where mid = $OLDMID;" && runSQL
			CMD="delete from modules where mid = $OLDMID;" && runSQL
		fi
	fi

#some commands may fail. It's OK if they do
	CMD="insert into modules (\"module_pkg_name\", \"apk_path\", \"enabled\") values (\"$PKGNAME\",\"$PKGPATH\", 1);" && runSQL

	CMD="select mid as ss from modules where module_pkg_name = \"$PKGNAME\";" && runSQL

	NEWMID=$(echo $SQLRESULT | xargs)

	for scope in $(getDefaultScopes); do
		CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"$scope\",0);" && runSQL
	done
}

activateModuleLSPDVector()
{
	DBPATH=$LSPDDBPATH

	CMD="insert or replace into modules (module_pkg_name, apk_path) values (\"$PKGNAME\",\"$PKGPATH\");" && runSQL
	CMD="insert or replace into modules_state (module_pkg_name, user_id, enabled, scope_request_blocked) values (\"$PKGNAME\",0,1,0);" && runSQL

	for scope in $(getDefaultScopes); do
		CMD="insert or ignore into scope (module_pkg_name, app_pkg_name, user_id) values (\"$PKGNAME\", \"$scope\", 0);" && runSQL
	done
}

testKernelSU()
{
	# Detect KernelSU / KernelSU-Next, by binary or by installed manager package.
	KSU_FOUND=0
	if [ "$(ksud -V 2>&1 | grep "not found" | wc -c)" -eq 0 ]; then KSU_FOUND=1; fi
	if [ "$(pm list packages 2>/dev/null | grep -e "com.rifsxd.ksunext" -e "me.weishu.kernelsu" | wc -c)" -ne 0 ]; then KSU_FOUND=1; fi

	if [ "$KSU_FOUND" -eq 1 ]; then #KSU installed
	if [[ $(pm list packages | grep $PKGNAME | wc -c) -eq 0 ]]; then #PixelXpert NOT installed yet
		ui_print ''
		ui_print '*******************************'
		ui_print 'KernelSU / KernelSU-Next found!'
		ui_print ''
		ui_print '                CAUTION!:'
		ui_print 'PixelXpert ships as a system priv-app and must'
		ui_print 'stay mounted for SystemUI to load it. Before'
		ui_print 'installing you MUST make sure this module is NOT'
		ui_print 'unmounted from the system:'
		ui_print '  - disable "Umount modules by default", and'
		ui_print '  - on SUSFS, exclude PixelXpert from try_umount.'
		ui_print 'This also applies with OverlayFS / Hybrid-Mount.'
		ui_print 'Otherwise, your device may fall into a BOOTLOOP!'
		ui_print ''
		ui_print 'Do you wish to continue?'
		ui_print 'Volume Up: Continue'
		ui_print 'Volume Down: Abort'
		if [[ "$(getevent -l | grep -m 1 KEY_VOLUME)" == *"VOLUMEDOWN"* ]]; then
			abort 'Installation cancelled'
		fi;
	fi;
    fi;
}

assertPixelRom()
{
	PixelTipsPattern="TipsPrebuilt*"
	PixelTipsParent="/product/priv-app"

  if ! find "$PixelTipsParent" -maxdepth 1 -name "$PixelTipsPattern" -print -quit | grep -q .; then
  	ui_print 'Device does not seem to be a Pixel'
  	ui_print 'phone, containing an original ROM.'

    abort 'Installation aborted due to incompatibility'
  fi
}

assertSupportedRom()
{
	# Pixel build ids start with a per-release letter: A15 = A*, A16 = B*, A17 = C*.
	# Accept Android 16 and 17 (any QPR); reject Android 15 and older.
	if [ -z "$(getprop ro.build.id | grep -e '^[BC][DP][0-9]')" ]; then
		ui_print 'This build is not compatible with'
    ui_print 'your ROM. Please install the stable'
    ui_print 'version 4.3.x instead'

		abort 'Installation aborted due to incompatibility'
  fi
}


assertPixelRom

assertSupportedRom

testKernelSU

prepareSQL

integrateMount

ui_print ''
ui_print ''

grantRootApps

set_perm $MODPATH/service.sh 0 0 0755

if resolveLspdDb; then
	ui_print ''
	ui_print ''

	activateModuleLSPD
	migratePrefs

	ui_print ''
	ui_print ''
	ui_print 'Installation Complete!'
	ui_print 'Please Reboot your device to activate'
else
	ui_print 'Lsposed not found!!'
	ui_print 'This module will not work without Lsposed'
	ui_print 'Please:'
	ui_print '- Install Lsposed'
	ui_print '- Reboot'
#	ui_print '- Manually enable PixelXpert in Lsposed'
#	ui_print '- Reboot'
fi

	ui_print ''
	ui_print '  **********************'
	ui_print '  * Brought to you by: *'
	ui_print '  *                    *'
	ui_print '  * PixelXpert team    *'
	ui_print '  **********************'
	ui_print ''
