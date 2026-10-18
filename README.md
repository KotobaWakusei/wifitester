# WifiKiller — Wireless Workbench

A rooted-Android utility for **passive Wi-Fi diagnostics** and read-only security inventory. Device and driver capabilities vary; a successful APK build does not guarantee that monitor mode is available on a particular phone.

## Current features

- **Root status** — verifies that `su -c id` actually returns UID 0; the check has a timeout so a pending root-manager prompt cannot leave the dashboard stuck forever.
- **Monitor-mode workbench** — discovers interfaces and frequencies advertised by `iw`, lets the user choose a channel, starts `tcpdump`, and records timestamped diagnostics.
- **State-aware cleanup** — records the selected interface's administrative UP/DOWN state, avoids deleting a monitor interface it did not create, and attempts to restore the original interface after stopping or a failed start.
- **Diagnostic export** — saves the current diagnostic log to a user-selected text file.
- **Read-only Wi-Fi inventory** — uses Android's public Wi-Fi scan API to display visible SSIDs, BSSIDs, signal levels, frequency/channel, and reported security capabilities; it can share a text report.

## Requirements

- Android 5.0+ (API 21+) for installation; recent Android versions and OEM builds may impose additional restrictions.
- Root access granted by a root manager.
- For monitor mode: working `iw`, `ip`, and `tcpdump` commands, plus a kernel driver that supports monitor interfaces and the selected channel.
- Wi-Fi scanning in the inventory screen requires Android location permission and may depend on the system Location toggle and scan throttling.

## Build

The project uses Gradle 8.7, JDK 17, and Android SDK Platform 34.

```bash
gradle --no-daemon assembleDebug
```

The APK is produced at `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions builds the debug APK on pull requests and pushes to `master`; open the relevant workflow run and download the `wifikill-debug-apk` artifact.

## Using monitor mode

1. Open **Wireless Workbench** and grant Root when prompted.
2. Open **Monitor Mode** and tap **重新检测设备** to refresh the detected interfaces, tools, and driver-advertised channels.
3. Choose an interface and channel, then read and accept the interruption warning before starting.
4. Inspect the diagnostics and passive `tcpdump` output.
5. Tap **停止并恢复** when finished. Confirm that normal Wi-Fi connectivity has returned.
6. Use **导出完整诊断报告** to save a text report if troubleshooting is needed.

### Important limitations

- `iw list` reports driver-advertised capabilities, not a guarantee that interface creation or channel tuning will succeed at runtime.
- Some OEM drivers do not support concurrent managed and monitor interfaces. Starting monitor mode may interrupt Wi-Fi.
- Android/vendor policy, regulatory domain, firmware, and root-manager behavior can affect results.
- Cleanup is best-effort. If the device does not recover automatically, stop the app and restore Wi-Fi from Android settings; do not assume a successful command means the radio is fully restored.
- Channel numbers are derived from the frequency data exposed by the driver or Android scan API. They are not a promise that every channel is legal or usable in every region.

## Safety and privacy

- The monitor workflow is intended for **passive reception and diagnosis**. Use it only on devices and networks you own or are authorized to test.
- Switching channels or bringing an interface down can disconnect the phone from its current network.
- Reports can contain nearby SSIDs, BSSIDs, signal levels, device model, Android version, and command output. Review a report before sharing it.
- The legacy interactive deauthentication screens have been removed from the app flow. Legacy backend code is still being audited for safe removal; do not assume every historical class or resource has already been deleted.

## Troubleshooting

- **Root unavailable:** open the root manager and verify that WifiKiller is authorized, then tap the Root card to retry.
- **No interfaces or channels:** confirm that `iw` works under Root and inspect the exported diagnostic report.
- **Monitor startup failed:** review the command output. The driver may reject monitor interface creation, concurrent interfaces, or the requested frequency.
- **Wi-Fi does not return:** use Android settings to toggle Wi-Fi, then collect a diagnostic report before retrying.
- **No scan results:** grant location permission, enable the system Location toggle if required by the device, and retry after Android's scan-throttle window.

## Development status

CI validates compilation and APK packaging. It does **not** replace on-device testing of Root authorization, monitor-mode startup, channel tuning, capture output, stop/restore behavior, or report export. Please include the device model, Android build, driver/tool output, and diagnostic report (after reviewing it for private data) when reporting a bug.
