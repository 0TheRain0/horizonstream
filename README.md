# Horizon Stream

Horizon Stream is an open-source PlayStation Remote Play client specifically designed and optimized for Meta Quest VR headsets. It aims to bring a better, more immersive experience to your goggles, and is fully compatible with the **Meta Quest 2, Quest 3, Quest 3S, and Quest Pro**.

This project is a dedicated VR fork of the [chiaki-ng code](https://github.com/streetpea/chiaki-ng). 

## Goals & Roadmap
- Provide an immersive, high-performance PlayStation Remote Play experience directly on Meta Quest headsets.
- Streamline and simplify the console pairing process in future updates.

## Quest features

- **Chrome redirect-QR registration:** The bundled Chrome extension transfers a one-time PS Remote Play redirect URL to the Quest. Horizon Stream completes the Account-ID lookup locally, without the extension calling Sony.
- **Immersive VR Mode (experimental):** Presents the stream on a head-tracked spatial screen and keeps connection errors and PIN prompts visible in-headset.
- **Quest Touch controller emulation (experimental):** Uses Quest controllers as a virtual PlayStation controller during immersive streams, with dedicated Menu-button gestures and a learnable exit-stream button.
- **AI 2D-to-3D depth (experimental):** Uses Depth Anything V2 to infer depth from the 2D stream and render separate eye views. Choose Comfort, Balanced, Enhanced, or Strong depth strength.

Experimental features can increase battery use, latency, or visual/input instability. A Bluetooth gamepad and the regular flat stream remain the recommended fallback when reliability is more important than immersion.

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

## Getting Started: Pairing with PlayStation 5

To use Horizon Stream, you need to link it to your PlayStation 5 console using a Base64 encoded PSN Account ID and a Pairing PIN.

### 1. Obtain Your PSN Account ID (Base64)
Sony requires a specific 8-byte Account ID to register for Remote Play.
Use the **Chrome QR transfer** before obtaining a console Link Device code:

1. In the [Horizon Stream extension](chrome-extension/README.md), select its toolbar icon to open the side panel, then select **Open PS Remote Play sign-in** and complete Sony's normal sign-in in Chrome.
2. Keep the side panel open. It automatically displays the sign-in QR code when Sony reaches the final redirect.
3. In Horizon Stream registration, select **Scan sign-in QR code** and look at that code through the headset. Horizon Stream completes the Account-ID lookup locally.

The QR code contains a one-time PS Remote Play redirect URL, so scan it immediately and never share it. Alternatively, paste that complete redirect URL directly into Horizon Stream, or paste a Base64 Account ID obtained through another method.

### 2. Obtain a fresh Link Device code from your PlayStation 5
1. Turn on your PS5 and go to **Settings > System > Remote Play**.
2. Toggle on **Enable Remote Play**.
3. Select **Link Device**. The screen will display an 8-digit PIN.

### 3. Choose a controller
**Quest Controller Gamepad Emulation** is available experimentally in Immersive VR Mode. Pair a Bluetooth controller when you prefer conventional or more reliable gamepad input.
1. Put your controller (e.g., PlayStation DualSense or DualShock 4) into Bluetooth pairing mode. (For PlayStation controllers, hold the **PS button** and the **Share/Create button** until the light bar flashes rapidly).
2. On your Meta Quest, open the **Settings** menu.
3. Navigate to **Devices > Bluetooth**.
4. Select your controller from the list of available devices to pair it.

### 4. Connect Horizon Stream
1. Open the Horizon Stream app on your Meta Quest.
2. Ensure your headset is on the same local network as your PS5.
3. Select your console when it appears, or enter its IP address manually.
4. First provide your **Base64 PSN Account ID** using the QR transfer, a redirect URL, or an Account ID obtained elsewhere. Then obtain and enter the fresh **8-digit Link Device code** from your console screen. It is not your PSN password or sign-in PIN.
5. Click **Register** to complete the pairing. You're now ready to stream!

## Support & Community

If you need help setting up Horizon Stream, run into bugs, or want to chat with the community, join our official Discord server!

* **[Horizon Stream Discord](https://discord.gg/tXhkq5BBY)**

For questions regarding underlying core features and issues inherited from the upstream project, you can also visit the [chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH) as a secondary resource.
