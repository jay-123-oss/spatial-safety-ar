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
- [x] Push the verified ARCore-only refactor to the private repository's main branch.

- [x] Implement continuous rapid emergency vibration for Zone 4 with safe lifecycle cancellation.
- [x] Add subtle warning tones for Zones 2–3 and a distinct clear/success tone for Zone 1.
- [x] Add feedback state transition and cooldown logic to prevent tone spam.
- [x] Simplify the Compose safety overlay to camera, threat status, distance, and performance only.
- [x] Verify no scene-description or object-search controls or logic remain in the pure ARCore module.
- [x] Build and test the refined feedback and UI implementation.
- [x] Push the verified feedback and UI refinement to the private repository's main branch.

- [x] Remove every remaining Text-to-Speech/TTS reference from the native Android module.
- [x] Refine ToneGenerator-only clear and obstacle feedback without voice output.
- [x] Implement confidence-aware center-weighted multi-point depth sampling with robust filtering.
- [x] Add temporal smoothing and stable zone transitions to reduce depth noise and false positives.
- [x] Verify continuous emergency vibration starts and cancels immediately on clear/turn-away states.
- [x] Update UI and tests for distance stability, tone-only feedback, and no TTS behavior.
- [x] Build, package, and validate the production-grade offline update.
- [x] Push the verified update to the private repository's main branch.
