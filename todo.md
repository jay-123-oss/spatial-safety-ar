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
- [ ] Create a private GitHub repository and push the completed source code.

- [x] Regenerate a lower-size launcher icon and replace the oversized branding assets.
- [ ] Re-run checkpoint save after the optimized icon replacement.
- [ ] Push the checkpointed project to a private GitHub repository.

## Change history

- [ ] User requested a smaller regenerated icon because checkpoint validation rejected the previous 2.5 MB PNG files.
