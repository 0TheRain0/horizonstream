# Horizon Stream PSN Authenticator extension

This Chrome extension captures a one-time PS Remote Play redirect URL and presents it as a Horizon Stream QR code plus a copyable plain-text URL. Horizon Stream on Quest scans the QR code and performs the final Account-ID lookup locally. The plain-text URL can also be used by any client that accepts a PS Remote Play sign-in redirect URL.

## Use it

1. Select the **Horizon Stream PSN Authenticator** toolbar icon to open its persistent **side panel**.
2. In the side panel, select **Open PS Remote Play sign-in** and complete Sony's normal sign-in in the new Chrome tab. Keep the panel open beside that tab.
3. When the sign-in tab reaches `https://remoteplay.dl.playstation.net/remoteplay/redirect?...`, the panel automatically changes to the QR and complete redirect URL. No copying or pasting is needed for Horizon Stream.
4. In Horizon Stream, choose **Scan sign-in QR code** and look at the QR code through the headset. Horizon Stream completes the Account-ID lookup on the Quest. To use the captured result with any other PS Remote Play client that accepts a sign-in redirect URL, copy the plain-text URL instead.

The extension does not inspect browser cookies, page content, or passwords, and it does not call Sony. It observes only the top-level final redirect in the exact sign-in tab it opened. Nothing is sent to a relay service, and data is not saved after the browser session closes. The QR code itself contains the one-time redirect URL, so scan it immediately and do not show it to anyone else.

## Load it in Chrome

1. Open `chrome://extensions`.
2. Enable **Developer mode**.
3. Select **Load unpacked** and choose this `chrome-extension` folder.
4. Pin **Horizon Stream PSN Authenticator**, then select its toolbar icon to open the side panel.
5. Follow the four steps above.

The helper does not persist the redirect URL after the browser session ends. The panel must remain open during sign-in so it can display the result automatically. This is a compatibility workflow based on the legacy Remote Play authorization flow, not a PlayStation-supported developer integration; Sony may change or stop accepting it at any time.

## Privacy

Horizon Stream PSN Authenticator processes the one-time redirect locally and does not collect, transmit, sell, or share user data. See the complete [privacy policy](PRIVACY_POLICY.md).

## Troubleshooting

- Click **Reload** for the extension on `chrome://extensions` after updating, then begin again with step 1. An authorization URL created before the update, or used once already, cannot be retried.
- If Horizon Stream says the code is expired, already used, or issued for a different request, create a new redirect URL and QR code, then scan it only once.
- If Horizon Stream reports a 403, Sony rejected the compatibility flow. Neither the extension nor Horizon Stream can safely work around that server-side decision.
