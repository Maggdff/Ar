# Smart Study — ذاكر بذكاء

Native Android Studio project based on the supplied `مقدمة_في_الحاسوب.txt`.

## Included
- Kotlin + Jetpack Compose UI with Arabic RTL support and light/dark appearance toggle.
- All 11 chapters extracted from the supplied course text, kept in `app/src/main/assets/chapters.json`.
- Chapter reading screen and quiz flow with feedback/explanations.
- OpenGL ES 2.0 rotating 3D cube scenes for quiz concepts; drag to rotate and pinch with two fingers to zoom.
- Home screen label: `Developed by Maggd Alosimi`.

## Build
1. Open this folder in Android Studio (recent version).
2. Allow Gradle sync and install Android SDK Platform 35 if prompted.
3. Connect a device or start an emulator, then Run `app`.

## Notes / limitations
- This environment did not have an Android SDK or Gradle installed, and network access to fetch Gradle dependencies was unavailable. Therefore this project has **not** been compiled here and no APK is claimed.
- The 3D scenes are real OpenGL-rendered geometric models, but they are conceptual diagrams built from cubes, not photorealistic models of every specific component. The scene layout varies by quiz topic.
- Quiz questions are starter questions grounded in the supplied course text; the full 11 chapters are available for reading.


## Automatic GitHub build
The workflow at `.github/workflows/android-build.yml` runs on push, pull request, or manual dispatch. It builds the debug APK and publishes it as the `SmartStudy-debug-apk` workflow artifact. After pushing the repository to GitHub, open **Actions → Build Android APK → latest run → Artifacts** to download it. This workflow has not been run from this environment.
