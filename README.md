# Sonos Widget

Android home screen widget for controlling Sonos speakers directly from your home screen.

## Features

- **Now Playing** — Album art, track name, artist, album with dynamic theme colors
- **Playback Controls** — Play/pause, skip, previous, shuffle, repeat
- **Volume Control** — Adjust volume with +/- buttons
- **Speaker Grouping** — View and manage speaker groups, group/ungroup rooms
- **Queue** — See upcoming tracks and tap to jump to any song
- **Smart Discovery** — Automatically finds and connects to the speaker that's currently playing
- **Auto-Switch** — If the connected speaker goes idle, automatically switches to one that's playing
- **Multiple Layouts** — Mini (lock screen), compact (4x2) and expanded layouts that adapt to widget size

## How It Works

The widget communicates with Sonos speakers over your local network using UPnP/SOAP (port 1400). No cloud account required for basic functionality. An optional Sonos Cloud API integration provides fallback connectivity.

### Connection Priority

1. **Saved speaker** — Unicast probe of the last known IP, so reconnection does not wait on discovery
2. **Local SSDP/mDNS** — Discovers speakers on your Wi-Fi network
3. **Manual IP** — User-configured speaker IPs (Settings → Manual IP)
4. **Cloud API** — Sonos REST API via OAuth (requires sign-in)

## Requirements

- Android 15+ (API 35)
- Sonos speakers on the same Wi-Fi network
- Location permission (required for local network discovery on Android 15+)

## Building

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Gradle daemon is pinned to Eclipse Temurin 21 by checked-in daemon JVM
criteria and is provisioned automatically on a clean machine, so no `JAVA_HOME`
override is needed — only a wrapper-supported Java to launch it. Application
bytecode continues to target Java 17.

APK output: `app/build/outputs/apk/debug/app-debug.apk`

For Sonos Cloud API support, add the public client id to `local.properties`:
```properties
SONOS_CLIENT_ID=your_client_id
```
The client secret is not part of the app. It lives in the website's Vercel
environment, where the token proxy (`/api/sonos/token`) adds it to token
requests.

No credentials are needed to build the debug variant.

## Tech Stack

- **Build:** AGP 9.4.0 with built-in Kotlin, Gradle 9.6.0, KSP 2.3.11
- **Widget UI:** Jetpack Glance 1.2.0-rc01
- **DI:** Hilt 2.60.1
- **Networking:** raw OkHttp 4.12.0 + `org.json` — SOAP/HTTP locally, REST for the cloud API
- **Image Loading:** Coil 2.7.0
- **Background:** Foreground Service with adaptive polling, WorkManager for idle recovery
- **State:** DataStore Preferences via Glance

## Project Structure

```
com.sycamorecreek.sonoswidget/
├── app/          Application entry point, companion activity
├── data/         Repository (single source of truth), widget state publication
├── service/      Polling service, state mapping, album art
├── sonos/
│   ├── local/    UPnP/SOAP (SSDP, mDNS, SOAP client)
│   └── cloud/    Sonos Cloud REST API (OAuth)
└── widget/       Glance layouts, state model, action callbacks
```

See [CLAUDE.md](CLAUDE.md) for detailed architecture and development notes, and
[WIDGET_EXPERIENCE_IMPLEMENTATION_PLAN.md](WIDGET_EXPERIENCE_IMPLEMENTATION_PLAN.md)
for the current delivery status of the widget experience work.
