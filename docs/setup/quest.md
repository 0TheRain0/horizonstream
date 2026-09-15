# Horizon Stream on Meta Quest

This guide covers Horizon Stream's Quest-specific registration and streaming features. Horizon Stream is a Quest-focused Remote Play client built on the Chiaki/chiaki-ng implementation.

!!! Warning "Sony compatibility"

    Horizon Stream is not endorsed by Sony. The redirect-QR Account-ID process uses a compatibility workflow that Sony may change or stop accepting. AI 2D-to-3D depth may increase latency, battery use, or the chance of visual artifacts.

## Before you begin

- Use a supported Meta Quest headset and update Horizon OS before testing immersive features. QR scanning uses raw passthrough camera frames exposed to apps on Quest 3 and Quest 3S. Quest 2 and Quest Pro do not expose those frames, so they use manual pairing.
- Put the Quest and PlayStation on the same local network for initial registration and best streaming quality.
- Turn on the PlayStation and enable Remote Play. The Quest must discover an unregistered console before the immersive setup can start.
- Have a computer with Google Chrome available. Install the [Horizon Stream PSN Authenticator from the Chrome Web Store](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe). The [extension README](../../chrome-extension/README.md) explains the side-panel workflow and local-development loading option.
- On Quest 3/3S, allow **Headset Camera** access when Horizon Stream asks before onboarding. It is used only for the sign-in QR code and console Link Device code.
- Keep a Bluetooth gamepad available if you need the most reliable controller support.

## First-time console setup

The normal Quest flow is designed to keep the setup instructions in view:

1. Open Horizon Stream and wait for the console to appear on the connections page.
2. Select the discovered, unregistered console. If no console appears, refresh discovery and check that the PlayStation is on, Remote Play is enabled, and both devices are on the same network.
3. On Quest 3/3S, approve **Headset Camera** access in the regular Quest view. Horizon Stream then opens the immersive onboarding flow.

The first onboarding page confirms the selected console. Press any button on a Quest controller to advance. You do not need to enter a redirect URL, PSN Online ID, or Account ID manually in this flow.

### Sign in to PlayStation Network on your computer

This step is required the first time Horizon Stream needs a PSN Account ID:

1. On the computer, install and open the [Horizon Stream PSN Authenticator](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe) Chrome extension.
2. Open its side panel and select **Open PS Remote Play sign-in**.
3. Complete the normal PlayStation Network sign-in in the Chrome tab. Keep the extension side panel open.
4. When sign-in reaches the final redirect, the extension displays a one-time QR code.
5. In the headset, press a controller button to begin scanning and hold the QR code inside the on-screen frame. Horizon Stream validates the redirect and retrieves the Account ID locally.

!!! Warning "Treat the QR code as sensitive"

    The QR code contains a complete one-time redirect URL. Scan it immediately, do not share it, and create a new Chrome sign-in if it is expired or already used. Horizon Stream stores the resulting Account ID in the app so the sign-in QR step is skipped on future onboarding attempts.

### Link the PlayStation

After the Account ID is ready, the onboarding screen explains how to get the short-lived console code:

1. On the **PS5**, open `Settings → System → Remote Play`.
2. If needed, turn on **Enable Remote Play**.
3. Select **Link Device** and leave the eight-digit code visible.
4. Press any button on a **Quest controller** when the onboarding screen asks you to scan. This is not a request to press a button on the PS5 controller.
5. Keep the code in the camera frame. Horizon Stream reads it, confirms the code, and then begins pairing.

Keep the console awake and on the same network while pairing. The Link Device code is temporary; if it expires, retry with a fresh code from the PS5.

### Repeat setup

Once the PSN Account ID has been retrieved, Horizon Stream saves it locally. The next time you select an unregistered console, onboarding goes from the welcome screen directly to the PS5 Link Device instructions. You still need a fresh Link Device code for each console registration.

### Pairing complete

After pairing succeeds, Horizon Stream shows the Quest controller mappings and explains how to use a DualSense, DualShock 4, or other Bluetooth controller. Press any Quest controller button to connect to the immersive stream.

The legacy manual registration screen remains available for older consoles and advanced recovery cases. It may ask for a console address and registration values directly.

## Quest 2 and Quest Pro manual pairing

Quest 2 and Quest Pro can use Horizon Stream's regular and immersive streaming views, but they cannot use its QR or Link Device camera scans. Horizon OS does not expose the raw passthrough camera frames needed for those scans to apps. This is expected even if the Camera permission appears enabled.

After you select a discovered console, Horizon Stream opens the regular setup screen rather than immersive scanning. Complete registration as follows:

