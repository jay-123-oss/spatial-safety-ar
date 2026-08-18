# Trinetra Visual Navigation JSON Contract

The visual cognition layer must return **only one JSON object**. The mobile client parses the response with `VisualNavigationPrompt.parse`; malformed, incomplete, or extra-key responses resolve to a conservative safe fallback.

## Required schema

| Field | Allowed value |
|---|---|
| `environment` | A short physical scene description. |
| `path_status` | `CLEAR`, `BLOCKED`, `PARTIALLY_BLOCKED`, or `HAZARDOUS`. |
| `hazards_detected` | An array of objects containing only `object` and `position`. |
| `text_signs` | Readable sign text, or `None`. |
| `actionable_guidance` | A precise, TTS-ready physical movement instruction. |

Positions must be egocentric and practical, such as `directly ahead`, `on your immediate left`, or `about 2 meters away`. Do not use compass directions or image coordinates. Every hazard must be paired with a bypass maneuver; a generic warning is not sufficient.

## Few-shot examples

### Static obstacle

```json
{
  "environment": "Narrow alleyway",
  "path_status": "PARTIALLY_BLOCKED",
  "hazards_detected": [{
    "object": "Parked scooter",
    "position": "1 step ahead on the left side"
  }],
  "text_signs": "None",
  "actionable_guidance": "There is a parked scooter on your left. Shift one step to your right and walk straight to bypass it."
}
```

### Floor hazard

```json
{
  "environment": "Outdoor street",
  "path_status": "HAZARDOUS",
  "hazards_detected": [{
    "object": "Open manhole",
    "position": "Directly ahead, about 2 steps away"
  }],
  "text_signs": "None",
  "actionable_guidance": "Stop immediately. There is an open manhole directly in front of you. Tap your cane to the right to find a safe path around it."
}
```

### Clear path

```json
{
  "environment": "Indoor hallway",
  "path_status": "CLEAR",
  "hazards_detected": [],
  "text_signs": "Room 204",
  "actionable_guidance": "The hallway is clear. Continue walking straight. Room 204 is coming up."
}
```

The complete model instruction is centralized in `VisualNavigationPrompt.SYSTEM_PROMPT` so an image-model adapter can reuse the same contract without duplicating prompt text.
