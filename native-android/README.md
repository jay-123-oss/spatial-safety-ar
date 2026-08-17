# Spatial Safety AR — Pure ARCore Android module

यह native Android Studio module एक **offline, ARCore-only safety prototype** है। इसमें TensorFlow Lite, LiteRT, neural-network model, labels file, remote API या machine-learning inference नहीं है। Camera preview, central depth measurement, point-cloud fallback, threat classification, native tones और haptics सभी device पर locally चलती हैं। यह collision-avoidance system नहीं है; उपयोगकर्ता को अपने surroundings पर ध्यान बनाए रखना आवश्यक है।

## Project structure

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | Google ARCore, Jetpack Compose, coroutines और unit-test dependencies; ML dependencies नहीं |
| `AndroidManifest.xml` | Camera/vibration permissions और optional ARCore declaration |
| `MainActivity.kt` | ARCore availability/install gate, permission flow, `Session` lifecycle, autofocus और automatic depth configuration |
| `ar/ArSafetyRenderer.kt` | OpenGL ES live AR camera feed, `Session.update()` frame loop और pure depth-engine dispatch |
| `safety/ARCoreObstacleEngine.kt` | Confidence-aware 9×9 center sampling, weighted median, temporal smoothing, point-cloud fallback और stable zone events |
| `safety/AlertController.kt` | ToneGenerator-only audio, continuous Zone 4 `VibrationEffect` और lifecycle cleanup |
| `ui/UIOverlayScreen.kt` | Button-free Compose overlay with closest distance, threat color, depth source and FPS |

## Safety policy

| Zone | Distance | UI color | Feedback |
|---|---:|---|---|
| Surakshit | `> 4 m` | Green | One short ToneGenerator clear/success chime after a prior hazard clears |
| Chetaavni | `2.5–4 m` | Yellow | Subtle short notification tone |
| Savdhaan | `1–2.5 m` | Orange | Distinct short ToneGenerator warning beep |
| Turant Ruke | `< 1 m` | Red | Continuous rapid vibration until the path clears or tracking resets |

The engine samples a confidence-aware 9×9 grid across the central field of each available Depth API image. It uses valid-sample coverage, center weighting and a weighted median instead of a single pixel, then applies a seven-frame temporal median with asymmetric smoothing. Closer readings respond faster; receding readings settle more gently. If ARCore has not produced a depth image yet, it evaluates high-confidence point-cloud samples in the same central field. Tone events run only on stable zone changes, meaningful approach or cooldown expiry; safe chimes run once when a prior hazard clears. Depth remains an estimate rather than a collision-avoidance guarantee.

## Run instructions

Open **`native-android`** in Android Studio, use a complete Java 17 JDK, let Android Studio install/sync Android SDK Platform 35, and run on an ARCore-capable Android device. Grant camera permission and accept Google Play Services for AR installation/update when prompted. Depth estimates improve while the user moves the phone; ARCore reports its most reliable depth around 0.5–5 metres in typical scenes.[1]

Run these checks before device testing:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:checkDebugDuplicateClasses
```

## References

[1] [ARCore Depth API overview](https://developers.google.com/ar/develop/java/depth/overview)
