# AGENTS.md — Sonos Widget

## Project Overview

Android home screen widget for controlling Sonos speakers. Built with **Jetpack Glance** (widget UI), **Hilt** (DI), and local **UPnP/SOAP** control over port 1400. Supports cloud fallback via Sonos OAuth API.

**Package:** `com.sycamorecreek.sonoswidget`
**SDK:** compileSdk 36, minSdk 35, targetSdk 36 (Android 16)
**Kotlin:** Built into AGP 9.4, JVM target 17

## Build

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The Gradle daemon is pinned to Eclipse Temurin 21 and is provisioned automatically
on a clean machine; application bytecode continues to target Java 17.

APK output: `app/build/outputs/apk/debug/app-debug.apk`

## Architecture

### Layers

```
widget/          UI layer (Glance composables, state model, actions)
service/         Bridge layer (polling, state mapping, album art, theme)
data/            Repository (single source of truth, connection management,
                 widget state publication)
sonos/local/     UPnP/SOAP transport (SSDP, mDNS, SOAP client)
sonos/cloud/     Sonos Cloud REST API (OAuth, cloud controller)
app/             Entry point (Application, CompanionActivity)
```

### Key Files

| File | Purpose |
|------|---------|
| `SonosRepository.kt` | Central orchestrator. Discovery, polling, command routing, zone management. ~2100 lines. |
| `WidgetStatePublisher.kt` | The only path that publishes visible state. Serializes writes, merges narrow field patches, enforces revision and ownership rules. |
| `WidgetStateRevisionPolicy.kt` | Per-field revision ledger backing the publisher: snapshots, ownership claims, staleness checks. |
| `WidgetLayoutPolicy.kt` | Size-bucket thresholds and `bucketFor()`, shared by the widget and its tests. |
| `SonosControlActions.kt` | SOAP action builders and XML parsers (transport, volume, queue, zone groups). |
| `SonosSoapClient.kt` | Low-level HTTP POST of SOAP envelopes to speakers on port 1400. |
| `PlaybackService.kt` | Foreground service driving the poll loop with adaptive intervals. |
| `WidgetStateStore.kt` | Serializes `SonosWidgetState` to JSON and pushes to Glance preferences. |
| `WidgetStateMapper.kt` | Pure functions mapping SOAP responses to widget domain model. |
| `SonosWidget.kt` | Glance widget class. Responsive size mode with 3 buckets (Mini/Compact/Full). |
| `ExpandedLayout.kt` | Full 6x5 layout: album art, controls, volume, speakers, queue. |
| `CompactLayout.kt` | Compact 4x2 layout: small art, track info, basic controls. |
| `MiniLayout.kt` | Mini ~240x80 layout (lock screen): art thumbnail, title/artist, play + next. |
| `WidgetTheme.kt` | "Glass on art" design tokens: white-alpha text/surfaces, palette accent resolution. |
| `GlassComponents.kt` | Shared Glance building blocks: `ImmersiveSurface`, icon buttons, chips, section headers. |
| `WidgetBackgroundRenderer.kt` | Renders the blurred/dimmed album-art widget background, cached to disk like album art. |
| `AlbumArtLoader.kt` | Downloads album art via Coil, caches to internal storage as WebP. |
| `SonosWidgetState.kt` | Data classes for widget state (`SonosWidgetState`, `Track`, `Zone`, `QueueItem`, etc.). |
| `WidgetActions.kt` | Glance `ActionCallback` implementations for play/pause, skip, volume, grouping, etc. |

### Connection Flow

```
discoverAndConnect()
  ├─ trySavedSpeaker()       Unicast probe of last-known IP (fast path; immune to multicast flakiness)
  ├─ tryLocalDiscovery()     SSDP + mDNS → findBestCoordinator() → pick PLAYING speaker
  ├─ tryManualIps()          User-configured IPs → coordinator redirect
  └─ tryCloudFallback()      Sonos Cloud REST API (OAuth required)
```

### Polling Loop (PlaybackService)

```
pollOnce() → repository.pollAndUpdate() → pollLocal() or pollCloud()
  Intervals: PLAYING=2s, PAUSED=4s, STOPPED=15s
  DISCONNECTED: exponential 30s → capped at 60s on Wi-Fi, 300s off Wi-Fi
  STOPPED re-scan: After 30s STOPPED, probes all coordinators for a PLAYING one
  Idle teardown (5 min) → WidgetRefreshWorker (15 min periodic)
                        + WifiReconnectWorker (fires when Wi-Fi returns)
```

