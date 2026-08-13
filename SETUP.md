## Getting Started: Pairing with PlayStation 5

To use Horizon Stream, you need to link it to your PlayStation 5 console using a Base64 encoded PSN Account ID and a Pairing PIN.

### 1. Obtain Your PSN Account ID (Base64)
Sony requires a specific 8-byte Account ID to register for Remote Play.
Use the Chrome QR transfer before obtaining a console Link Device code:

1. In the Horizon Stream Chrome extension, select its toolbar icon to open the side panel, then select **Open PS Remote Play sign-in** and complete Sony's normal sign-in in Chrome.
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

## Quest streaming features

Horizon Stream includes experimental immersive features configured in the app's **Settings** screen:

- **Immersive VR Mode:** presents the stream as a head-tracked spatial screen. It is required for the Quest Touch controller and AI depth features.
- **Quest Controller Gamepad Emulation:** maps Quest Touch inputs to a virtual PlayStation controller. Tap Quest Menu for Options, double-tap for the PlayStation button, and long-press to exit the stream. Menu + X sends Share, Menu + Y sends the PlayStation button, and Menu + left-stick click sends a touchpad click.
- **Exit Stream Button:** under **Controls & Input**, learn a separate Quest or Bluetooth-gamepad button to exit the stream. The Quest Menu long-press is always reserved and cannot be reassigned; the learned assignment can be cleared.
- **AI 2D-to-3D Depth:** runs Depth Anything V2 asynchronously to create separate eye views in Immersive VR Mode. Start at **Comfort** or **Balanced**; **Enhanced** and **Strong** are more pronounced but may produce artifacts or use more resources.

These features are experimental. If a game has unstable input, visual artifacts, or unacceptable battery use, disable them and use a Bluetooth controller with the flat stream.

For the complete Quest guide, see [docs/setup/quest.md](docs/setup/quest.md).

## Support & Community

If you need help setting up Horizon Stream, run into bugs, or want to chat with the community, join our official Discord server!

* **[Horizon Stream Discord](https://discord.gg/tXhkq5BBY)**

For questions regarding underlying core features and issues inherited from the upstream project, you can also visit the [chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH) as a secondary resource.
