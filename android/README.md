# Building ChudPresence Mobile

Open the `android` directory in Android Studio. Use JDK 21 and install Android SDK Platform 36, the Android SDK Build Tools, NDK 27.0.12077973, and CMake 3.22.1 when prompted.

The app requires the Discord Social SDK. Download it from the Discord Developer Portal and place `lib/release/discord_partner_sdk.aar` at `android/app/libs/discord_partner_sdk.aar`, with the supplied `License-Notices.txt` alongside it. These SDK files are not included in this repository.

Build the `app` configuration in Android Studio or run `gradlew.bat :app:assembleDebug` from the `android` directory on Windows. The debug APK is written to `app/build/outputs/apk/debug/`.

Twitch enrichment requires a Twitch public application client ID. Set `TWITCH_CLIENT_ID` as a Gradle property if you want to enable it. Do not put a client secret in the app.

For setup and everyday use, see the [user guide](../USER-GUIDE.md).
