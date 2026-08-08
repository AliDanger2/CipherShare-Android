# CipherShare for Android

CipherShare for Android is the official mobile companion to CipherShare Desktop, enabling fast, direct file and clipboard transfers between Android devices and computers over a local network.

Built around the same protocol as the desktop application, CipherShare allows devices on the same LAN to automatically discover one another and transfer files and clipboard content without requiring cloud services, accounts, or an internet connection.

## Features

* Automatic LAN device discovery
* Fast peer-to-peer file transfers
* Send individual files or entire folders
* Clipboard transfers (plain text and images only — see Known limitations for how receiving actually behaves)
* Receive files with optional transfer approval
* SHA-256 integrity verification
* Background discoverability while the app is open or minimized — this stops if you swipe CipherShare away from Recents, which deliberately shuts the background listener down rather than leaving it running silently (see Known limitations)
* Configurable download location
* Transfer history
* Device management
* Dark interface matching CipherShare Desktop
* Proper app termination when closed by the user

## Privacy

CipherShare transfers files and clipboard content directly between your devices.

* No cloud servers
* No accounts
* No telemetry
* No data collection
* No internet connection required for local transfers

Your files and clipboard content never leave your local network, but they aren't encrypted while they're on it — see Known limitations.

## Known limitations

* Discovery relies on IPv4 UDP broadcast and is limited to a single LAN segment; it will not cross routers or subnets that block broadcast traffic.
* Clipboard sync only supports plain text and images — there's no way to send a copied file via the clipboard button.
* Receiving clipboard content doesn't write to your phone's clipboard the instant it arrives if CipherShare isn't in focus. This isn't a bug — since Android 10, the OS silently blocks any app that doesn't currently have window focus (including a foreground service) from writing to the clipboard. CipherShare holds received clipboard content as pending and applies it as soon as the app is actually opened or you tap the "Transfer complete" notification.
* Closing CipherShare (swiping it away from Recents) intentionally stops the background service, which means the app also stops being discoverable and stops listening for incoming transfers at that point — it won't keep receiving in the background indefinitely.
* Transfers are sent in plaintext over TCP; integrity is verified via SHA-256, but transfers are not encrypted in transit. Use CipherShare only on networks you trust.

## Compatibility

CipherShare for Android is fully compatible with CipherShare Desktop and communicates using the same discovery and transfer protocol.

## Project Status

CipherShare is actively under development.

## Download

Download the latest APK from the project's **GitHub Releases** page.

## License

This project is licensed under the MIT License.
