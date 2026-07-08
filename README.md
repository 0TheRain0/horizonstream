# Horizon Stream

Horizon Stream is an open-source PlayStation Remote Play client specifically designed and optimized for Meta Quest VR headsets. It aims to bring a better, more immersive experience to your goggles, and is fully compatible with the **Meta Quest 2, Quest 3, Quest 3S, and Quest Pro**.

This project is a dedicated VR fork of the [chiaki-ng code](https://github.com/streetpea/chiaki-ng). 

## Goals & Roadmap
- Provide an immersive, high-performance PlayStation Remote Play experience directly on Meta Quest headsets.
- Streamline and simplify the console pairing process in future updates.

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
*   **In-App Login Helper (Recommended):** During registration, tap the **Retrieve Account ID via PSN Login** button. This opens a secure, official Sony login portal. After signing in, the app will automatically fetch your account details, convert them into the required Base64 format, and auto-fill the field for you.
*   **External Web Tools:** If you prefer, you can retrieve it manually by using a community service like [psn.flipscreen.games](https://psn.flipscreen.games/) or the official [Chiaki Web Tool](https://streetpea.github.io/chiaki-ng/setup/configuration/#obtaining-your-psn-account-id). Copy the **Base64** formatted version (which typically ends in `=`).

### 2. Prepare Your PlayStation 5
1. Turn on your PS5 and go to **Settings > System > Remote Play**.
2. Toggle on **Enable Remote Play**.
3. Select **Link Device**. The screen will display an 8-digit PIN.

### 3. Connect Horizon Stream
1. Open the Horizon Stream app on your Meta Quest.
2. Ensure your headset is on the same local network as your PS5.
3. Select your console when it appears, or enter its IP address manually.
4. Input your **Base64 PSN Account ID** and the **8-digit PIN** from your TV screen.
5. Click **Register** to complete the pairing. You're now ready to stream!

## Support & Community

If you need help setting up Horizon Stream, run into bugs, or want to chat with the community, join our official Discord server!

* **[Horizon Stream Discord](https://discord.gg/tXhkq5BBY)**

For questions regarding underlying core features and issues inherited from the upstream project, you can also visit the [chiaki-ng community Discord](https://discord.gg/tAMbRuwXDH) as a secondary resource.
