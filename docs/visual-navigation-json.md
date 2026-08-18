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

## Mobile latency profile

The default mobile profile requests **INT4 quantization**, limits generation to 160 tokens, downsizes the longest image edge to 768 pixels, uses JPEG quality 65, requests low image detail, and enforces a 1.2-second minimum frame interval. The `quantization` request field is a gateway hint; the configured SmolVLM2 serving stack must actually load an INT4/INT8/FP16 artifact for that hint to affect model execution.

The offline `MockSmolVlmClient` includes deterministic scenarios for an open gutter, clear path, low-light uncertainty, a sudden approaching cyclist, and malformed model output. These cases exercise conservative movement guidance and cane-specific instructions without network access.
