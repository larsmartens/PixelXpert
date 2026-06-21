PKGNAME="sh.siava.pixelxpert"
PKGPATH="/system/priv-app/PixelXpert/PixelXpert.apk"
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

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

migratePrefs(){
  am start -n "$PKGNAME/.ui.activities.SettingsActivity" -e migratePrefs true > /dev/null
}

#activate PKGNAME in Lsposed
activateModuleLSPD()
{	
	DBPATH=$LSPDDBPATH
	
	ui_print '- Trying to activate the module in Lsposed...'	
	
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

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"android\",0);" && runSQL
	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"system\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"com.android.systemui\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"com.google.android.apps.nexuslauncher\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"com.google.android.dialer\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"com.android.phone\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"com.android.settings\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"me.weishu.kernelsu\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"com.rifsxd.ksunext\",0);" && runSQL

	CMD="insert into scope (mid, app_pkg_name, user_id) values ($NEWMID, \"$PKGNAME\",0);" && runSQL
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

ui_print ''
ui_print ''

grantRootApps

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
