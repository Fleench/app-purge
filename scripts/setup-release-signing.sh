#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE="${APP_PURGE_RELEASE_KEYSTORE:-"$ROOT_DIR/app-purge-release.jks"}"
PROPERTIES_FILE="$ROOT_DIR/keystore.properties"
ALIAS="${APP_PURGE_KEY_ALIAS:-app-purge-release}"
REPOSITORY="${APP_PURGE_GITHUB_REPOSITORY:-Fleench/app-purge}"
JAVA_HOME="${JAVA_HOME:-/tmp/app-purge-build-env/tools/jdk-17}"

if [ -e "$KEYSTORE" ]; then
    echo "Refusing to overwrite existing keystore: $KEYSTORE" >&2
    exit 1
fi

if [ -e "$PROPERTIES_FILE" ]; then
    echo "Refusing to overwrite existing signing config: $PROPERTIES_FILE" >&2
    exit 1
fi

if [ ! -x "$JAVA_HOME/bin/keytool" ] && ! command -v keytool >/dev/null 2>&1; then
    echo "keytool was not found. Set JAVA_HOME or run scripts/setup-local-build-env.sh first." >&2
    exit 1
fi

if ! command -v gh >/dev/null 2>&1; then
    echo "GitHub CLI was not found. Install gh or set GitHub Actions secrets manually." >&2
    exit 1
fi

gh auth status >/dev/null

random_secret() {
    od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
}

STORE_PASSWORD="$(random_secret)"
KEY_PASSWORD="$(random_secret)"
KEYTOOL_BIN="keytool"
if [ -x "$JAVA_HOME/bin/keytool" ]; then
    KEYTOOL_BIN="$JAVA_HOME/bin/keytool"
fi

umask 077
"$KEYTOOL_BIN" -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype JKS \
    -storepass "$STORE_PASSWORD" \
    -keypass "$KEY_PASSWORD" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -dname "CN=App Purge, O=Fleench, C=US" \
    -noprompt >/dev/null

{
    printf 'storeFile=%s\n' "$(realpath --relative-to="$ROOT_DIR" "$KEYSTORE")"
    printf 'storeType=JKS\n'
    printf 'storePassword=%s\n' "$STORE_PASSWORD"
    printf 'keyAlias=%s\n' "$ALIAS"
    printf 'keyPassword=%s\n' "$KEY_PASSWORD"
} > "$PROPERTIES_FILE"

gh secret set APP_PURGE_KEYSTORE_BASE64 -R "$REPOSITORY" --body "$(base64 -w0 "$KEYSTORE")" >/dev/null
gh secret set APP_PURGE_KEYSTORE_PASSWORD -R "$REPOSITORY" --body "$STORE_PASSWORD" >/dev/null
gh secret set APP_PURGE_KEY_ALIAS -R "$REPOSITORY" --body "$ALIAS" >/dev/null
gh secret set APP_PURGE_KEY_PASSWORD -R "$REPOSITORY" --body "$KEY_PASSWORD" >/dev/null

echo "Release signing is configured for $REPOSITORY."
echo "Keep $KEYSTORE and $PROPERTIES_FILE private and backed up."
