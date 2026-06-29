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


## Install troubleshooting

If Android reports `INSTALL_PARSE_FAILED_NO_CERTIFICATES` while installing a
release APK, make sure you are installing the signed artifact:

```sh
app/build/outputs/apk/release/app-release.apk
```

The GitHub release workflow publishes this signed APK. Do not install or publish
`app-release-unsigned.apk`; Android rejects unsigned APKs because it cannot find
a signing certificate.
