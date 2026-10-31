# Distraction-Free

Distraction-Free is a native Java Android application for locally enforced app and
website restrictions. It is intentionally small: the dashboard has only **Apps to
Block** and **Websites to Block**.

## Build and install

1. Open this directory in Android Studio (Ladybug or newer).
2. Allow Gradle to download the Android Gradle Plugin and AndroidX/Material
   dependencies.
3. Use **Build > Make Project**, then install the debug APK on a physical test
   device running Android 8.0 (API 26) or newer.

The repository does not include a Gradle wrapper; Android Studio can generate one
for a locally installed Gradle distribution if command-line builds are required.

## Device Owner setup

Device Owner is a provisioning mode, not a normal runtime permission. Use a
factory-reset test device or an emulator with no accounts, then provision from an
ADB shell:

```text
adb shell dpm set-device-owner com.distractionfree/.DeviceAdminReceiver
```

Confirm the dashboard says `Device Owner: ready` before selecting apps. The app
uses `DevicePolicyManager.setApplicationHidden()` and persists the selected
package names in its private SQLite database. A Device Owner can only be removed
through device-management provisioning tools; there is deliberately no in-app
unlock or temporary bypass.

## Website filtering

The first website list is stored locally: `instagram.com`, `youtube.com`,
`facebook.com`, `x.com`, and `reddit.com`. Matching is exact-or-subdomain
matching, so `m.instagram.com` is blocked by `instagram.com`.

The VPN is a local DNS interception tunnel. It routes the configured DNS address
to the service, returns NXDOMAIN for blocked names, and forwards other DNS
queries to Cloudflare DNS through a protected socket. It does not upload DNS
queries or browsing history. The foreground notification and dashboard status
make the service state visible.

This is not absolute tamper-proof enforcement. A user who can change VPN
permissions, install another VPN, use a proxy, use encrypted DNS/DoH/DoT, or
use an application with its own resolver may bypass DNS filtering. IPv6 and
non-DNS traffic are intentionally not claimed as covered by this minimal local
filter. Device Owner policies and managed-device configuration are required for
stronger enterprise enforcement.

## Testing checklist

- Provision Device Owner on a reset physical device and verify the dashboard status.
- Confirm Instagram, Chrome, and YouTube appear selected when installed.
- Select another app and verify it becomes hidden; restart the device and verify
  the restriction remains.
- Grant VPN permission and verify the foreground notification and `VPN: active`.
- Resolve a blocked root domain and a subdomain in a normal browser.
- Remove a domain and confirm it is no longer returned as blocked.
- Revoke VPN permission, stop the service, and verify the dashboard does not claim
  protection is active.
