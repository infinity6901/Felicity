#!/usr/bin/env bash
# One-time (and idempotent) signing setup for the CI workflow.
#
#  1. Creates ONE permanent release keystore (signing/felicity-release.jks) if it doesn't exist.
#  2. Uploads it + its passwords to GitHub repository secrets:
#       SIGN_KEY (base64 keystore), ALIAS, KEY_PASSWORD, KEY_STORE_PASSWORD
#
# Usage:   scripts/setup-signing.sh [owner/repo]       (repo defaults to the current git remote)
# Env:     FORCE=1     overwrite secrets that already exist (DANGEROUS: changes the signing key
#                      if the keystore differs -> users could no longer update the app)
#          DRY_RUN=1   create the keystore locally but don't call GitHub
#
# NEVER commit signing/ — it is git-ignored. BACK UP signing/ somewhere safe (password manager,
# encrypted drive). If you lose this keystore you can never ship an update that installs over
# existing installs.
set -euo pipefail

cd "$(dirname "$0")/.."
DIR=signing
JKS="$DIR/felicity-release.jks"
INFO="$DIR/signing-info.txt"
ALIAS_NAME="felicity"
REPO="${1:-}"

mkdir -p "$DIR"
chmod 700 "$DIR"

if [ -f "$JKS" ] && [ -f "$INFO" ]; then
  echo "Reusing existing keystore $JKS"
  # shellcheck disable=SC1090
  source "$INFO"
else
  command -v openssl >/dev/null || { echo "openssl not found"; exit 1; }
  [ -f "$JKS" ] && { echo "$JKS exists but $INFO is missing - refusing to overwrite. Restore $INFO or move the keystore away."; exit 1; }
  STORE_PASS="$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | head -c 24)"
  # PKCS12 keystores use one password for store + key.
  if command -v keytool >/dev/null; then
    keytool -genkeypair -v -storetype PKCS12 \
      -keystore "$JKS" -alias "$ALIAS_NAME" \
      -keyalg RSA -keysize 4096 -validity 36500 \
      -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
      -dname "CN=Felicity, OU=Dev, O=Felicity, C=IN" >/dev/null
  else
    # Some environments (e.g. Android/PRoot) cannot run a JVM because anonymous
    # executable memory is blocked. Produce the same RSA-4096 / PKCS12 keystore
    # with openssl instead of keytool.
    echo "keytool not found - generating equivalent PKCS12 keystore with openssl"
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    openssl req -x509 -newkey rsa:4096 -sha256 -nodes -days 36500 \
      -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
      -subj "/CN=Felicity/OU=Dev/O=Felicity/C=IN" >/dev/null 2>&1
    openssl pkcs12 -export -name "$ALIAS_NAME" \
      -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
      -out "$JKS" -passout "pass:$STORE_PASS" >/dev/null 2>&1
    rm -rf "$TMP"
    trap - EXIT
  fi
  umask 077
  cat > "$INFO" <<INFO_EOF
ALIAS_NAME=$ALIAS_NAME
STORE_PASS=$STORE_PASS
INFO_EOF
  echo "Created keystore $JKS"
fi

source "$INFO"
echo
echo "Certificate fingerprint (this must never change between releases):"
if command -v keytool >/dev/null; then
  keytool -list -v -keystore "$JKS" -alias "$ALIAS_NAME" -storepass "$STORE_PASS" 2>/dev/null | grep -E "SHA256:|Owner:"
else
  openssl pkcs12 -in "$JKS" -nokeys -passin "pass:$STORE_PASS" 2>/dev/null \
    | openssl x509 -noout -fingerprint -sha256 -subject
fi

if [ "${DRY_RUN:-0}" = "1" ]; then
  echo "DRY_RUN=1 -> skipping GitHub secrets."
  exit 0
fi

command -v gh >/dev/null || { echo "GitHub CLI (gh) not found"; exit 1; }
gh auth status >/dev/null 2>&1 || { echo "Not logged in: run 'gh auth login' first"; exit 1; }
[ -z "$REPO" ] && REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
echo "Target repo: $REPO"

EXISTING="$(gh secret list -R "$REPO" | awk '{print $1}')"
if echo "$EXISTING" | grep -qx "SIGN_KEY" && [ "${FORCE:-0}" != "1" ]; then
  echo "Secret SIGN_KEY already exists in $REPO - leaving all signing secrets untouched."
  echo "(Set FORCE=1 only if you really intend to replace the signing key.)"
  exit 0
fi

base64 -w0 "$JKS" 2>/dev/null | gh secret set SIGN_KEY -R "$REPO" || base64 < "$JKS" | tr -d '\n' | gh secret set SIGN_KEY -R "$REPO"
printf '%s' "$ALIAS_NAME" | gh secret set ALIAS -R "$REPO"
printf '%s' "$STORE_PASS" | gh secret set KEY_PASSWORD -R "$REPO"
printf '%s' "$STORE_PASS" | gh secret set KEY_STORE_PASSWORD -R "$REPO"
echo "Secrets set: SIGN_KEY, ALIAS, KEY_PASSWORD, KEY_STORE_PASSWORD"
echo "REMINDER: back up the $DIR/ folder somewhere safe. It is NOT in git."