### Widget Size Buckets

```
MINI_SIZE  = 240×80dp   → MiniLayout (lock screen)
HALF_SIZE  = 320×180dp  → CompactLayout (4x2)
FULL_SIZE  = 400×460dp  → ExpandedLayout (only when the host can fit its player budget)
```

Android widget dp formula: `(cellCount × 73) - 16`. A 6x5 grid ≈ 422×349dp.

## Sonos Protocol Notes

- **SOAP over HTTP** on port 1400 (cleartext, allowed via `network_security_config.xml`)
- **Services:** AVTransport (playback), RenderingControl (volume), ContentDirectory (queue), ZoneGroupTopology (speakers)
- **Zone Groups:** XML from `GetZoneGroupState`. Each group has a `Coordinator` UUID. Only coordinators accept AVTransport commands; satellites return HTTP 500.
- **Surround sound:** `<ZoneGroupMember>` tags can contain `<Satellite>` children (non-self-closing), handled by the zone group regex parser.
- **Album art:** Relative URLs like `/getaa?s=1&u=...` resolved against `http://{speakerIp}:1400/`
- **DIDL-Lite:** Track metadata is double-XML-encoded inside SOAP responses. Entity decoding (`&amp;` → `&`, `&lt;` → `<`) required before parsing.

## State Persistence

Widget state flows: `SonosRepository` → `WidgetStateMapper` → `WidgetStatePublisher` →
`WidgetStateStore.pushState()` → Glance `updateAppWidgetState()` → `SonosWidget.provideContent()`.

`WidgetStatePublisher` is the only path that publishes visible state: it
serializes the StateFlow update and the Glance write under one lock, applies
narrow field patches instead of republishing old snapshots, and enforces field
revisions and operation ownership so a slow poll or an old failure cannot
overwrite newer user intent. Pending operations are exempt from polling. State
is stamped with a per-process session id, and the widget renders through
`deserializeForDisplay`, which drops operations left behind by a dead process.
See CLAUDE.md for the full rules.

**Critical:** Glance's `updateAppWidgetState` lambda receives `MutablePreferences` that must be modified **in-place**. Do NOT call `.toMutablePreferences()` — that creates a discarded copy.

## Common Pitfalls

1. **Glance Column limit:** Max 10 direct children. Use nested `Column` wrappers to group sections.
2. **SOAP to satellites:** AVTransport commands (GetTransportInfo, Play, Pause) fail with HTTP 500 on satellite/surround speakers. Always route through the group coordinator.
3. **Zone group XML:** The `<ZoneGroupMember>` regex must handle both self-closing (`/>`) and non-self-closing (`>...</ZoneGroupMember>`) tags for surround sound setups.
4. **Album art URLs:** Double-encoded XML entities in DIDL-Lite (`&amp;amp;` after SOAP + DIDL decoding). The `decodeXmlEntities()` in `extractDidlValue()` handles this.
5. **Widget size thresholds:** Must match real Android dp dimensions, not desired pixel sizes. Use the formula `(cells × 73) - 16`.
6. **Publishing widget state:** Never assign `_widgetState.value` or call `WidgetStateStore.pushState` directly — both belong to `WidgetStatePublisher`. Routing around it is how `pollCloud` ended up able to overwrite newer intent and erase in-flight requests.
7. **Testing anything stateful:** `SonosRepository` needs a `Context`, `android.util.Log` and Hilt, so it cannot be constructed in a JVM unit test. Put logic behind a plain seam and test that; there is no Robolectric or instrumentation suite.

## Dependencies (Key)

- Build: AGP 9.4.0 (built-in Kotlin), Gradle 9.6.0, KSP 2.3.11, Compose compiler 2.3.21
- Glance 1.2.0-rc01, Hilt 2.60.1, OkHttp 4.12.0
- Coil 2.7.0, Coroutines 1.9.0, DataStore 1.1.1, WorkManager 2.10.0
- Tests: JUnit 4.13.2, `org.json`, and `kotlinx-coroutines-core` for the
  publication-ordering tests
- There is no Retrofit or kotlinx.serialization here: both the local SOAP
  transport and the cloud REST client use raw OkHttp with `org.json`
- Full list in `gradle/libs.versions.toml`

## OAuth Setup

Sonos Cloud API requires OAuth credentials in `local.properties`:
```properties
SONOS_CLIENT_ID=your_client_id
SONOS_CLIENT_SECRET=your_client_secret
```
Redirect URI configured at: `https://sycamorecreekconsulting.com/sonos/callback`