1. On a computer with Google Chrome, install and open the [Horizon Stream PSN Authenticator](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe), select **Open PS Remote Play sign-in**, and complete PlayStation Network sign-in.
2. When the extension reaches the final redirect, use **Copy URL**. The result is a one-time, sensitive sign-in redirect; do not share it.
3. Back in Horizon Stream, select **QR scanning isn't working**, then **Advanced setup**. Paste the copied result into **PS Remote Play redirect URL**. Horizon Stream retrieves and saves the PSN Account ID. Enter the console address only if discovery did not already provide it.
4. On PS5, open `Settings → System → Remote Play → Link Device`. On PS4, open `Settings → Remote Play Connection Settings → Add Device`. Enter the current eight-digit code in Horizon Stream, then pair and save.

No headset-camera permission is required for this route. If the redirect is expired or already used, repeat Chrome sign-in to generate a new URL.


## Immersive VR Mode

Enable **Settings → Quest & VR Hardware → Immersive VR Mode** to present a stream on a head-tracked spatial screen. The immersive path also renders connection errors and console PIN prompts as readable in-headset overlays.

Press the Quest **Meta** button whenever you want to reorient the screen. Horizon Stream uses your complete look direction, including looking up or down, and keeps the screen level without carrying over head roll. The screen stays fixed in the room until you recenter again.

Disable it to return to the regular flat streaming view. Changes to the immersive screen's size, curve, and depth mode apply from the in-stream settings panel.

## Quest Touch controller support

Enable **Quest Controller Support** under **Settings → Quest & VR Hardware**. It automatically enables Immersive VR Mode if needed. During an immersive stream, Horizon Stream maps Quest Touch inputs to a virtual PlayStation controller.

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
| Tap Menu once | Sends PlayStation Options after a short delay. |
| Double-tap Menu | Sends the PlayStation button, unless using a Menu chord. |
| Long-press Menu | Sends one PlayStation Options press, then opens the in-stream controls. |
| Menu + X | Share |
| Menu + Y | PlayStation button |
| Menu + left-stick click | Touchpad click |

### In-stream controls and pointer mode

Long-press Quest Menu while streaming to open the spatial **Stream Settings** panel. The panel can cycle the screen size, toggle the curved immersive screen, change AI depth options, close the panel, or exit streaming. Aim either Quest controller at a row and squeeze its trigger to choose it. This pointer remains available even when **Quest Controller Support** is disabled, so controllers can still operate the immersive controls without emulating a PlayStation controller.

The curved-screen and size choices take effect immediately and are saved for future immersive connections. A paired Bluetooth controller can use its D-pad or left stick to move through the panel and its primary button to select a row. Its Options/Menu button follows the same single-, double-, and long-press behavior as Quest Menu.

## AI 2D-to-3D Depth

Enable **Settings → Spatial 3D Rendering → AI 2D-to-3D Depth** to run Depth Anything V2 asynchronously on the incoming 2D game frames. Horizon Stream uses the estimated depth to create separate eye views; it does not receive native stereoscopic video from the PlayStation.

The available **AI Depth Strength** presets are:

| Preset | Intended result |
|---|---|
| Comfort | Subtle depth; recommended starting point. |
| Balanced | Default middle-ground depth. |
| Enhanced | More pronounced depth. |
| Strong | Maximum displacement; most likely to reveal estimation artifacts. |

AI depth estimation can be less convincing for HUDs, fast cuts, transparency, particles, or scenes with limited visual depth cues. If the image looks uncomfortable, unstable, overly separated, or performance drops, lower the strength or switch the feature off.

## Troubleshooting

- **No console is discovered:** turn on the PlayStation, enable Remote Play, wake it from standby if necessary, and confirm the Quest and console are on the same local network. Use **Refresh search** on the connections page.
- **Camera permission is denied:** on Quest 3/3S, return to the connections page, choose the unregistered console again, and allow **Headset Camera** access. If you use Quest 2 or Quest Pro, do not keep retrying camera permission: follow the manual pairing steps above instead.
- **QR scanner does not show the computer screen:** keep the Chrome QR code bright and fully visible, move the computer closer, and keep it inside the blue frame. If the code is stale or already used, complete a new Chrome sign-in and scan the new QR code once.
- **Sony returns an authorization error:** the compatibility sign-in process may have been rejected server-side. Start again with a fresh Chrome sign-in; Horizon Stream cannot safely bypass a Sony server decision.
- **The PS5 Link Device code is not detected:** open **Settings → System → Remote Play → Link Device** again, leave the eight-digit code visible, and keep it inside the camera frame. Retry pairing with the fresh code.
- **Pairing fails:** make sure the PS5 remains awake, Remote Play is enabled, and the code has not expired. Press a Quest controller button to retry the Link Device scan.
- **Quest controls are not working:** confirm **Immersive VR Mode** is enabled, then begin a new stream. If **Quest Controller Support** is off, Quest controllers remain available as pointers for the long-press stream controls; use a Bluetooth controller for gameplay input.
- **AI depth is flat or visually wrong:** verify **AI 2D-to-3D Depth** is on before starting the stream, then try a lower strength or turn it off.
