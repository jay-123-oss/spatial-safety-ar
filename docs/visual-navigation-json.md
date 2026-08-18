# Trinetra Offline VLM Contract

The VLM is a **secondary cognition layer**. The runtime architecture remains CameraX → YOLO11n/tracking → ARCore/Depth → Safety Engine → `VlmRouter` → local SmolVLM2-500M only when an event trigger fires. Immediate collision, braking, emergency, and dangerous-navigation decisions remain in the Safety Engine.

## Local model abstraction

`VisionLanguageModel` defines initialization, readiness/state, image-plus-context analysis, and release. `SmolVlmEngine` implements the lifecycle and is replaceable by future Moondream, PaliGemma, or Qwen engines. The current build contains an explicit `MissingLocalModelRuntime` until the SmolVLM2-500M Android runtime and model artifact are supplied; in that state the app remains functional through YOLO, ARCore/Depth, and the safety fallback.

## Event-driven routing

`VlmTriggerManager` supports configurable low-confidence, unknown/unstable detection, scene-change, complex-scene, and user-query triggers. `VlmRouter` enforces safety-critical bypass, cooldown, single-flight processing, and no unbounded queue. Normal scenes keep VLM off.

## Structured output

The local model must return exactly this JSON shape:

```json
{
  "scene": "outdoor walking path",
  "important_objects": [
    {
      "name": "motorcycle",
      "confidence": 0.86,
      "position": "front-right",
      "relation": "partially blocking path"
    }
  ],
  "unknown_objects": [],
  "path_status": "partially_blocked",
  "scene_change": false,
  "description": "A motorcycle is partially blocking the walking path.",
  "uncertainty": "low"
}
```

The parser rejects malformed JSON, missing or extra fields, invalid positions, invalid confidence values, invalid path status, and overlong descriptions. It returns a conservative fallback rather than crashing. TTS speaks only the validated concise `description`; safety warnings from the Safety Engine retain higher priority.

## Offline guarantee

The Android manifest does not request `INTERNET`, and the active runtime does not upload camera frames. No cloud endpoint or API key is used. `native-android/local.properties.example` documents only local SDK/model setup. The debug log reports explicit VLM lifecycle state and keeps the YOLO/ARCore safety path active if the local model is unavailable.

## Verification

Unit coverage includes normal-scene VLM-off routing, low-confidence and user-query triggers, safety-critical bypass, cooldown/single-flight behavior, strict schema parsing, malformed and missing fields, low-light uncertainty, sudden obstacles, and local depth fallback behavior.
