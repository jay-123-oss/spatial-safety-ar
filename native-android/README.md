# Spatial Safety AR — Native Android module

यह module Android Studio में `native-android` directory खोलकर चलाया जा सकता है। यह ARCore और TensorFlow Lite पर आधारित एक **safety-assist prototype** है, न कि collision-avoidance system। Camera permission और Google Play Services for AR दोनों आवश्यक हैं।

## Included implementation

| Component | Responsibility |
|---|---|
| `MainActivity` | ARCore installation check, runtime camera permission, session lifecycle and Compose host |
| `ArSafetyRenderer` | `GLSurfaceView` camera feed, AR frame loop, detector dispatch and output mapping |
| `ObjectDetectorHelper` | Bundled TFLite model loading, YUV conversion, serial background inference and delegate fallback |
| `SpatialFusionEngine` | Depth sampling, four Hindi threat zones and anti-spam trigger policy |
| `AlertController` | On-device Android TTS and priority haptic patterns |
| `UIOverlayScreen` | Compose bounding boxes, highest-risk radar and live device metrics |

## Model contract

The bundled `ssd_mobilenet_v1.tflite` is an SSD MobileNet-style detector with the conventional four outputs: boxes `[1,N,4]` in `ymin,xmin,ymax,xmax` order; classes `[1,N]`; confidence scores `[1,N]`; and detection count `[1]`. The helper supports unsigned, signed or float RGB input tensors. A different model may be substituted if it maintains this output contract; YOLO exports require a dedicated output decoder before replacement.

## Run instructions

Open this directory in Android Studio, allow Gradle synchronization, connect a supported ARCore Android device, and select **Run**. Google Play Services for AR may ask to update or install on first use. The app configures `Config.FocusMode.AUTO` and requests `Config.DepthMode.AUTOMATIC` only when the current device reports support. ARCore Depth estimates are generally strongest when the device is moving and the target lies roughly 0.5–5 metres away; the UI therefore presents an estimate and does not promise autonomous protection.[1]

The `GPU` metric intentionally reports `N/A` unless a device-specific profiler is added: Android exposes no reliable, portable public API for percentage GPU load. CPU is process-time based, while FPS is rendered-frame based.

### Inference runtime dependency policy

The module deliberately keeps only `org.tensorflow:tensorflow-lite:2.17.0`. That artifact bridges to LiteRT 1.0.1; adding `tensorflow-lite-support:0.4.4` also pulls legacy `tensorflow-lite-api:2.13.0`, which collides with LiteRT's compatibility classes at build time. The detector uses the Interpreter API directly and does not require the Support Library or GPU delegate. NNAPI remains the selected acceleration path with a safe CPU fallback.

## Validation status

The checked-in Gradle wrapper executed successfully with Gradle 8.7. The project has been compiled and its `:app:testDebugUnitTest` task completed successfully against Android SDK Platform 35 using Java 17. Source-integrity checks also confirmed the expected TFLite flatbuffer marker (`TFL3`), the presence of all Kotlin sources, and the absence of unexpanded template markers.

Use a complete Java 17 development kit for Android Studio/Gradle; the Android Gradle Plugin needs the JDK's `jlink` executable while compiling with API 35. Before device testing, run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

## References

[1] [ARCore Depth API overview](https://developers.google.com/ar/develop/java/depth/overview)
