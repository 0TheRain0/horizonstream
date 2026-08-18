# Horizon Stream on Meta Quest

This guide covers Horizon Stream's Quest-specific registration and streaming features. Horizon Stream is a Quest-focused Remote Play client built on the Chiaki/chiaki-ng implementation.

!!! Warning "Sony compatibility and experimental features"

    Horizon Stream is not endorsed by Sony. The redirect-QR Account-ID process uses a compatibility workflow that Sony may change or stop accepting. Immersive VR Mode, Quest Touch controller emulation, and AI 2D-to-3D depth are experimental and may increase latency, battery use, or the chance of visual/input issues.

## Before you begin

- Use a supported Meta Quest headset and update Horizon OS before testing immersive features.
- Put the Quest and PlayStation on the same local network for initial registration and best streaming quality.
- On the console, enable Remote Play.
- For the recommended Account-ID transfer, install the [Horizon Stream PSN Authenticator from the Chrome Web Store](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe). The [extension README](../../chrome-extension/README.md) explains the side-panel workflow and local-development loading option.
- Keep a Bluetooth gamepad available if you need the most reliable controller support.

## Register a console

From the Horizon Stream connections page, select **Add Console Manually** (or select a discovered unregistered console). The form intentionally asks for the Account ID before the short-lived console code.

### 1. Enter a name and console IP address

Give the console a recognizable name and enter its local IP address. A discovered console can prefill the address.

### 2. Get the PSN Account ID

The recommended method is the **sign-in QR code**. It transfers a one-time redirect URL from Chrome to the headset; the extension does not exchange it with Sony.

1. On a computer, open the [Horizon Stream PSN Authenticator](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe) and select its toolbar icon to open its side panel.
2. In the side panel, select **Open PS Remote Play sign-in** and complete Sony's normal sign-in in Chrome.
3. Keep the side panel open. When the sign-in tab reaches its final redirect, it captures the redirect and replaces the instructions with a QR code and copyable URL.
4. In Horizon Stream, select **Scan sign-in QR code**. Allow passthrough-camera access when prompted and look at the QR code.
5. Horizon Stream validates the redirect and retrieves the Base64 Account ID locally.

!!! Warning "Treat the QR code as sensitive"

    The QR code contains the complete one-time redirect URL. Scan it immediately, do not share it, and create a new one if it is expired or already used.

#### Other Account-ID methods

The same screen also accepts either of these alternatives:

- Paste a complete `https://remoteplay.dl.playstation.net/remoteplay/redirect?...` URL and select **Use redirect URL**.
- Paste an 8-byte Base64 **PSN Account ID** obtained through another method.

Do not enter a PSN password, console PIN, or a two-factor code in the Account ID field.

### 3. Get the fresh console Link Device code

Only after the Account ID is ready, retrieve the temporary registration code from the console:

- **PS5:** `Settings → System → Remote Play → Link Device`
- **PS4:** `Settings → Remote Play Connection Settings → Add Device`

Enter the eight-digit code promptly; it expires quickly. It is not your PSN password or sign-in PIN.

### 4. Select the console version and register

Select the appropriate console type, then choose **Register & Save**. A pre-7.0 PS4 uses a PSN Online ID instead of a Base64 Account ID.

## Immersive VR Mode (experimental)

Enable **Settings → Quest & VR Hardware → Immersive VR Mode (Experimental)** to present a stream on a head-tracked spatial screen. The immersive path also renders connection errors and console PIN prompts as readable in-headset overlays.

Disable it to return to the regular flat streaming view. Start a new connection after changing spatial rendering features so the stream is created with the intended renderer.

## Quest Touch controller emulation (experimental)

Enable **Quest Controller Gamepad Emulation** under **Settings → Quest & VR Hardware**. It automatically enables Immersive VR Mode if needed. During an immersive stream, Horizon Stream maps Quest Touch inputs to a virtual PlayStation controller.

| Quest input | PlayStation input |
|---|---|
| Left stick | Left stick |
| Right stick | Right stick / camera |
| A / B | Cross / Circle |
| X / Y | Square / Triangle |
| Left / right trigger | L2 / R2 |
| Right grip | R1 |
| Left grip | L1, unless used with the left stick |
| Left grip + left stick | D-pad |
| Stick clicks | L3 / R3 |

### Quest Menu gestures

Quest Menu is reserved by Horizon Stream and cannot be used as a learnable button assignment.

| Gesture | Result |
|---|---|
| Tap Menu once | Sends PlayStation Options after a short delay and shows the long-press exit reminder. |
| Double-tap Menu | Sends the PlayStation button, unless using a Menu chord. |
| Long-press Menu | Exits the stream and returns to the connections page. |
| Menu + X | Share |
| Menu + Y | PlayStation button |
| Menu + left-stick click | Touchpad click |

### Learn an exit-stream button

To assign another exit control, open **Settings → Controls & Input → Exit Stream Button**, select it, and press a Quest Touch or Bluetooth-gamepad button. The learned button is consumed by Horizon Stream and exits the active stream rather than being sent to the game. Choose **Clear assignment** to remove it.

## AI 2D-to-3D Depth (experimental)

Enable **Settings → Spatial 3D Rendering → AI 2D-to-3D Depth (Experimental)** to run Depth Anything V2 asynchronously on the incoming 2D game frames. Horizon Stream uses the estimated depth to create separate eye views; it does not receive native stereoscopic video from the PlayStation.

The available **AI Depth Strength** presets are:

| Preset | Intended result |
|---|---|
| Comfort | Subtle depth; recommended starting point. |
| Balanced | Default middle-ground depth. |
| Enhanced | More pronounced depth. |
| Strong | Maximum displacement; most likely to reveal estimation artifacts. |

AI depth estimation can be less convincing for HUDs, fast cuts, transparency, particles, or scenes with limited visual depth cues. If the image looks uncomfortable, unstable, overly separated, or performance drops, lower the strength or switch the feature off.

## Troubleshooting

- **QR scanner does not show the real world:** grant the headset passthrough-camera permission when the scanner asks. If camera access is unavailable, paste the redirect URL or Base64 Account ID instead.
- **Redirect URL is rejected or expired:** create a new Chrome sign-in and scan the new QR code only once.
- **Sony returns an authorization error:** the compatibility sign-in process may have been rejected server-side. Start again with a fresh redirect; Horizon Stream cannot safely bypass a Sony server decision.
- **Quest controls are not working:** confirm both Immersive VR Mode and Quest Controller Gamepad Emulation are enabled, then begin a new stream. Use a Bluetooth controller as a fallback.
- **AI depth is flat or visually wrong:** verify AI 2D-to-3D Depth is on before starting the stream, then try a lower strength or turn it off.
