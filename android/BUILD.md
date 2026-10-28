# Building the Prompter Android app

Requirements: **JDK 17–21** (not newer — AGP), Android SDK with platform 35.

```bash
# point the build at your JDK (or set JAVA_HOME globally)
export JAVA_HOME=/path/to/jdk-21

# SDK location — create local.properties if gradle can't find it:
#   echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew assembleDebug
# result: app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected phone:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is fine for personal use. For a release build add a signing config (`signingConfigs` in `app/build.gradle.kts`) and run `./gradlew assembleRelease`.
