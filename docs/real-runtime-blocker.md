# Real Perception Runtime Blocker

## Required stop condition

**MODEL ARTIFACT MISSING**

The repository was audited before implementation. No `.litertlm`, `.tflite`, `.onnx`, `.pt`, `.task`, or label-map artifact exists under `native-android`. The Gradle file also has no LiteRT-LM Android/Kotlin runtime dependency. Per the supplied instructions, implementation must stop here rather than creating fake inference.

## Actual audit values

| Measurement | Real result |
|---|---|
| VLM model loaded | NO |
| VLM actual inference | NO |
| VLM latency | N/A — no model/runtime executed |
| YOLO model loaded | NO |
| YOLO actual inference | NO |
| YOLO latency | N/A — no model asset/runtime path executed |
| Camera → VLM source connection | YES in source: ARCore renderer passes selected frame to `SmolVlmNavigationPipeline.submitArCoreFrame()`; physical execution not verified |
| Camera → YOLO connection | NO; detector is not instantiated by `MainActivity` |
| Physical-device verification | NOT RUN; `adb devices` has no connected device |

## Exact required artifacts

### SmolVLM2

The project needs the official `SmolVLM2-500M.litertlm` model bundle and a compatible LiteRT-LM Android/Kotlin runtime integration. The production class `ArtifactGatedLocalVlmRuntime` intentionally reports the model as unavailable until those artifacts are present. It does not fabricate a response.

### YOLO

The project needs a trained YOLO `.tflite` model and its label map, for example:

```text
native-android/app/src/main/assets/models/<trained-detector>.tflite
native-android/app/src/main/assets/models/<trained-detector>-labels.txt
```

The exact filenames, tensor contract, quantization, class IDs, preprocessing, and output decoder must come from the trained model provider. They cannot be inferred safely from the generic `YoloTflite` wrapper.

## Why no code was changed in this stop pass

The user specification explicitly forbids fake VLM/YOLO inference and requires reporting `MODEL ARTIFACT MISSING` when the actual artifact is absent. Adding a mock, placeholder model, arbitrary download, or unverified tensor contract would violate that requirement and make runtime claims unreliable.
