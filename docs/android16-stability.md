# Android 16 and 17 Stability Notes

This fork carries an Android 16/17 stability layer on top of upstream `canary`.

## Root Cause We Hit

The boot loop was caused by PixelXpert code running inside `system_server`, not by the Zygisk backend itself. The decisive crash record was a dropbox entry:

- `/data/system/dropbox/system_server_crash@1776064255019.txt`

The stack showed:

- `java.util.ConcurrentModificationException`
- `sh.siava.pixelxpert.xposed.XPrefs.loadEverything(XPrefs.java:35)`

## Fixes Carried In This Fork

- `565ab462` `fix(xposed): avoid concurrent preference reload crashes`
  - uses `CopyOnWriteArrayList` for running modules and preference listeners
- `6d25d4e5` `fix(reflection): propagate hook throwables to after callbacks`
  - restores throwable handling expected by `AppCloneEnabler`
- `0dd195c7` `Limit preference wait during Xposed startup`
  - bounds preference-provider waits in hooked processes
- Android 17 guarded priv-app mode
  - verifies the mounted APK before LSPosed activation and integrates with
    Hybrid-Mount when present
  - restricts default LSPosed scope to SystemUI and PixelXpert itself
  - defers preference-backed hooks while capturing boot-created SystemUI
    lifecycle objects through a preference-free bootstrap

## Debugging Workflow That Worked

1. Stabilize the phone first with the PixelXpert Magisk module disabled.
2. Inspect `/data/system/dropbox` for `system_server_crash`, `system_server_watchdog`, `system_server_pre_watchdog`, and `system_server_anr`.
3. Treat tombstones as secondary evidence. The failing path here only became obvious in dropbox.
4. Keep LSPosed/Vector core and third-party module scope isolation separate. Do not blame the backend until `system`-scoped modules are ruled out.
5. Install the replacement APK while the module is still disabled, then re-enable only after a verified copy and backup.
6. Validate a full boot-and-idle window, not just `sys.boot_completed=1`.

## Deployment Notes

- Historical priv-app module APK path:
  - `/data/adb/modules/PixelXpert/system/priv-app/PixelXpert/PixelXpert.apk`
- Active package-manager APK path:
  - `/system/priv-app/PixelXpert/PixelXpert.apk`
- Durable rollback control:
  - `/data/adb/modules/PixelXpert/disable`
- Transient mount markers cleared by the module service:
  - `/data/adb/modules/PixelXpert/skip_mount`
  - `/data/adb/modules/PixelXpert/mount_error`
- Prefer an on-device rollback script in `/data/adb/fork-module-updates/<change-id>/`.

## Residual Risk

Android framework and SystemUI hooks still carry normal canary risk after Pixel
monthly platform changes. If boot reliability regresses, compare dropbox
`system_server_*` entries, LSPosed scope state, app-visible mounts, and package
manager state before blaming the Zygisk backend.
