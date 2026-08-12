# Horizon Stream PSN Account ID extension

This Chrome extension creates a local QR transfer code from a one-time PS Remote Play redirect URL. Horizon Stream on Quest scans that code and performs the final Account-ID lookup locally.

## Use it

1. Select **Open PS Remote Play sign-in** and complete Sony's normal sign-in in Chrome.
2. When the sign-in tab reaches `https://remoteplay.dl.playstation.net/remoteplay/redirect?...`, reopen the extension. It captures that one-time redirect automatically and immediately shows the QR code. If the capture did not appear, copy the *entire* address-bar URL and paste it into step 2, then select **Create sign-in QR code**. Treat the URL like a password: do not share it.
3. In Horizon Stream, choose **Scan QR code** and look at the QR code through the headset. Horizon Stream completes the Account-ID lookup on the Quest.

The extension does not inspect browser cookies, page content, or passwords, and it does not call Sony. It observes only the top-level final redirect in the exact sign-in tab it opened. Nothing is sent to a relay service, and data is not saved after the browser session closes. The QR code itself contains the one-time redirect URL, so scan it immediately and do not show it to anyone else.

## Load it in Chrome

1. Open `chrome://extensions`.
2. Enable **Developer mode**.
3. Select **Load unpacked** and choose this `chrome-extension` folder.
4. Pin and open **Horizon Stream PSN Account ID**.
5. Follow the three steps above.

The extension does not persist the redirect URL; closing the popup clears it. This is a compatibility workflow based on the legacy Remote Play authorization flow, not a PlayStation-supported developer integration; Sony may change or stop accepting it at any time.

## Troubleshooting

- Click **Reload** for the extension on `chrome://extensions` after updating, then begin again with step 1. An authorization URL created before the update, or used once already, cannot be retried.
- If Horizon Stream says the code is expired, already used, or issued for a different request, create a new redirect URL and QR code, then scan it only once.
- If Horizon Stream reports a 403, Sony rejected the compatibility flow. Neither the extension nor Horizon Stream can safely work around that server-side decision.
