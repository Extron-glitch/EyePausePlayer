# EyePause Player 👁️

An Android video player that **pauses when you look away** and **resumes when you look back** — using your front camera + ML Kit face detection.

Built specifically for: **OnePlus Pad Go 2** (works on any Android 8.0+ device with a front camera)

---

## How It Works

- Uses **CameraX** to silently read the front camera
- **ML Kit Face Detection** checks every frame for a face
- If no face is detected for **1.5 seconds** → video pauses
- When your face returns → video resumes automatically
- A small badge (top-right) shows "Watching 🟢" or "Paused 🔴"

---

## How to Build & Install

### Option A — Android Studio (Easiest)

1. Install [Android Studio](https://developer.android.com/studio)
2. Open this folder as a project
3. Wait for Gradle sync to finish
4. Connect your OnePlus Pad Go 2 via USB (enable Developer Options + USB Debugging)
5. Click ▶ Run

### Option B — Command Line

```bash
# In the project root folder:
./gradlew assembleDebug

# APK will be at:
# app/build/outputs/apk/debug/app-debug.apk

# Install directly:
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## First Launch

1. Grant **Camera** permission (for eye tracking)
2. Grant **Storage/Media** permission (to pick videos)
3. Tap **"Pick a Video"** and choose any video from your device
4. The video starts playing — eye tracking is active!

You can also open video files directly from your file manager — the app registers itself as a video player.

---

## Customization (in MainActivity.kt)

| Setting | Variable | Default |
|---|---|---|
| Pause delay after looking away | `PAUSE_DELAY_MS` | 1500ms |
| Minimum face size to detect | `setMinFaceSize(0.15f)` | 15% of frame |

---

## Supported Formats

Whatever Android's built-in `VideoView` supports: MP4, MKV, AVI, 3GP, WebM, etc.

---

## Permissions Used

| Permission | Why |
|---|---|
| `CAMERA` | Front camera for face/eye detection |
| `READ_MEDIA_VIDEO` | Pick videos from storage (Android 13+) |
| `READ_EXTERNAL_STORAGE` | Pick videos (Android 12 and below) |

No data ever leaves your device. All processing is on-device.
