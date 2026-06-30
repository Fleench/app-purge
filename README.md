# app-purge

## Local builds

Prerequisites:

- `curl` and `unzip`
- JDK 17 on `PATH` is optional; the setup script downloads a project-local JDK 17 when `java` is missing.

Set up the local Android SDK, Gradle cache, and Gradle wrapper:

```sh
./scripts/setup-local-build-env.sh
```

Build a debug APK:

```sh
JAVA_HOME="/tmp/app-purge-build-env/tools/jdk-17" ANDROID_HOME="/tmp/app-purge-build-env/android-sdk" GRADLE_USER_HOME="/tmp/app-purge-build-env/gradle-home" ./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Release signing

Android can only update an installed APK when the new APK has the same package
name and signing certificate as the installed app. App Purge release builds use
a stable release keystore configured through `keystore.properties` locally and
GitHub Actions secrets in CI.

To create the release keystore and configure the GitHub repository secrets:

```sh
./scripts/setup-release-signing.sh
```

This creates ignored local files:

```sh
app-purge-release.jks
keystore.properties
```

Keep both files private and backed up. If they are lost, future APKs cannot
update installs signed with that key.

Required GitHub Actions secrets:

- `APP_PURGE_KEYSTORE_BASE64`
- `APP_PURGE_KEYSTORE_PASSWORD`
- `APP_PURGE_KEY_ALIAS`
- `APP_PURGE_KEY_PASSWORD`

If an existing install was signed by an older debug or CI-generated key, Android
cannot update it to the new release key. That install needs one final uninstall
and reinstall. Future releases signed by this stable key should update in place.

Build a signed release APK:

```sh
JAVA_HOME="/tmp/app-purge-build-env/tools/jdk-17" ANDROID_HOME="/tmp/app-purge-build-env/android-sdk" GRADLE_USER_HOME="/tmp/app-purge-build-env/gradle-home" ./gradlew assembleRelease
```

## Install troubleshooting

If Android reports `INSTALL_PARSE_FAILED_NO_CERTIFICATES` while installing a
release APK, make sure you are installing the signed artifact:

```sh
app/build/outputs/apk/release/app-release.apk
```

The GitHub release workflow publishes this signed APK. Do not install or publish
`app-release-unsigned.apk`; Android rejects unsigned APKs because it cannot find
a signing certificate.
