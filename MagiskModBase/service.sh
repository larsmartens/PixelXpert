PKGNAME="sh.siava.pixelxpert"
PKGPATH="/system/priv-app/PixelXpert/PixelXpert.apk"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"
MODDIR=${0%/*}

# Locate the LSPosed/Vector config DB (the manager may live under a renamed directory).
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
	chmod +x $MODDIR/sqlite3
	SQLITEPATH="$MODDIR/sqlite3"
}

# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome
runSQL(){
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

waitForMountedPackage(){
	echo "- 	Waiting for $PKGNAME package mount..."
	i=0
	while [ $i -lt 60 ]; do
		PMPATH=$(pm path $PKGNAME 2>/dev/null | sed 's/package://g' | head -1)
		if [ "$PMPATH" = "$PKGPATH" ] && [ -f "$PKGPATH" ]; then
			echo "- 	Package mount verified at $PKGPATH"
			return 0
		fi
		i=$((i + 1))
		sleep 1
	done

	echo "! 	$PKGNAME is not available at $PKGPATH; skipping LSPosed activation"
	return 1
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
	echo "- 	Granting root access to $1..."
	UID=$(pm list packages -U $1 --user 0 | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1)
	if [ -z "$UID" ]; then
		echo "! 	Package $1 is not installed for user 0; skipping root policy"
		return
	fi

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

#activate PKGNAME in Lsposed
activateModuleLSPD()
{
	DBPATH=$LSPDDBPATH

	echo '- Trying to activate the module in Lsposed...'

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

activateModuleLSPDVector()
{
	DBPATH=$LSPDDBPATH

	CMD="insert or replace into modules (module_pkg_name, apk_path) values (\"$PKGNAME\",\"$PKGPATH\");" && runSQL
	CMD="insert or replace into modules_state (module_pkg_name, user_id, enabled, scope_request_blocked) values (\"$PKGNAME\",0,1,0);" && runSQL

	for scope in android system com.android.systemui com.google.android.apps.nexuslauncher com.google.android.dialer com.android.phone com.android.settings me.weishu.kernelsu com.rifsxd.ksunext $PKGNAME; do
		CMD="insert or ignore into scope (module_pkg_name, app_pkg_name, user_id) values (\"$PKGNAME\", \"$scope\", 0);" && runSQL
	done
}

# Self-heal: clear stale flags that a previous failed mount could have left behind, so a transient
# mount failure doesn't keep our priv-app unmounted (and SystemUI without PixelXpert) on every boot.
selfHealMount(){
	for flag in skip_mount mount_error; do
		[ -f "$MODDIR/$flag" ] && rm -f "$MODDIR/$flag"
	done
}

selfHealMount

prepareSQL

grantRootApps

if resolveLspdDb; then
	activateModuleLSPD
fi
