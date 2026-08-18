# Trinetra SmolVLM2 Navigation Contract

The hybrid pipeline sends selected CameraX frames to a configured SmolVLM2-compatible gateway when deterministic AR/YOLO state is unknown or high-risk. The model response must contain **exactly five JSON fields**. The mobile parser rejects malformed, incomplete, extra-field, overlong, or invalid responses and returns a conservative fallback.

| Field | Requirement |
|---|---|
| `environment` | Scene description of at most three words. |
| `primary_hazard` | Most dangerous object, or `None`. |
| `hazard_position` | Relative position using steps, meters, or clock-face direction. |
| `spatial_reasoning` | Concise internal spatial explanation; never spoken to the user. |
| `action_command` | Polite TTS-ready command with at most two sentences. This is the only spoken field. |

The Android implementation is split into `SmolVlmCameraAnalyzer` for latest-frame CameraX capture and JPEG encoding, `OpenAiCompatibleSmolVlmClient` for the multimodal request, `VisualNavigationPrompt.parse` for strict validation, and `NavigationTtsController` for speaking only `action_command`.

Configure `SMOLVLM_ENDPOINT` and, when required, `SMOLVLM_API_KEY` as Android build fields. The defaults are empty, which keeps the network pipeline disabled until a real gateway is configured.
