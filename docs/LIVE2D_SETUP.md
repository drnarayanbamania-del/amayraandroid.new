# Live2D Cubism SDK — Setup Guide for Maya

Step-by-step instructions to obtain the license-gated Cubism SDK, integrate it into
this project behind the existing `CubismEngine` seam, and stay compliant with the
Live2D license terms. Researched 2026-09 against Live2D's official pages (links at
the bottom).

---

## 0. What you will download

| Piece | What it is | License that covers it | Can be committed to this repo? |
|---|---|---|---|
| **Cubism Core** | Prebuilt shared library (`libLive2DCubismCore.so` for Android arm64/armv7/x86_64) | **Live2D Proprietary Software License Agreement** | **NO** — never commit; see license notes |
| **Cubism Framework** | C++ source layer over Core (`Framework/`) — rendering, model loading, motions, expressions, physics | **Live2D Open Software License Agreement** (but it links against Core, so the combined app inherits the Proprietary terms for the Core part) | Framework source: yes (its license permits). Core `.so`: **no** |
| **Native Samples** | Working Android/iOS/desktop demo apps | Framework license + sample-specific terms | Reference only, do not vendor wholesale |

This is why this repo ships the *integration architecture* (`app/src/main/java/com/amayra/maya/avatar/live2d/`)
but not the runtime: the Core is a proprietary binary that each developer must download
themselves after accepting the license.

---

## 1. Prerequisites

