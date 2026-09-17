## Getting Started: Quest setup

The Quest setup flow keeps console discovery, PSN sign-in, pairing, and immersive streaming together. Put the Quest and PlayStation on the same local network, turn on the PlayStation, and enable Remote Play before starting.

### First-time setup

1. Open Horizon Stream and wait for the unregistered console to appear. Use **Refresh search** if needed.
2. Select the discovered console and approve **Headset Camera** access. It is used only for scanning the sign-in QR code and console Link Device code.
3. On a computer with Chrome, install the [Horizon Stream PSN Authenticator](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe).
4. Open the extension side panel, choose **Open PS Remote Play sign-in**, and finish signing in to PlayStation Network. Keep the panel open until the one-time QR code appears.
5. Press any button on a Quest controller to start the headset scan. Hold the computer QR code inside the frame. Horizon Stream retrieves the PSN Account ID locally and saves it.
6. On the PS5, open **Settings → System → Remote Play → Link Device** and leave the eight-digit code visible. Press any Quest controller button when onboarding asks you to scan it.
7. Keep the PS5 awake and on the same network while pairing. When pairing succeeds, review the controller mappings and press a button to connect to the immersive stream.

The sign-in QR code contains a one-time redirect URL. Do not share it; if it is stale or already used, create a new Chrome sign-in and scan the new code once.

### Repeat setup

The saved PSN Account ID lets future onboarding skip the Chrome sign-in and QR steps. Each new console registration still requires a fresh PS5 Link Device code.

### Controller options

Quest Touch support is available in Immersive VR Mode. A/B map to Cross/Circle, X/Y to Square/Triangle, triggers to L2/R2, grips to L1/R1, and the left-grip plus left-stick chord to the D-pad. You can also pair a DualSense, DualShock 4, or other Bluetooth gamepad from **Quest Settings → Devices → Bluetooth**.

## Quest streaming features

Horizon Stream includes immersive features configured in the app's **Settings** screen:

- **Immersive VR Mode:** presents the stream as a head-tracked spatial screen. It is required for Quest Touch controller support, pointer controls, and AI depth features.
- **Recenter the immersive screen:** press the Quest **Meta** button to recenter the headset. Horizon Stream places the screen along your full look direction, including vertical pitch, while keeping the screen level and fixed until the next recenter.
- **Curved immersive screen:** wraps the spatial screen around you when the headset runtime supports OpenXR cylinder layers.
- **Quest Controller Support:** maps Quest Touch inputs to a virtual PlayStation controller. Tap Quest Menu for Options, double-tap for the PlayStation button, and long-press to send Options and open the in-stream controls. Menu + X sends Share, Menu + Y sends the PlayStation button, and Menu + left-stick click sends a touchpad click.
- **In-stream controls:** long-press Quest Menu to send one Options press and open the settings panel. On a Bluetooth controller, use its Options/Menu button in the same way. The panel includes screen size, curve, Quest Controller Support, AI depth, close, and exit controls; Bluetooth D-pad/left-stick navigation is supported.
- **AI 2D-to-3D Depth:** runs Depth Anything V2 asynchronously to create separate eye views in Immersive VR Mode. Start at **Comfort** or **Balanced**; **Enhanced** and **Strong** are more pronounced but may produce artifacts or use more resources.

If a game has unstable input, visual artifacts, or unacceptable battery use, disable AI depth and use a Bluetooth controller with the flat stream.

For the complete Quest guide, see [docs/setup/quest.md](docs/setup/quest.md).

## Support & Community

If you need help setting up Horizon Stream, run into bugs, or want to chat with the community, join our official Discord server!

* **[Horizon Stream Discord](https://discord.gg/tXhkq5BBY)**

For questions regarding underlying core features and issues inherited from the upstream project, you can also visit the [chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH) as a secondary resource.
