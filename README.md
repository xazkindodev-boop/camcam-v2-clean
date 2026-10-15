# xCam - Universal Virtual Camera Xposed Module

**xCam** is an Xposed module built with **LibXposed API 101**. It provides virtual-media preview and several camera capture interception paths. Compatibility depends on the Android version, device camera implementation, target application's camera stack, and Xposed framework; the project does not guarantee support for every application or device.

## 🚀 Key Features

- **Android API target range (API 28 - 37)**: The project builds with a hybrid set of legacy and modern camera hooks. The range describes build targets, not verified support on every device.
- **Supports**:
    - **Legacy (API 28-30)**: Includes Camera1 hooks and a hook for the legacy Camera2 `produceFrame` implementation.
    - **Modern (API 31-37)**: Includes Camera2 surface handling, ImageReader processing, and other capture hooks. Exact coverage varies by app and OEM.
- **Capture interception**: Several image paths are instrumented. CameraX `takePicture()` is currently observed for logging; it is not itself a replacement implementation.

## 📊 Feature Availability

| Capture path | Status |
|:--- |:--- |
| **Virtual-media preview** | Implemented; device/app compatibility must be validated |
| **Still-photo replacement** | Partial and app-dependent |
| **CameraX `takePicture()` hook** | Logging only; does not replace the result |
| **MediaRecorder output remux** | Post-processing is implemented; test the output on target devices |

> [!TIP]
> A successful build or unit-test run does not validate camera behavior on a device. Test with the target application and keep an original recording until the result is confirmed.

## 📦 Installation

### Rooted Environments
1. Install the **xCam APK**.
2. Enable the module in your Xposed manager (e.g., **LSPosed**, **Vector**).
3. Select the **target applications** in the module scope.
4. Force stop and restart the selected applications.

### Non-Rooted Environments
1. Patch your target application using a tool like **LSPatch**.
2. Install the patched application and the xCam manager.

## ⚖️ License

This project is licensed under the **GNU General Public License v3.0**.

```text
Copyright (C) 2026 zkincamcam
```

## ⚠️ Disclaimer

This module is intended for **educational and development purposes only**. Use it responsibly and at your own risk. The developer is not responsible for any misuse or breaches of third-party service terms.
