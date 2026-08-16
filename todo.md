# Project TODO

- [x] Create a native Android Studio Kotlin/Jetpack Compose module for the safety prototype.
- [x] Configure Android Gradle, manifest capabilities, ARCore, TensorFlow Lite, Camera and Compose dependencies.
- [x] Implement the ARCore activity, permission handling, session configuration and camera rendering loop.
- [x] Implement `ObjectDetectorHelper` with asset model loading, background inference, frame conversion and delegate fallback.
- [x] Implement `SpatialFusionEngine` with depth sampling, four threat zones, cooldown and escalation override.
- [x] Implement `AlertController` with offline TTS, Android vibration patterns and lifecycle cleanup.
- [x] Implement a Compose overlay with detection labels, status radar and performance metrics.
- [x] Add a model/labels asset contract and setup documentation for a compatible TFLite model.
- [x] Generate a branded mobile icon and update the managed project branding configuration.
- [x] Run static checks and review the Android project configuration for integration issues.
- [x] Create a private GitHub repository and push the completed source code.

- [x] Regenerate a lower-size launcher icon and replace the oversized branding assets.
- [x] Re-run checkpoint save after the optimized icon replacement.
- [x] Push the checkpointed project to a private GitHub repository.

## Change history

- [x] User requested a smaller regenerated icon because checkpoint validation rejected the previous 2.5 MB PNG files.

- [x] Diagnose the reported LiteRT and TensorFlow Lite duplicate-class build conflict.
- [x] Align inference dependencies to a single runtime and update affected Kotlin imports.
- [x] Run dependency, Gradle, and unit-test validation after the inference-runtime fix.
- [x] Push the verified duplicate-class resolution to the private repository's main branch.

- [x] Diagnose why the installed Android app closes or does not open.
- [x] Fix the native Android launch path and unsupported-device fallback behavior.
- [x] Build and validate the repaired Android app startup flow.
- [x] Push the verified startup repair to the private repository's main branch.

- [x] Audit and explain the actual ARCore implementation present on the main branch.

- [x] Remove all TensorFlow Lite/LiteRT dependencies, assets, source classes, and documentation references.
- [x] Implement `ARCoreObstacleEngine` with center-region depth sampling, four threat zones, cooldown, and priority override.
- [x] Refactor the ARCore renderer and MainActivity to operate without ML frames or object detections.
- [x] Update Compose dashboard for live camera preview, closest obstacle distance, threat state, and FPS.
- [x] Build and test the pure ARCore app with no TensorFlow/LiteRT artifacts in the dependency graph.
- [ ] Push the verified ARCore-only refactor to the private repository's main branch.
