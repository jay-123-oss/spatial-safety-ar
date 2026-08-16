# Spatial Safety AR — Mobile Interface Design

## Product intent

यह Android-only spatial-safety prototype उपयोगकर्ता के आसपास मौजूद वस्तुओं को ARCore depth और on-device object detection से पहचानता है, दूरी मापता है, तथा उच्चतम जोखिम को दृश्य, ध्वनि और haptic feedback से बताता है। डिज़ाइन portrait 9:16, एक हाथ के उपयोग और तेज़ glanceability के लिए तैयार किया गया है। यह एक safety-assist prototype है; यह किसी व्यक्ति की situational awareness का विकल्प नहीं है।

## Color choices

| भूमिका | रंग | उपयोग |
|---|---:|---|
| Base canvas | `#071019` | Camera preview के ऊपर dark, high-contrast overlay |
| Safe / Surakshit | `#36D399` | 4 मीटर से दूर का status और outline |
| Warning / Chetaavni | `#FACC15` | 2.5–4 मीटर distance alert |
| Caution / Savdhaan | `#FB923C` | 1–2.5 मीटर proximity alert |
| Emergency / Turant Ruke | `#F43F5E` | 1 मीटर से कम दूरी का stop state |
| Primary text | `#F8FAFC` | Dark preview पर accessibility-oriented labels |
| Secondary text | `#94A3B8` | System metrics और contextual text |

## Screen list and layout

| Screen | Primary content | Main actions | Layout details |
|---|---|---|---|
| Permission and capability gate | Camera, ARCore availability, depth-support state | Grant camera permission; retry capability test | Centered card with concise Hindi copy and a 48 dp primary action |
| Live Safety View | Full-screen camera/AR render, object boxes, distance labels, threat radar, performance strip | Toggle voice feedback; pause/resume scanning; open settings | Edge-to-edge preview; status radar at top; large thumb-reachable controls at bottom |
| Settings | Voice feedback, haptic feedback, detector confidence threshold, developer metrics switch | Change local preferences | Standard Compose list with 48 dp rows and safe-area bottom padding |
| Diagnostics (optional developer screen) | ARCore tracking state, Depth API state, model loading state, rolling inference time | Copy diagnostic snapshot | Scrollable monospaced status content, not shown during normal safety use |

## Primary interaction model

The Live Safety View prioritizes the highest threat currently observed. Each detected item receives a color-coded rectangular outline and a label such as `Car · 1.2 m`. The radar/status bar shows only the most critical active zone to prevent cognitive overload. When the state changes to a more severe zone, it triggers a short, rate-limited speech and haptic alert. Repeated alerts for the same obstacle are suppressed unless it moves materially closer or the cooldown expires.

## Key user flows

| Flow | Steps |
|---|---|
| First use | Launch → check ARCore support → request camera permission → verify depth support → load bundled TFLite model → enter Live Safety View |
| Hazard recognition | AR frame arrives → detector runs off the UI thread → central depth sample is converted to metres → SpatialFusionEngine assigns zone → overlay, radar, TTS and haptics update |
| Temporary interruption | User taps pause → AR session pauses and UI displays “Scanning paused” → user taps resume → AR session resumes without rebuilding controls |
| Unsupported device | Capability gate detects no ARCore/Depth support → provides an explicit explanation and retry control; scanning never starts in a partially initialized state |

## Accessibility and safety decisions

All critical states combine color with written Hindi labels and spoken feedback. Overlay labels use high-contrast text with a dark background chip. Important controls retain at least a 48 dp touch target. The UI deliberately avoids claims of collision avoidance or autonomous control; it reports estimated measurements and requires the user to remain attentive.
