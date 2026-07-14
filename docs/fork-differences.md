# Fork Differences

This document summarizes the maintained differences between
`larsmartens/PixelXpert` and upstream `siavash79/PixelXpert`.

## Repository and Update Ownership

- The canonical source fork is the public GitHub-linked fork:
  `https://github.com/larsmartens/PixelXpert`.
- Update manifests are published through:
  `https://github.com/larsmartens/pixelxpert-updates`.
- Current canary release artifacts are published from the canonical linked
  fork. The legacy `larsmartens/PixelXpert-fork` repository retains historical
  stable releases.
- Canary workflows are fork-specific and include release serialization,
  generated changelog handling, fork-owned update metadata, and rollback guard
  checks.

## Android 17 and Root Stack Compatibility

- Targets Android 17/API 37 in the fork build layer.
- Uses data-app mode by default on Android 17 and creates `skip_mount` before
  activation. The historical mounted priv-app path remains available only with
  the explicit `a17_enable_privapp_mount` marker.
- Defaults Android 17 LSPosed scope to SystemUI and PixelXpert itself. Launcher
  and dialer scopes require `a17_enable_default_scopes`; framework and
  system-server hooks remain disabled unless explicitly opted into the unsafe
  compatibility path.
- Captures boot-created SystemUI lifecycle objects before preference-backed
  modpacks are deferred, allowing those modpacks to initialize against the live
  views after boot without reading preferences on the early boot path.
- Detects and supports KSU-Next, KernelSU-style layouts, Hybrid-Mount, and
  managerless LSPosed/Vector installations.
- Handles the newer LSPosed/Vector `module_pkg_name` schema in addition to the
  older `mid` schema.

## Stability Hardening

- Preference loading is bounded so hooked processes do not wait indefinitely for
  the preference provider.
- Modpack and preference listener collections are hardened against concurrent
  modification during system_server/SystemUI startup.
- Reflection hook helpers propagate callback failures in the paths that need to
  preserve upstream exception behavior.
- Hook wrappers and platform probing are centralized to reduce Android-version
  drift across SystemUI, framework, launcher, and settings hooks.
- PyTorch segmentation code is removed from the fork path to avoid 16 KB
  page-size compatibility issues.

## Diagnostics and Device Debugging

- PixelXpert can export a root-stack diagnostics report from the app.
- The report includes root backend detection, manager package state, module
  markers, installed APK path, LSPosed/Vector DB state, scope counts,
  Hybrid-Mount configuration, mount state, dropbox pointers, tombstone pointers,
  and recent PixelXpert logcat lines.
- The fork adds a system-trace capture quick settings tile for field debugging.

## App and UX Differences

- The update screen carries fork-specific visual context and repository links.
- Update checks are routed through fork-owned metadata.
- Settings-side compatibility fixes handle cloned apps and stale retained
  package records.
- Broadcasts from hooked system processes use explicit package targeting for
  more reliable status checks.
