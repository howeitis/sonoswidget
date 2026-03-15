# SonosWidget — Task 1.1 Build Report

**Task:** Project Bootstrap
**Agent:** Claude Code (Opus 4)
**Date:** March 13, 2026
**Status:** Complete — all acceptance criteria pass
**Build time:** ~2 minutes 13 seconds (clean assembleDebug)

---

## What Was Built

A fully compiling Android project scaffold for the SonosWidget app, targeting Pixel 9 / Android 16 (API 36). The project is ready to receive Task 1.2 (Widget Shell).

### Files Created

| File | Purpose |
|------|---------|
| `build.gradle.kts` (root) | Project-level Gradle config; declares all plugins |
| `app/build.gradle.kts` | App module with all dependencies from PRD Section 17 |
| `settings.gradle.kts` | Module include + repository config |
| `gradle.properties` | JVM args, AndroidX, R class config |
| `gradle/libs.versions.toml` | Full version catalog (pinned versions per PRD) |
| `gradlew` / `gradlew.bat` | Gradle wrapper scripts (Gradle 8.11.1) |
| `gradle/wrapper/*` | Wrapper JAR + properties |
| `AndroidManifest.xml` | All 7 permissions + cleartext traffic config |
| `res/xml/network_security_config.xml` | Local-network cleartext restriction |
| `SonosApplication.kt` | `@HiltAndroidApp` entry point |
| `res/values/strings.xml` | All user-facing strings (controls, states, errors) |
| `res/values/themes.xml` | Material 3 dark theme for companion app |
| `res/values/colors.xml` | Theme + widget default colors |
| `res/drawable/ic_launcher_*.xml` | Adaptive icon (speaker symbol on dark background) |
| `res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon manifest |
| `app/proguard-rules.pro` | Keep rules for Hilt, Glance, Retrofit |

### APK Verification

- **Package:** `com.sycamorecreek.sonoswidget`
- **compileSdk:** 36 (Android 16)
- **minSdk:** 35 / **targetSdk:** 36
- **`usesCleartextTraffic`:** true (confirmed in APK manifest)
- **`networkSecurityConfig`:** present (confirmed in APK manifest)
- **All permissions present:** INTERNET, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, NEARBY_WIFI_DEVICES, FOREGROUND_SERVICE, FOREGROUND_SERVICE_MEDIA_PLAYBACK, POST_NOTIFICATIONS

---

## Deviations from PRD

### 1. Hilt version: 2.52 changed to 2.58

**Why:** Hilt 2.52 is incompatible with KSP2, which became the default annotation processing backend in Kotlin 2.1.x. The build fails with:

```
[Hilt] Expected @HiltAndroidApp to have a value.
Did you forget to apply the Gradle Plugin?
```

This is a known Dagger bug (GitHub issues #4766, #4303) fixed in Hilt 2.57.1. We chose 2.58 as the target because:
- 2.57.1+ fixes the KSP2 annotation value passing
- 2.58 is the latest version compatible with AGP 8.10.x
- 2.59+ requires AGP 9.0.0 (which would cascade into other changes)

**Risk:** Low. Hilt 2.58 is API-compatible with 2.52. No code changes needed beyond the version bump.

**PRD recommendation:** Update Section 17.1 version catalog to specify `hilt = "2.58"` for future agent tasks.

### 2. KSP added as annotation processor

The PRD version catalog didn't include KSP, but Kotlin 2.1.x has deprecated `kapt` in favor of KSP. We added:
- `ksp = "2.1.21-2.0.1"` to the version catalog
- KSP plugin to both root and app build files
- Hilt compiler wired via `ksp()` instead of `kapt()`

### 3. Configuration cache disabled

The Gradle configuration cache (`org.gradle.configuration-cache`) was set to `false` in `gradle.properties`. The Hilt Gradle plugin's bytecode transformation can conflict with configuration cache in some scenarios. This can be re-enabled in later tasks once the build stabilizes and is verified.

### 4. Additional dependencies added

The PRD version catalog covered the core libraries but didn't list some transitive requirements. We added:
- `activity-compose` (1.9.3) — required for Compose Activity integration
- `core-ktx` (1.15.0) — standard AndroidX core extensions
- `lifecycle-runtime-ktx` (2.8.7) — lifecycle-aware coroutine scopes
- Compose UI sub-artifacts (ui, material3, tooling-preview) — managed by the Compose BOM

These are standard Android project dependencies and don't change the architecture.

---

## Issues Encountered During Build

### 1. Environment script bug (sonoswidget_env_check.sh)

The pre-build environment check script crashed on first error due to `set -e` combined with `((errors++))` (which returns exit code 1 when incrementing from 0 in bash). Also, the script was WSL2-only but we're building in Git Bash on Windows.

**Fixes applied:**
- Changed counter increments to `errors=$((errors + 1))` (safe with `set -e`)
- Replaced hanging `cmd.exe /C` call with `${USERNAME}` env var
- Added Git Bash SDK paths alongside WSL `/mnt/c/` paths
- Fixed `df -BG` (Linux-only) to fall back to plain `df` on Git Bash
- Fixed SDK Platform 36 detection to match `android-36.1` directory name

### 2. Launcher icon XML error

Duplicate `android:fillColor` attribute on SVG path elements. Fixed by removing the duplicate.

### 3. Java not on PATH

Android Studio's bundled JBR (JDK 21) was available but not on the system PATH. Builds require `JAVA_HOME` to be set:
```
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
```

**PRD recommendation:** Add a `local.properties` setup step to Task 1.1, or document the JAVA_HOME requirement for the Windows/Git Bash build environment.

---

## Feedback for Next Tasks

### For Task 1.2 (Widget Shell)

1. **JAVA_HOME and ANDROID_HOME** must be set before running `./gradlew`. On this machine:
   ```
   JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
   ANDROID_HOME="/c/Users/omhhe/AppData/Local/Android/Sdk"
   ```

2. The project uses **KSP** (not kapt) for annotation processing. Any future annotation processors (Room, etc.) should use `ksp()` in dependencies.

3. The **Compose BOM** (`2024.12.01`) is included for the companion app. Widget code must only use `androidx.glance.*` imports — never `androidx.compose.*`.

4. **Glance 1.2.0-rc01** is the widget framework version. The agent for Task 1.2 should verify that `providePreview()` API is available in this RC before implementing the widget picker preview.

### For PRD v1.9 (if updated)

1. **Update Hilt version** from 2.52 to 2.58 in Section 17.1.
2. **Add KSP** to the version catalog and plugin list in Section 17.1.
3. **Add `activity-compose`, `core-ktx`, and `lifecycle-runtime-ktx`** to the libraries list — these are required for any Compose-based Activity.
4. **Note the JAVA_HOME requirement** for building outside Android Studio (CLI builds, agent builds).
5. Consider adding a `local.properties.example` template to the task file list.

---

## Build Verification Command

To re-verify the build at any time:

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
export ANDROID_HOME="/c/Users/omhhe/AppData/Local/Android/Sdk"
cd "<project-root>"
./gradlew assembleDebug
```

Expected output: `BUILD SUCCESSFUL` with 40 tasks executed, producing `app/build/outputs/apk/debug/app-debug.apk`.
