#!/usr/bin/env bash
# Verifies a built APK: it exists, has the expected package/version, and (unless --unsigned-ok)
# carries a valid signature. Prints the signing certificate digest so that debug and release
# builds - and successive runs - can be compared.
# Usage: verify-apk.sh <apk> [--unsigned-ok]
set -eu
APK="$1"; UNSIGNED_OK="${2:-}"
[ -s "$APK" ] || { echo "APK missing or empty: $APK"; exit 1; }
BT=$(ls -d "$ANDROID_SDK_ROOT"/build-tools/* | sort -V | tail -n1)
echo "== $APK ($(du -h "$APK" | cut -f1))"
"$BT/aapt2" dump badging "$APK" | grep -E "^package:|^sdkVersion|^targetSdkVersion|^native-code" || true
"$BT/aapt2" dump badging "$APK" | grep -q "package: name='app.simple.felicity'" \
  || { echo "unexpected package name"; exit 1; }
if "$BT/apksigner" verify --print-certs "$APK" 2>&1 | tee /tmp/apksigner.txt | grep -E "Verifies|SHA-256|DN"; then
  :
else
  if [ "$UNSIGNED_OK" = "--unsigned-ok" ]; then echo "APK is not signed (allowed)"; exit 0; fi
  cat /tmp/apksigner.txt; echo "APK signature verification failed"; exit 1
fi
