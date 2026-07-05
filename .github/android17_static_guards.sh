#!/usr/bin/env bash
set -euo pipefail

fail() {
  echo "android17 static guard: $*" >&2
  exit 1
}

require_grep() {
  local pattern="$1"
  local file="$2"
  local message="$3"

  grep -Eq "$pattern" "$file" || fail "$message"
}

require_no_file() {
  local pattern="$1"
  local found

  found="$(find MagiskModBase -type f -name "$pattern" -print -quit)"
  [ -z "$found" ] || fail "opt-in marker must not ship by default: $found"
}

require_grep 'compileSdk[[:space:]]*=[[:space:]]*37' app/build.gradle.kts \
  "compileSdk must stay on Android 17/API 37 until this guard is updated"
require_grep 'targetSdk[[:space:]]*=[[:space:]]*36' app/build.gradle.kts \
  "targetSdk must stay at 36 until Android 17 target behavior is explicitly audited"

require_no_file 'a17_enable_privapp_mount'
require_no_file 'a17_enable_default_scopes'

require_grep 'persist[.]pixelxpert[.]disable_hooks' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "global hook kill switch property is missing"
require_grep 'persist[.]pixelxpert[.]a17[.]unsafe_scopes' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 unsafe-scope opt-in property is missing"
require_grep 'isSystemServer[[:space:]]*&&[[:space:]]*Build[.]VERSION[.]SDK_INT[[:space:]]*>=[[:space:]]*37' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 system_server skip gate is missing"
require_grep 'shouldDeferHookLoading' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 non-self package defer gate is missing"
require_grep 'Build[.]VERSION[.]SDK_INT[[:space:]]*>=[[:space:]]*37[[:space:]]*&&[[:space:]]*!PRParam[.]getPackageName[(][)][.]equals[(]APPLICATION_ID[)]' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 hook deferral must be self-only before boot completion"
require_grep 'shouldSkipModPackOnAndroid17' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 modpack skip policy is missing"
require_grep 'modPackData[.]targetPackage[.]isEmpty[(][)]' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 common-modpack skip is missing"
require_grep 'Constants[.]SYSTEM_FRAMEWORK_PACKAGE' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 framework-modpack skip is missing"
require_grep 'Constants[.]TELECOM_SERVER_PACKAGE' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 Telecom skip is missing"
require_grep 'Build[.]VERSION[.]SDK_INT[[:space:]]*<[[:space:]]*37' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 audio-focus workaround must stay SDK-gated"
require_grep 'runSafe[(]instance,param[[:space:]]*->' app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  "Android 17 audio-focus workaround must use runSafe"

for script in MagiskModBase/customize.sh MagiskModBase/service.sh; do
  require_grep 'a17_enable_privapp_mount' "$script" \
    "$script must keep Android 17 priv-app mounting behind an explicit marker"
  require_grep 'a17_enable_default_scopes' "$script" \
    "$script must keep Android 17 broad default scopes behind an explicit marker"
  require_grep 'touch.*skip_mount|skip_mount.*touch' "$script" \
    "$script must create skip_mount for Android 17 data-app mode"
  require_grep 'getDefaultScopes' "$script" \
    "$script must centralize default LSPosed scope policy"
  require_grep 'echo[[:space:]]+"[$]PKGNAME"' "$script" \
    "$script must default Android 17 LSPosed scope to PixelXpert self only"
done

actual="$(mktemp)"
expected="$(mktemp)"
trap 'rm -f "$actual" "$expected"' EXIT

git grep -n -E 'ReflectedClass[.]of[(]"com[.]android[.]' -- \
  app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java \
  app/src/main/java/sh/siava/pixelxpert/xposed/modpacks \
  | sed -E 's/:[0-9]+:/:/; s/[[:space:]]+/ /g; s/: /:/' \
  | sort > "$actual"

cat > "$expected" <<'EOF'
app/src/main/java/sh/siava/pixelxpert/xposed/XPLauncher.java:ReflectedClass PhoneWindowManagerClass = ReflectedClass.of("com.android.server.policy.PhoneWindowManager");
EOF
sort -o "$expected" "$expected"

if unexpected="$(comm -13 "$expected" "$actual")" && [ -n "$unexpected" ]; then
  echo "$unexpected" >&2
  fail "new hard com.android reflection in boot-sensitive hook code must be reviewed or allowlisted"
fi

unguarded_method_lookup="$(git grep -n -E 'findFirst[(][)][.]get[(]' -- \
  app/src/main/java/sh/siava/pixelxpert/xposed/modpacks || true)"

if [ -n "$unguarded_method_lookup" ]; then
  echo "$unguarded_method_lookup" >&2
  fail "modpack method discovery must fail closed instead of using findFirst().get()"
fi