- Android Studio (this project's AGP 9.3.1 toolchain already installed).
- **NDK** and **CMake**: Android Studio → Settings → Languages & Frameworks → Android SDK →
  SDK Tools → check "NDK (Side by side)" and "CMake" → Apply. (This machine currently has
  neither installed — that's the other half of why native integration isn't built yet.)
- A Live2D Cubism model in Cubism-3 export format (`.moc3` + `model3.json`).

## 2. Download the SDK (license-gated, ~10 minutes)

1. Go to **https://www.live2d.com/en/sdk/download/native/**
   (the live download page; the files are NOT on Maven, GitHub, or any package manager —
   every direct URL returns 404 by design).
2. Read and accept **both** agreements when prompted:
   - *Live2D Proprietary Software License Agreement* (covers Cubism Core)
   - *Live2D Open Software License Agreement* (covers Framework)
3. Download **Cubism SDK for Native** — current release line is **Cubism 5 SDK R5**
   (R4_2 was the prior stable; anything 5.x supports `.moc3` model3 `version: 3`, which is
   what Maya's validator requires).
4. Optional but useful: clone **https://github.com/live2d/cubismnativesamples** — the
   `Samples/Android` app demonstrates the exact load/motion/render flow this project mirrors.

The zip unpacks to roughly:

```
CubismSdkForNative-5-r5/
├── Core/                    <- proprietary prebuilt libs + headers (live2d/Live2DCubismCore.h)
│   ├── dll/android/arm64-v8a/libLive2DCubismCore.so   (+ armeabi-v7a, x86_64)
│   └── include/Live2DCubismCore.hpp ...
├── Framework/               <- C++ source (open license)
└── Samples/                 <- sample apps + RedistributableFiles.txt
```

## 3. Create the `:live2d` module in this project

```
maya-android/                     <- this repo
├── app/                          <- existing Maya app (do not modify for Live2D)
└── live2d/                       <- NEW module
    ├── build.gradle.kts          <- com.android.library + kotlin-android
    └── src/main/
        ├── cpp/
        │   ├── CMakeLists.txt
        │   ├── maya_cubism_jni.cpp        <- implements the JNI bridge
        │   └── (copy of Framework/ here)  <- from the SDK zip
        └── java/com/amayra/maya/live2d/
            └── MayaCubismEngine.kt        <- implements CubismEngine, calls JNI
```

1. Copy `Framework/` from the SDK zip into `live2d/src/main/cpp/Framework/`.
2. Copy the three `libLive2DCubismCore.so` binaries into
   `live2d/src/main/jniLibs/arm64-v8a/`, `.../armeabi-v7a/`, `.../x86_64/`.
3. In `settings.gradle.kts` add `include(":live2d")`; in `app/build.gradle.kts` add
   `implementation(project(":live2d"))`.
4. Wire `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }`
   in the `live2d` module.

## 4. Implement the engine (the only Maya-specific code)

`MayaCubismEngine.kt` implements the existing **`CubismEngine`** interface
(`app/src/main/java/com/amayra/maya/avatar/live2d/Live2DRenderer.kt`). No Maya UI,
voice, or AI code changes — the seam already exists:

```kotlin
class MayaCubismEngine : CubismEngine {
    override val engineName = "cubism-native-5-r5 (JNI)"
    // load():    read assets/live2d/maya/maya.model3.json via Live2DModelRepository,
    //            feed moc3 bytes + textures to JNI, create CubismModel + CubismMotionQueue
    // update():  push Live2DFrameParams values (ParamMouthOpenY, ParamEyeLOpen, ...)
    //            + start/stop motion groups via JNI
    // render():  draw to an OpenGL texture shared with the Compose canvas
    // release(): destroy model + free texture
}
```

Then register it once at app start (e.g. in `MayaApplication.onCreate`):

```kotlin
Live2DEngines.register(MayaCubismEngine())
```

`Live2DAvatarRenderer` picks it up automatically — when the packaged model passes
validation (MOC3 magic check, texture presence) AND an engine is registered, the
avatar renders Live2D; otherwise it keeps showing the clearly-labeled holographic
placeholder. Nothing else needs to change.

## 5. Verify

1. `./gradlew :live2d:externalNativeBuildDebug` — CMake compiles Framework + JNI.
2. `./gradlew assembleDebug` — full APK builds with the `.so` packaged.
3. Install, open Settings → Voice & Avatar → Avatar style → **Live2D (Cubism)**.
4. Confirm in logcat (`adb logcat -s Maya`) the lines:
   - `Cubism engine registered: cubism-native-5-r5 (JNI)`
   - `Cubism engine loaded: ...` and `Motion group -> idle`
   - (absence of) the "engine binding not packaged" badge
5. Exercise the pipeline: send a chat message with Auto-speak on → watch
   LISTENING → PROCESSING → SPEAKING transitions and `ParamMouthOpenY` driven
   mouth movement; tap the avatar mid-speech → TTS stops, mouth stops, LISTENING.

## 6. License — what applies to Maya specifically

**Two agreements govern the downloaded pieces:**

- **Proprietary Software License Agreement** (Cubism Core): you may use it to build
  and *Publish* a Derivative Work only if you qualify for the Publication License
  exemption, otherwise you need a paid Publication License Agreement with Live2D.
- **Open Software License Agreement** (Framework source): permissive for use inside
  your app, but the app still embeds the proprietary Core.

**The revenue tiers that decide whether Maya needs a paid license** (§1.21–1.24 of the
Proprietary agreement, current text on live2d.com):

| Classification | Latest fiscal-year sales | Publication License needed to publish? |
|---|---|---|
| General User (individuals/students/circles) | < ¥10,000,000 | **No** — exempt under §2.2 |
| Small-Scale Enterprise | < ¥10,000,000 | **No** — exempt under §2.2 |
| Middle-Scale Enterprise | ¥10M – ¥100M | **Yes** (or Simple License Plan for temporary promos) |
| Large-Scale Enterprise | ≥ ¥100M | **Yes** |

**Critical clause for Maya specifically — §1.5 "Expandable Application":** any
Derivative Work "having significant expandability… which uses and generates any
indefinite number of models by adding or combining files" (their examples: avatars,
live-streaming apps). **Maya ships a user-replaceable `assets/live2d/maya/` folder and
a model picker — that almost certainly qualifies as an Expandable Application.** §2.1
requires **prior application to and approval by Live2D before executing a Publication
License Agreement**, and §2.2 ends with: *"this exemption is not applicable for
Publishing the Expandable Application."*

Translation: even a General User / Small-Scale Enterprise **cannot rely on the free
exemption** if Maya's user-swappable-model design is shipped publicly. Two safe paths:

1. **Personal/private use** — no "Publishing" occurs (sideloading your own build for
   yourself is Internal/Training use under §1.8/§1.9, not Publication). Free, no
   agreement needed. This is what the current placeholder-only build is for.
2. **Public release of Maya with Live2D** — contact Live2D (Customer Support /
   https://www.live2d.com/en/business/) *before* publishing, disclose the expandable
   nature, and execute the (paid) Publication License Agreement for an Expandable
   Application.

**Also note:**
- Keep the Core `.so` **out of any public repo** of this project (see `RedistributableFiles.txt`
  in the SDK zip for what may be redistributed; the Core binary for embedding in a
  *published app* is allowed under the Publication License, but committing it to an
  open-source repo is not the same thing).
- Model assets have their **own** licenses separate from the SDK — the model in
  `assets/live2d/maya/` must be one you own or have a license to use.
- If fiscal sales cross ¥10M, the exemption retroactively ends (§2.2 requires
  notification and back-payment to the month sales exceeded the threshold).

## 7. Troubleshooting

| Symptom | Fix |
|---|---|
| `CMake not found` | Install CMake + NDK via SDK Manager (step 1) |
| `libLive2DCubismCore.so: not found` at runtime | `.so` missing from `jniLibs/<abi>/` — re-copy from the SDK zip for **all three ABIs** |
| Model loads but invisible | Check `layouts` in model3.json / try the official Samples app with the same model to isolate |
| `version must be 3` from Maya's validator | Re-export from Cubism Editor 4/5 with "Cubism 3" format target |
| Badge says "model invalid" | Read the exact message in `adb logcat -s Maya` — the validator reports the precise missing/invalid file |
| Motions not playing | Group names in model3.json must match `idle`/`listening`/`thinking`/`speaking`/`greeting`/`happy`/`concerned`/`error` (or rely on the graceful fallback chain in `Live2DController`) |

## 8. Source pages (verified 2026-09)

- Download page: https://www.live2d.com/en/sdk/download/native/
- SDK overview: https://www.live2d.com/en/sdk/about/
- Proprietary Software License Agreement: https://www.live2d.com/eula/live2d-proprietary-software-license-agreement_en.html
- Publication License / business inquiries: https://www.live2d.com/en/business/slp/
- Native Samples: https://github.com/live2d/cubismnativesamples
- Framework source: https://github.com/Live2D/CubismNativeFramework
- Cubism SDK manual: https://docs.live2d.com/en/cubism-sdk-manual/top/
