# NETHA KEYBOARD ULTRA

A native Android keyboard (IME) built with Kotlin.

## Build

Run the following command from the repository root:

```bash
./gradlew :keyboard:testDebugUnitTest :keyboard:assembleDebug
```

The debug APK is generated at:

`keyboard/build/outputs/apk/debug/`

GitHub Actions also builds the keyboard automatically when relevant files on the
`netha-keyboard-ultra` branch change. Open **Actions → NETHA KEYBOARD ULTRA APK**
to download the latest build artifact.

## Install and enable

1. Install the generated APK on the Android device.
2. Open **NETHA KEYBOARD ULTRA**.
3. Enable the keyboard in Android settings.
4. Select NETHA KEYBOARD ULTRA as the current keyboard.

## Wallpapers

Add supported JPG, JPEG, PNG or WebP images to the repository's `wallpapers/`
folder on the `netha-keyboard-ultra` branch. In the app, refresh the
**NETHA WALLPAPER** list to load them.

## Project layout

- `keyboard/` — Android app source, resources and unit tests.
- `wallpapers/` — Images available in the keyboard wallpaper picker.
- `.github/workflows/keyboard-build.yml` — Keyboard build and test workflow.
- `gradle/` and Gradle wrapper files — Build tooling.

The repository retains its existing license in `LICENSE`.
