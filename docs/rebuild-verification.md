# Trinetra Android Rebuild Verification

## Baseline findings

The supplied device log contained two separate issues. The old installed APK referenced `com.manus.spatialsafety.MainActivity`, while the refactored source launcher was `com.manus.spatialsafety.ar.MainActivity`; the APK therefore failed before activity construction. The current manifest uses the refactored launcher and retains a compatibility activity for stale explicit intents. The app version is bumped to `0.2.0` / version code 2 so an older APK is not mistaken for the rebuilt one.

The repository contained no YOLO model asset and no `.litertlm` VLM artifact. Before this rebuild, the active app path was ARCore depth rendering plus an additional CameraX binding created by the VLM coordinator. That second camera owner was removed because ARCore must remain the single camera-session owner.

## Rebuild changes

The runtime now has a single ARCore-owned camera lifecycle, bounded 20 FPS depth analysis, temporal filtering, explicit launcher compatibility, offline local-VLM abstraction, event-driven `VlmRouter`, strict structured output parsing, and fail-safe local-model lifecycle states. Cloud endpoint configuration and the `INTERNET` permission were removed from the active app path. Safety alerts remain separate from contextual VLM TTS.

## Verification commands

The following commands are run with the configured Android SDK and JDK 21:

```text
./gradlew test assembleDebug --no-daemon --max-workers=1
./gradlew lintDebug --no-daemon --max-workers=1
```

Both complete successfully. Unit coverage includes parser edge cases, router triggers/cooldown, mock scenes, depth fallback, ARCore safety filtering, and local model lifecycle.

## Known artifact requirement

The app is **not claiming that SmolVLM2 is currently executing on-device**. The clean local boundary is present, but the `SmolVLM2-500M.litertlm` bundle and LiteRT-LM Android runtime still need to be packaged and connected before local VLM inference can be enabled. Until then, the application intentionally continues through ARCore/Depth and its safety engine rather than crashing or silently uploading frames. The same applies to YOLO: the generic TFLite detector is implemented and tested, but no trained YOLO model/label asset is bundled in this repository, so object-detection accuracy cannot be validated on a real scene yet.
