#!/usr/bin/env bash
set -euo pipefail

required=(
  PIXELXPERT_SIGNING_KEY_BASE64
  PIXELXPERT_SIGNING_STORE_PASSWORD
  PIXELXPERT_SIGNING_KEY_PASSWORD
  PIXELXPERT_SIGNING_KEY_ALIAS
)

for variable in "${required[@]}"; do
  if [ -z "${!variable:-}" ]; then
    echo "PixelXpert signing secrets are unavailable; using the ephemeral debug key"
    exit 0
  fi
done

key_path="${RUNNER_TEMP:-/tmp}/pixelxpert-release.p12"
printf '%s' "$PIXELXPERT_SIGNING_KEY_BASE64" | base64 --decode > "$key_path"
chmod 600 "$key_path"

cat > ReleaseKey.properties <<EOF
storePassword=$PIXELXPERT_SIGNING_STORE_PASSWORD
keyPassword=$PIXELXPERT_SIGNING_KEY_PASSWORD
keyAlias=$PIXELXPERT_SIGNING_KEY_ALIAS
storeFile=$key_path
storeType=PKCS12
EOF

echo "Prepared persistent PixelXpert signing key"
