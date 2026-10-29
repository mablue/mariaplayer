# Signing

MariaPlayer uses one stable release key for every published build, so
install-over-install preserves all on-device data.

## Where the key lives

| Location | What |
|---|---|
| GitHub Secret KEYSTORE_B64 | base64 of mariaplayer.jks |
| GitHub Secret KEYSTORE_PASS | store password |
| GitHub Secret KEY_ALIAS | alias (mariaplayer) |
| GitHub Secret KEY_PASS | key password |
| Your password manager | backup of all four |
| ~/mariaplayer-keystore-backup.jks | local backup |
| ~/mariaplayer-keystore-password.txt | password (mode 600) |

Not in this repo. .gitignore blocks keystore/, *.jks, *.keystore, keystore.properties.

## Restore on a new machine

```bash
cd ~/mariaplayer
mkdir -p keystore
echo "$KEYSTORE_B64" | base64 -d > keystore/mariaplayer.jks
export KEYSTORE_PATH="$PWD/keystore/mariaplayer.jks"
export KEYSTORE_PASS="<from-password-manager>"
export KEY_ALIAS="mariaplayer"
export KEY_PASS="<from-password-manager>"
export VERSION_CODE=1
./gradlew :app:assembleRelease
```

## First migration: debug to release

One-time uninstall of the debug build is required. After that every
release installs over the previous one without signature mismatch.
