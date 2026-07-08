# Horizon Stream Project Context

This file contains the brief history and context of the Horizon Stream project to inform Antigravity and other agents of the project's goals, licensing requirements, and past development steps.

## Project History & Context

1. **Origins & Forking:**
   - This project is a dedicated VR fork of the open-source PlayStation Remote Play client `chiaki-ng` (and originally `chiaki`).
   - The goal is to bring a better remote play experience to Meta Quest VR headsets (Quest 2, 3, 3s, and Pro) and to eventually streamline the PS5 pairing process.

2. **VR Adaptations & Fixes:**
   - The Android app was modified to lock in landscape mode (ignoring sensor rotation).
   - Fixed crashing/restarting issues on the Quest 3 by configuring the app to handle dynamic window size and config changes properly.
   - Initial VR-specific modifications (including a `DeviceUtils` helper and namespace migration to `com.cmsoft.horizonstream`) were ported over from a local `chiaki-ng` repository.

3. **Rebranding & Licensing Compliance (AGPLv3 & Meta):**
   - The app is intended to be distributed on the Meta Horizon Store.
   - To comply with Meta's developer policies and avoid trademark infringement, the app was entirely rebranded from "Chiaki" to "Horizon Stream".
   - To comply with the AGPLv3 open-source license, the source code must remain public, the store listing must prominently link to this repository, and no additional DRM can be added to the APK.
   - Documentation (like `README.md` and `CONTRIBUTOR_GUIDE.md`) was rewritten to reflect the new branding, explicitly state AGPLv3 compliance, and include instructions on how to obtain a PSN Account ID (Base64) and pair with a PlayStation 5 using an 8-digit PIN.

4. **Meta Store Setup:**
   - Android V2 app signing was set up to prepare for Meta Horizon Store publishing. A keystore (`horizonstream.keystore`) was generated and securely configured via `local.properties` (using the password `March9219` for both store and key).

## Guidelines for Agents
- Ensure any new UI/UX changes take the Meta Quest VR environment (and dynamic window resizing) into account.
- Maintain AGPLv3 compliance; do not add any proprietary DRM restrictions.
- Keep branding strictly as "Horizon Stream" and avoid the original "Chiaki" name in user-facing elements.
- Do not automatically push local changes to remote repositories; wait for explicit user confirmation before running push commands.
