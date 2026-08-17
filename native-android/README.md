# Spatial Safety AR — Pure ARCore Android module

यह native Android Studio module एक **offline, ARCore-only safety prototype** है। इसमें TensorFlow Lite, LiteRT, neural-network model, labels file, remote API या machine-learning inference नहीं है। Camera preview, central depth measurement, point-cloud fallback, threat classification, text-to-speech और haptics सभी device पर locally चलती हैं। यह collision-avoidance system नहीं है; उपयोगकर्ता को अपने surroundings पर ध्यान बनाए रखना आवश्यक है।

## Project structure

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | Google ARCore, Jetpack Compose, coroutines और unit-test dependencies; ML dependencies नहीं |
| `AndroidManifest.xml` | Camera/vibration permissions और optional ARCore declaration |
| `MainActivity.kt` | ARCore availability/install gate, permission flow, `Session` lifecycle, autofocus और automatic depth configuration |
| `ar/ArSafetyRenderer.kt` | OpenGL ES live AR camera feed, `Session.update()` frame loop और pure depth-engine dispatch |
| `safety/ARCoreObstacleEngine.kt` | Per-frame center-region Depth API sampling, point-cloud fallback, zone-transition events और cooldown logic |
| `safety/AlertController.kt` | On-device tones, continuous Zone 4 `VibrationEffect`, TTS cooldown और lifecycle cleanup |
| `ui/UIOverlayScreen.kt` | Button-free Compose overlay with closest distance, threat color, depth source and FPS |

## Safety policy

| Zone | Distance | UI color | Feedback |
|---|---:|---|---|
| Surakshit | `> 4 m` | Green | One short clear/success chime after a prior hazard clears |
| Chetaavni | `2.5–4 m` | Yellow | Subtle short notification tone |
| Savdhaan | `1–2.5 m` | Orange | Distinct short warning tone plus cooldown-limited local voice alert |
| Turant Ruke | `< 1 m` | Red | Continuous rapid vibration plus cooldown-limited urgent local voice alert |

The engine samples the central 24% of each available Depth API image without machine-learning inference. It selects the nearest valid depth sample, because the closest object along the user’s forward path is the relevant safety signal. If ARCore has not produced a depth image yet, it evaluates high-confidence point-cloud samples in the same central field. Tone and speech events run only on zone changes, meaningful approach, or cooldown expiry; safe chimes run once when a prior hazard clears. Depth remains an estimate rather than a collision-avoidance guarantee.

## Run instructions

Open **`native-android`** in Android Studio, use a complete Java 17 JDK, let Android Studio install/sync Android SDK Platform 35, and run on an ARCore-capable Android device. Grant camera permission and accept Google Play Services for AR installation/update when prompted. Depth estimates improve while the user moves the phone; ARCore reports its most reliable depth around 0.5–5 metres in typical scenes.[1]

Run these checks before device testing:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:checkDebugDuplicateClasses
```

## References

[1] [ARCore Depth API overview](https://developers.google.com/ar/develop/java/depth/overview)
