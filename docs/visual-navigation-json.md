# Trinetra Compact VLM Safety JSON

The VLM is a **secondary cognition layer**. ARCore depth, temporal filtering, the Safety Engine, and future detector evidence remain responsible for immediate collision and path safety. The VLM must not override emergency decisions or invent metric distance.

## Production schema

The local VLM must return exactly one JSON object with exactly these six fields:

```json
{
  "path_status": "clear|partially_blocked|blocked|uncertain",
  "hazard": "none|obstacle|pothole|stairs|vehicle|person|wall|unknown",
  "position": "left|center|right|unknown",
  "description": "short safety-relevant description",
  "confidence": 0.0,
  "uncertainty": "low|medium|high"
}
```

The parser rejects malformed JSON, missing fields, extra fields, invalid enums, invalid confidence values, empty descriptions, and descriptions longer than two sentences. All rejected responses return a conservative safe fallback and are logged by the developer diagnostics path.

## Responsibility split

| Signal | Authority |
|---|---|
| Metric distance | ARCore depth/point cloud |
| Immediate collision risk | Safety Engine + temporal depth filter |
| Object category | Detector when a valid trained model is available |
| Ambiguous or unusual scene context | Local VLM |
| Spoken contextual output | Validated `description` only |
| Emergency warning | `AlertController`, independent of VLM |

## Runtime status

`VisionLanguageModel` provides the lifecycle boundary. Production code uses `ArtifactGatedLocalVlmRuntime`, which deliberately returns `DISABLED` until `SmolVLM2-500M.litertlm` and the LiteRT-LM Android runtime are packaged. It never uses a mock as production inference and never claims that the model is loaded when it is not.

The current ARCore renderer remains the single camera owner. On a trigger, it passes the current ARCore camera image through the shared frame adapter to `SmolVlmCameraAnalyzer`; no second CameraX binding is created.

## Examples

### Clear path

```json
{
  "path_status": "clear",
  "hazard": "none",
  "position": "unknown",
  "description": "The immediate walking path is clear.",
  "confidence": 0.91,
  "uncertainty": "low"
}
```

### Blocked path

```json
{
  "path_status": "blocked",
  "hazard": "pothole",
  "position": "center",
  "description": "A pothole blocks the walking path. Stop.",
  "confidence": 0.88,
  "uncertainty": "low"
}
```

### Low light

```json
{
  "path_status": "uncertain",
  "hazard": "unknown",
  "position": "unknown",
  "description": "The scene is too dark to confirm a safe path. Stop and wait.",
  "confidence": 0.25,
  "uncertainty": "high"
}
```

## Verification

Parser tests cover valid responses, malformed JSON, missing fields, extra fields, invalid confidence, invalid position, low-light uncertainty, and TTS extraction. Mock responses exist only in the test source path and are not used as production model inference.
