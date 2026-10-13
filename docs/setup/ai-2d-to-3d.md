# AI 2D-to-3D depth

PlayStation Remote Play sends Horizon Stream a single 2D video image. When **AI 2D-to-3D Depth** is enabled in immersive mode, Horizon Stream estimates the relative depth of that image and uses it to make slightly different views for the left and right eyes. This is simulated depth; it is not native stereoscopic video and it does not reconstruct a complete 3D scene.

## How it works

1. The OpenXR video compositor captures a reduced copy of the incoming frame at 322 × 182 pixels. It passes that frame to the depth worker without waiting for inference, so the stream can continue while depth is being calculated.
2. The worker prepares the RGB input and runs the bundled Depth Anything V2 Small model. The model returns one relative-depth value per pixel. The app normalizes and lightly smooths that map over time, then sends it back to the compositor.
3. An OpenGL ES fragment shader samples the depth map and shifts the video’s horizontal sampling coordinate in opposite directions for each eye. The chosen strength controls the amount of separation.

**Depth Shift** applies the depth-based offset directly. **Depth Warp** starts with the same offset and performs four damped coordinate-refinement steps to remap the source image for each eye. Both options use the same model, depth map, and strength presets; Depth Warp is an alternate reprojection method, not another depth-estimation library.

## Model and runtime

| Component | Use in Horizon Stream | License / source |
| --- | --- | --- |
| Depth Anything V2 Small, quantized Q4 ONNX | Estimates relative depth from each reduced video frame. The bundled file is `android/app/src/main/assets/models/depth_anything_v2_small_q4.onnx`. | Apache-2.0. The packaged file matches the Q4 ONNX artifact in [ONNX Community’s model repository](https://huggingface.co/onnx-community/depth-anything-v2-small/tree/main/onnx); its SHA-256 is `5d55b02762e1907589158af3e366bd61ddf648155852a07bbf5e3a074639fcf8`. The original [Depth Anything V2 project](https://github.com/DepthAnything/Depth-Anything-V2) identifies the Small model as Apache-2.0. |
| ONNX Runtime Android 1.28.0 | Executes the model on the headset. Horizon Stream first requests Android NNAPI with FP16; if that provider cannot initialize the model, it falls back to optimized CPU inference. | MIT. See [Microsoft ONNX Runtime](https://github.com/microsoft/onnxruntime). |
| Kotlin preprocessing and OpenGL ES reprojection | Copies and prepares frames, normalizes and smooths estimated depth, and renders the eye-specific image offsets. | Horizon Stream source, covered by the repository’s AGPLv3 license. |

The ONNX model is packaged with the app and copied to app-private storage when needed. Inference runs on the headset; this depth-processing feature does not upload game frames to a depth service. The regular Remote Play connection to the PlayStation still uses the network as usual.

## Performance and image quality

Depth inference runs asynchronously and may update less often than the video stream. Fast camera movement, cuts, HUD elements, particles, transparency, or scenes with weak depth cues can therefore make the simulated depth lag or look unstable. Stronger depth settings increase eye separation and may make artifacts more noticeable. If the image is uncomfortable or garbled, reduce **AI Depth Strength**, try the other reprojection method, or turn AI depth off. The flat stream remains available.

The exact Android dependency version is declared in `android/app/build.gradle`; model preprocessing and the OpenGL ES shader are implemented in `DepthAnythingV2Bridge.kt` and `openxr_manager.cpp`.
