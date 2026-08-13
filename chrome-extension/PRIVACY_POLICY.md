# Horizon Stream PSN Authenticator — Privacy Policy

**Effective date:** August 13, 2026

Horizon Stream PSN Authenticator is a local browser helper for transferring a one-time PlayStation Remote Play sign-in redirect to Horizon Stream or another compatible client.

## Data collection

The extension does **not** collect, sell, or share personal data. It does not use analytics, advertising identifiers, tracking pixels, cookies, a remote database, or a relay service. It does not transmit data to the extension publisher.

The extension does not read or store Sony passwords, multi-factor authentication codes, cookies, page contents, browsing history, or unrelated tabs.

## Local processing

When the user starts sign-in, the extension opens Sony's normal sign-in flow in a Chrome tab. It watches only the top-level navigation in that tab for the final redirect at `remoteplay.dl.playstation.net`. The one-time redirect URL is processed locally in the browser and is displayed as a QR code and copyable text.

For the side panel to receive the redirect after the service worker or panel is recreated, the URL and temporary sign-in tab identifier may be held in Chrome's `storage.session` area. This storage is local to the browser session; it is not synced or uploaded by the extension and is removed when the session data is cleared or the user starts another sign-in. The URL is a sensitive, one-time authorization value. Users should scan or copy it immediately and not share it.

The QR code is generated entirely by the bundled code included in the extension package. No remote JavaScript, WebAssembly, or other remote code is loaded.

## Third-party services

The user signs in directly with Sony Interactive Entertainment. Sony's own privacy policy and terms apply to that sign-in and to any information the user submits to Sony. Horizon Stream PSN Authenticator does not receive the user's Sony credentials or act as a Sony service.

## Permissions

- `sidePanel` displays the helper as a persistent Chrome side panel.
- `webNavigation` detects the final top-level Remote Play redirect in the sign-in tab opened by the extension.
- `storage` provides temporary, browser-local session state so the panel can display the captured redirect.
- The host permission for `https://remoteplay.dl.playstation.net/*` is limited to detecting that final redirect.

## Changes and contact

If this policy changes, the updated version will be published in this repository with a new effective date. For questions or privacy concerns, please open an issue in the [Horizon Stream repository](https://github.com/0TheRain0/horizonstream/issues).
