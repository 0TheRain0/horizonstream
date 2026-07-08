## Getting Started: Pairing with PlayStation 5

To use Horizon Stream, you need to link it to your PlayStation 5 console using a Base64 encoded PSN Account ID and a Pairing PIN.

### 1. Obtain Your PSN Account ID (Base64)
Sony requires a specific 8-byte Account ID to register for Remote Play.
*   **In-App Login Helper (Recommended):** During registration, tap the **Retrieve Account ID via PSN Login** button. This opens a secure, official Sony login portal. After signing in, the app will automatically fetch your account details, convert them into the required Base64 format, and auto-fill the field for you.
*   **External Web Tools:** If you prefer, you can retrieve it manually by using a community service like [psn.flipscreen.games](https://psn.flipscreen.games/) or the official [Chiaki Web Tool](https://streetpea.github.io/chiaki-ng/setup/configuration/#obtaining-your-psn-account-id). Copy the **Base64** formatted version (which typically ends in `=`).

### 2. Prepare Your PlayStation 5
1. Turn on your PS5 and go to **Settings > System > Remote Play**.
2. Toggle on **Enable Remote Play**.
3. Select **Link Device**. The screen will display an 8-digit PIN.

### 3. Pair a Bluetooth Controller
Because the Meta Quest operating system blocks 2D apps from reading raw input from the Quest's motion controllers, **you must pair a Bluetooth gamepad to your headset** to play.
1. Put your controller (e.g., PlayStation DualSense or DualShock 4) into Bluetooth pairing mode. (For PlayStation controllers, hold the **PS button** and the **Share/Create button** until the light bar flashes rapidly).
2. On your Meta Quest, open the **Settings** menu.
3. Navigate to **Devices > Bluetooth**.
4. Select your controller from the list of available devices to pair it.

### 4. Connect Horizon Stream
1. Open the Horizon Stream app on your Meta Quest.
2. Ensure your headset is on the same local network as your PS5.
3. Select your console when it appears, or enter its IP address manually.
4. Input your **Base64 PSN Account ID** and the **8-digit PIN** from your TV screen.
5. Click **Register** to complete the pairing. You're now ready to stream!

## Support & Community

If you need help setting up Horizon Stream, run into bugs, or want to chat with the community, join our official Discord server!

* **[Horizon Stream Discord](https://discord.gg/tXhkq5BBY)**

For questions regarding underlying core features and issues inherited from the upstream project, you can also visit the [chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH) as a secondary resource.
