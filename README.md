# Horizon Stream

Horizon Stream is an open-source PlayStation Remote Play client specifically designed and optimized for Meta Quest VR headsets. It aims to bring a better, more immersive experience to your goggles. Immersive streaming is designed for the **Meta Quest 2, Quest 3, Quest 3S, and Quest Pro**; camera-assisted QR setup requires a Quest 3 or Quest 3S because those are the Quest headsets that expose passthrough camera frames to apps.

This project is a dedicated VR fork of the [chiaki-ng code](https://github.com/streetpea/chiaki-ng). 

## Goals & Roadmap
- Provide an immersive, high-performance PlayStation Remote Play experience directly on Meta Quest headsets.
- Keep console discovery, PSN sign-in, code scanning, pairing, and connection in one clear Quest-first flow.

## Quest features

- **Guided Quest onboarding:** On Quest 3 and Quest 3S, discover an unregistered console, approve headset-camera access, sign in to PlayStation Network in Chrome, scan the one-time sign-in QR code, and scan the console's Link Device code from the headset.
- **Quest 2/Pro manual pairing:** Quest 2 and Quest Pro can stream normally, but Horizon OS does not expose the raw passthrough camera frames this app needs to scan setup codes. Their setup route uses the Chrome extension's copyable redirect URL and the console's Link Device code instead.
- **Saved PSN Account ID:** Horizon Stream retrieves the Account ID locally and saves it in the app, so repeat onboarding skips the sign-in QR step and goes straight to the console Link Device step.
- **Immersive VR Mode:** Presents the stream on a head-tracked spatial screen and keeps connection errors and pairing prompts visible in-headset.
- **Quest Touch controller support:** Uses Quest controllers as a virtual PlayStation controller during immersive streams, with clear mappings and dedicated Menu-button gestures. Bluetooth controllers can also navigate the in-stream settings panel.
- **AI 2D-to-3D depth:** Uses Depth Anything V2 to infer depth from the 2D stream and render separate eye views. Choose Comfort, Balanced, Enhanced, or Strong depth strength.

AI depth can increase battery use or visual complexity. A Bluetooth gamepad and the regular flat stream remain available when reliability is more important than immersion.

## Documentation

- [Quest setup and registration](docs/setup/quest.md)
- [Quest Touch and controller mappings](docs/setup/controlling.md#horizon-stream-on-meta-quest)
- [Chrome redirect-QR extension](chrome-extension/README.md)

## Disclaimer
This project is not endorsed or certified by Sony Interactive Entertainment LLC.

Horizon Stream is Free and Open Source Software, built upon the excellent foundation of Chiaki and chiaki-ng.

## License & Open Source Compliance
This software is licensed under the **GNU Affero General Public License version 3 (AGPLv3)**. 

In accordance with the AGPLv3:
- **Modifications:** This project is a modified version of the original [chiaki-ng](https://github.com/streetpea/chiaki-ng) software. Modifications for Meta Quest VR compatibility were initially made in 2026.
- **Source Code:** The complete source code for Horizon Stream, including all modifications, is publicly available in this repository. Anyone is free to download, study, modify, and distribute the code under the same AGPLv3 license terms.
- **No DRM:** The software is provided without any Digital Rights Management (DRM) restrictions.

A copy of the AGPLv3 license is included in the `COPYING` file.

## Getting Started: Quest setup

Horizon Stream's Quest-first setup walks you through discovery, PSN sign-in, console pairing, and immersive streaming. The Quest and PlayStation should be on the same local network, and Remote Play must be enabled on the console.

### First-time setup

1. Turn on the PlayStation and wait for it to appear as an unregistered console in Horizon Stream. If it does not appear, use **Refresh search** and check the network.
2. Select the discovered console. On Quest 3/3S, approve the **Headset Camera** permission before immersive onboarding opens. It is used only to scan setup codes. Quest 2 and Quest Pro users should use the manual pairing instructions below.
3. On a computer with Google Chrome, install the [Horizon Stream PSN Authenticator](https://chromewebstore.google.com/detail/horizon-stream-psn-authenticator/pcdjngmmgchdmemcacffbmcpedjpgohe).
4. Open the extension side panel, choose **Open PS Remote Play sign-in**, and complete the normal PlayStation Network sign-in in Chrome. Keep the panel open until it displays the one-time QR code.
5. In the headset, press a Quest controller button to scan the QR code. Horizon Stream retrieves the PSN Account ID locally and saves it for future setup.
6. On the PS5, open **Settings → System → Remote Play → Link Device**. Leave the eight-digit code visible, then press a Quest controller button when onboarding asks you to scan it.
7. Keep the PS5 awake and on the same network while Horizon Stream pairs it. After pairing, review the Quest controller mappings and press any button to connect.

The QR code is a one-time redirect and should never be shared. If it expires, create a new Chrome sign-in and scan the new code once.

### Quest 2 and Quest Pro manual pairing

Quest 2 and Quest Pro do not provide apps with the raw passthrough camera frames required for QR and Link Device scanning. This is a headset-platform limitation, not a permission problem. Horizon Stream automatically opens the regular setup screen after you choose a discovered console.

1. On a computer with Chrome, install and open the Horizon Stream PSN Authenticator extension, select **Open PS Remote Play sign-in**, and sign in to PlayStation Network.
2. When the extension shows the final redirect, choose **Copy URL**. Treat that one-time URL like a password and do not share it.
3. In Horizon Stream, choose **QR scanning isn't working**, then **Advanced setup**. Paste the copied URL into **PS Remote Play redirect URL**. Horizon Stream retrieves and saves the PSN Account ID locally. Enter the console address only if it was not filled in from discovery.
4. On PS5, open **Settings → System → Remote Play → Link Device**. On PS4, open **Settings → Remote Play Connection Settings → Add Device**. Enter the fresh eight-digit code in Horizon Stream, then pair and save.

No headset-camera permission is needed for this route. After registration, Quest 2 and Quest Pro support both regular streaming and the immersive screen.

### Repeat setup

After the PSN Account ID has been saved, future onboarding skips the computer sign-in and QR pages. It goes directly to the PS5 Link Device instructions. A fresh eight-digit Link Device code is still required for each new console registration.

### Controllers

Quest Touch controllers can emulate a PlayStation controller in Immersive VR Mode. A/B map to Cross/Circle, X/Y to Square/Triangle, triggers to L2/R2, grips to L1/R1, and the left-grip plus left-stick chord to the D-pad. Stick clicks map to L3/R3. Tap Quest Menu for Options, double-tap for the PlayStation button, and long-press to send Options and open in-stream controls. Aim a controller and squeeze the trigger to resize the spatial screen, toggle the curved view, change settings, or exit streaming; this pointer remains available even when Quest Controller Support is disabled. Press the Quest Meta button to recenter the screen on your full gaze, including vertical pitch, while keeping its edges level. A paired Bluetooth controller can navigate the in-stream menu with its D-pad or left stick and activate selections with its primary button.

You can also pair a DualSense, DualShock 4, or another Bluetooth gamepad in **Quest Settings → Devices → Bluetooth**.

## Support & Community

If you need help setting up Horizon Stream, run into bugs, or want to chat with the community, join our official Discord server!

* **[Horizon Stream Discord](https://discord.gg/tXhkq5BBY)**

For questions regarding underlying core features and issues inherited from the upstream project, you can also visit the [chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH) as a secondary resource.
