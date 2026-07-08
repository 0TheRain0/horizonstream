# Research: Meta Quest Touch Controllers as Gamepads in Flat Android Apps

This document outlines the design, mapping logic, and technical hurdles encountered while attempting to support standalone Meta Quest Touch controllers as gamepads inside Horizon Stream (a flat 2D Android app).

---

## 1. The Goal
Directly map the Meta Quest Touch (Left and Right) controllers to emulate a dual-stick PlayStation controller during a remote play session. 
- Left Stick -> Character movement.
- Right Stick -> Camera/look.
- X/Y/A/B -> PlayStation Square/Triangle/Cross/Circle.
- Grips/Triggers -> L1/R1 and L2/R2.
- Toggleable sub-option to use Left Stick for D-pad navigation when Left Grip (L1) is held.

---

## 2. Input Handling Implementation Design

### A. Device Detection & Mode Split
Meta Quest Touch controllers can register either as two separate independent devices (Split mode) or a single unified gamepad virtual device (Combined mode). The distinction was handled dynamically by checking if the input device supported the `AXIS_Z` motion range (indicative of a right thumbstick):

```kotlin
private fun isQuestController(deviceId: Int): Boolean {
    val device = InputDevice.getDevice(deviceId) ?: return false
    val name = device.name ?: ""
    return device.vendorId == 0x2833 || 
           name.contains("Oculus", ignoreCase = true) || 
           name.contains("Meta", ignoreCase = true) || 
           name.contains("Quest", ignoreCase = true)
}

private fun isCombinedQuestController(deviceId: Int): Boolean {
    val device = InputDevice.getDevice(deviceId) ?: return false
    return device.getMotionRange(MotionEvent.AXIS_Z) != null
}

private fun isLeftQuestController(deviceId: Int): Boolean {
    val name = InputDevice.getDevice(deviceId)?.name ?: return false
    return name.contains("Left", ignoreCase = true) || name.contains("-L", ignoreCase = true)
}
```

### B. Action Button Mappings
Physical face buttons were mapped to match standard controller layout ergonomics:
- **A** (Right controller, bottom) -> **PS Cross**
- **B** (Right controller, top) -> **PS Circle**
- **X** (Left controller, bottom) -> **PS Square**
- **Y** (Left controller, top) -> **PS Triangle**
- **L1 / R1** (Left/Right Grips) -> **L1 / R1**
- **L2 / R2** (Left/Right Index Triggers) -> **L2 / R2** (analog axis or fallback digital KeyEvents)
- **L3 / R3** (Stick Clicks) -> **L3 / R3**

---

## 3. The Technical Blockers (Why it didn't work)

Sideloaded flat (2D) Android applications running inside Meta Horizon OS (Quest 3 / Quest Pro) encounter OS-level restrictions regarding system controllers:

1. **System Pointer Capture**: By default, Horizon OS intercepts Quest Touch controller inputs to draw pointer lines and trigger mouse events (touch-clicks) on the 2D Android panel. 
2. **Missing Gamepad Forwarding**: Because the OS handles controllers as spatial mouse/pointer devices, it does not package and forward standard `KeyEvent` or `MotionEvent` triggers (such as `AXIS_X` or `KEYCODE_BUTTON_A`) to the active window focus. 
3. **Bluetooth Gamepad Difference**: Physical Bluetooth controllers (e.g. Xbox, PS5 controllers) paired directly to the headset bypass the pointer capture and work normally because they register as standard Bluetooth HID devices. Touch controllers communicate over Meta's private, low-latency protocol and do not register as Bluetooth HID.

---

## 4. Alternate Solutions for Revisit

If we want to revisit Touch controller support in the future, we have two primary implementation paths:

### A. Meta Horizon OS Spatial SDK (`com.meta.spatial`)
Meta provides a Spatial SDK targeting native Android/Kotlin developers. Integrating this allows native flat applications to request specific 3D spatial properties and tap directly into Meta's VR runtime inputs.
- **Gradle Dependency**:
  ```groovy
  implementation("com.meta.spatial:meta-spatial-sdk:0.13.1")
  implementation("com.meta.spatial:meta-spatial-sdk-compose:0.13.1")
  ```
- **Configuration**: The app would need to initialize the Spatial SDK and configure the application activity framework to use `VrInputSystemType.SIMPLE_CONTROLLER` or query spatial controller state arrays directly.

### B. Native OpenXR / OVR Input Integration
Because `horizonstream` compiles C++ code via CMake (forked from `chiaki-ng`), it is possible to load the native Meta OpenXR Loader library (`libopenxr_loader.so`) and query controller states directly from the Quest native C++ runtime.
- **Reference**: Similar to how VR-specific emulators (e.g., PPSSPP VR or Citra VR) implement native C++ input hooks to poll Oculus controller states independently of Android's window callbacks.
