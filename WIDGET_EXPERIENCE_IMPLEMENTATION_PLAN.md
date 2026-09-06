# Sonos Widget experience improvement plan

Status: implementation in progress. The code-side phases below have been applied
in reviewable batches; device and household validation remains open.
Prepared: 2026-09-05.

## Implementation checkpoint (2026-09-05)

Completed in code and unit tests:

- Versioned room-follow migration, explicit room behavior, per-widget disclosure
  preferences, and shared-target switching feedback.
- Explicit operation, freshness, capability, and artwork-version state contracts;
  backward-compatible persistence that drops transient operations on restart.
- Command reconciliation and refresh coalescing, capability enforcement, bounded
  artwork caching, and a foreground-service fallback for disallowed starts.
- Mini/compact/expanded layout policy boundary tests, corrected advertised minimum
  size, bounded widget room lists, and a focused companion room chooser for
  overflow.
- Fixture coverage for representative playback, connection, capability, error,
  and long-content states. `testDebugUnitTest`, `lintDebug`, and `assembleDebug`
  have passed during implementation.

Still requires a real device/emulator and, where applicable, a Sonos household:

- Launcher screenshots and touch/overflow checks at boundary sizes, font scales,
  and bright/dark artwork.
- Local coordinator, surround, grouping, cloud, permission, network recovery,
  process-death, and idle lifecycle scenarios.
- Measured interaction/recovery timings and final before/after evidence. The
  current log timing is instrumentation only; it is not launcher-visible latency
  proof.

Observed emulator evidence (API 36.1, Pixel Launcher, default font scale):

- The picker recognizes the 3×1 provider preview, and the placed Mini widget
  renders its searching and settled offline states without clipped primary
  controls. An initially observed offline badge/art overlap was corrected by
  keeping Mini status in its dedicated text area rather than duplicating a badge.
- Resizing the placed widget produced the Compact offline state with readable
  controls and recovery copy. Its similarly redundant badge was removed after
  observation so the artwork/device affordance remains unobscured. This
  emulator's four-column launcher grid could not reach the 400dp width required
  to exercise the Expanded bucket.
- At 1.3× system font scale, the Compact offline state remained readable with
  no clipped controls or recovery copy. The scale was restored to 1.0× after
  the check. At 2.0×, primary controls still fit and do not overlap, but the
  long offline subtitle and recovery copy ellipsize. Their essential meaning
  remains visible; this is a recorded readability limitation. The scale was
  restored to 1.0× after the check.
- A reversible 360dpi emulator override provided enough logical width to render
  the Expanded offline bucket. The placed widget showed the reconnect affordance,
  unobscured artwork/device placeholder, transport/volume region, and bounded
  Up Next empty state without overlap. The emulator density was restored to its
  original 420dpi afterward.
- Reinstalling from a background widget update produced Android 16's expected
  foreground-service-start denial in logs without an application crash; it is
  not evidence of full background recovery timing and that lifecycle scenario
  remains open.
- With neither Sonos package installed, tapping widget artwork opened a focused
  companion explanation with explicit Get Sonos and Back actions. The focused
  route now defers the unrelated Nearby Devices permission prompt so those
  actions are not obscured. The Back button was visually verified. A fresh
  behavioral recheck after reboot was blocked by a recurring emulator System
  UI ANR; its return-to-launcher behavior remains a manual-device validation
  item rather than a passed emulator check.
- A forced app stop temporarily replaced the widget RemoteViews with Android's
  default provider placeholder. Restarting the companion repopulated the cached
  offline widget state safely. This is a force-stop observation, not a complete
  ordinary process-death or background-recovery validation.
- The companion offline/setup screen renders without an application crash. The
  emulator briefly reported an unrelated System UI ANR; it was dismissed before
  the app and widget checks. This does not validate connected playback, room
  lists, alternate widget sizes, font scaling, or real Sonos behavior.

## Objective

Deliver a calmer, sharper widget with predictable room targeting, comfortable controls, immediate interaction feedback, and truthful recovery states. Preserve local Sonos control and cloud fallback. Build on the existing Glance layouts and optimistic playback/volume updates.

The preceding review was based on source inspection. No Android device was connected. Layout overflow, latency, concurrency failures, and contrast must be reproduced or measured; do not describe inferred problems as device-confirmed defects.

Read `AGENTS.md` before implementation. Paths below are relative to the repository; Kotlin paths are under `app/src/main/java/com/sycamorecreek/sonoswidget/` unless otherwise specified.

## Scope and product defaults

Implement phases 0–7 in order, as independently reviewable changes. Finish each phase's checks before proceeding. Do not turn this into a framework migration or a wholesale repository rewrite.

Adopt these defaults so implementation can proceed without routine clarification:

- Keep the dark, artwork-derived appearance, with quieter backgrounds and fewer filled surfaces.
- Keep transport and volume in stable positions. Status changes must not insert rows above them.
- Compact and expanded widgets expose a room selector and volume. Mini always identifies the room; omit next before sacrificing readable metadata or touch targets.
- Expanded shows one bounded secondary section: Up next by default when available, Favorites when idle and available, otherwise a useful empty state. Explicit user section selection persists per widget.
- Room selection and grouping are distinct. Use a room selector and a separate “Play in…” grouping surface. Group edits use selection plus Apply; Back/Cancel leaves playback unchanged.
- New installations default to staying with the selected room. Existing users retain current automatic-follow behavior unless they have a saved default room, which migrates to stay-with-room. Expose the choice in companion settings and document the migration.
- Retain the shared playback target across widget instances in this release. Panel selection and disclosure state are per widget. Independently pinned room widgets are a follow-up requiring keyed state, command routing, and artwork caches.
- Do not automatically replay ambiguous or non-idempotent commands after reconnection. Offer reconnection explicitly; a recovered user can issue a fresh command.
- Keep continuous animation, draggable widget sliders, new SDK requirements, UPnP event subscriptions, and independent per-widget room sessions out of the initial implementation. Evaluate eventing only after lifecycle measurements.

## Coding-team decisions (2026-09-05)

These decisions clarify and take precedence over less specific wording elsewhere in this plan.

1. **Versioned migration.** Add `experience_preferences_version` (initial new version 1) and an explicit `room_follow_mode`. Run migration before discovery, default initialization, or any new preference writes. A nonblank legacy `default_zone_id` means a currently saved default: initialize Stay with room and that target. A cached `active_zone_id` is not evidence of a user-selected default. Legacy code deletes default keys when the default is cleared; it cannot distinguish never-selected from selected-then-cleared, and this release does not need that distinction: both migrate to Follow playing music when legacy-install evidence exists. Capture legacy evidence before initialization: existing legacy Sonos preferences or pre-existing persisted widget playback state. With no such evidence, initialize the new-install Stay with room default, selecting its target during setup. This is a documented fallback for indistinguishable empty legacy installs, not a claim of perfect installation-history detection. Persist mode, applicable target, and version atomically in the preferences transaction; snapshot external widget evidence beforehand. Preserve an already explicit mode if present. Make migration idempotent and never recompute mode on later launches, discovery, or widget addition. On a read error, retry rather than treating the store as empty and marking migration complete. Test saved default, cleared/absent default with legacy evidence, empty storage, repeated migration, and interrupted/read-failed migration.

2. **Immediate shared-target publication.** Selecting a room changes the one shared target for all widgets. Immediately request updates for every instance with the new room identity and a Switching room state; do not wait for each instance's next polling cycle. Do not label the old room's track as the new room's playback: use correctly keyed cached content or a neutral loading state. A single successful essential refresh then publishes confirmed content to all instances. Launcher rendering is asynchronous, so simultaneous pixels are not guaranteed. Reject stale callbacks whose captured target generation no longer matches; refresh their widget rather than accidentally sending an old-screen action to the new room. Preserve per-widget section selection, but invalidate any open grouping draft tied to the previous target. On switch failure, publish one consistent recovery state to all instances; rollback only if that switch still owns the latest intent.

3. **Per-widget identity and cleanup.** Scope panel/section preferences to the widget instance. Prefer the existing Glance per-instance preference store accessed with its `GlanceId`; the underlying Android identity is `appWidgetId`. If a separate map is necessary, key it by `appWidgetId`, not room ID, and do not duplicate the same preferences in both stores. On individual widget deletion, clean up any custom instance state and preserve Glance's lifecycle cleanup. Do not wait until the last widget is removed. Deleting one instance must not clear the shared target or another widget's preferences. Handle restored/remapped IDs without attaching an old instance's state to an unrelated widget; migrate the mapping or use safe panel defaults. Test deletion and recreation with two widgets.

4. **Minimum supported size.** Raise `minResizeWidth` and `minResizeHeight` to Mini's validated real requirements. The starting floor is 240×80dp, with permission to raise it if 48dp actions and readable content do not fit. Align initial minimum dimensions and offered size buckets as well. There is no deliberately supported 180×48dp feature-complete layout. Existing undersized placements or a launcher ignoring constraints must still render defensively: prefer a minimal recognizable room/play surface or an actionable resize/open-companion prompt, without crashing or overlapping controls. That compatibility fallback does not expand the advertised size support.

5. **Grouping in cloud mode.** “Play in…” is available only through an operational local grouping capability this release. Hide the grouping entry in cloud-only widget mode; do not leave a dead/disabled chip occupying space. Cloud room selection remains available if selecting an existing cloud group is supported. In companion settings/help, explain on demand: “Connect to your speakers’ Wi-Fi to change groups here.” If local access disappears while the grouping editor is open, preserve the draft for inspection, disable Apply with that explanation, and require fresh topology/capability validation before applying after reconnection. Never submit the stale draft automatically.

6. **Open Sonos fallback.** Preserve the existing package-launch preference: try `com.sonos.acr2`, then `com.sonos.acr`. Use a normal supported app launch; no undocumented room/track deep link is required. Ensure package visibility and widget activity-launch behavior work for the target SDK. If neither app can be launched, open a focused companion explanation with explicit “Get Sonos” and Back actions. Only “Get Sonos” opens the existing store listing, with web fallback; artwork taps must not unexpectedly redirect to a store. If store/browser launch also fails, retain an actionable explanation. Use the same behavior from widget and companion, and test both packages available, each individually, neither, and launch failure.

7. **Visual reference.** The coding team's seventh question was truncated. For the apparent visual-direction question, use the current dark, artwork-derived widget as the starting reference; no external product clone or unseen mockup is implied. Preserve recognizable artwork and the white primary play control; reduce saturation and competing glass fills, improve typography and spacing, and follow the new hierarchy. In phase 4, produce representative rendered mini/compact/expanded states (playing and idle, bright and dark covers) early, then refine against the acceptance criteria. No additional design approval gate is required for routine choices within this direction. If the missing part of the question specifies another constraint, incorporate it explicitly rather than assuming it was answered here.

## Current implementation facts to preserve or address

| Area | Current evidence | Required outcome |
|---|---|---|
| Layout | `SonosWidget.kt` offers 240×80, 320×180, 400×340dp. Expanded fixed content exceeds its smallest height before secondary sections. | Every offered size fits, including error and selection states. |
| Minimum resize | `res/xml/sonos_widget_info.xml` permits 180×48dp, smaller than the smallest designed layout. | Metadata and layouts describe the same supported range. |
| Commands | `SonosRepository.routeCommand()` includes reconciliation in a three-second timeout. | A slow refresh cannot mark an acknowledged command failed. |
| Optimism | Playback/volume overrides exist with a two-second hold. | Preserve immediate feedback and prevent stale rollback. |
| Loading | `playFavorite()` uses firmware `isUpdating`. | Separate content loading and actual device updating. |
| Errors | `InlineErrorBanner` says tap to retry but is not clickable. | Actions and copy agree. |
| Refresh | `pollLocal()` waits for secondary data and images before publishing. | Essential state publishes independently of enrichment. |
| Images | Art is requested at 240px, displayed at up to 128dp; cached globally. | Sharp images, no wrong-track artwork after failures/races. |
| Capabilities | Cloud seek/mute return false; layout gating is mostly connection-wide. | Unsupported operations are not offered. |
| Lifecycle | Polling: playing 2s, paused 4s, stopped 15s; idle teardown after 5m with 15m periodic fallback. | Measure and improve recovery without blindly polling faster. |

## Phase 0 — Establish a reproducible baseline

Files: build scripts, existing widget/service files; add fixtures and test support as needed.

1. Inspect working-tree changes and preserve them. Re-read current implementation rather than relying on old comments: `AGENTS.md` and some source comments have outdated descriptions.
2. Build the existing debug app. Record any pre-existing failures separately.
3. Add deterministic fixture states: music playing/paused, TV, radio/unknown duration, idle, disconnected with cached metadata, no permission, cloud, loading favorite, failed command, long names, many rooms/favorites, missing and bright artwork.
4. Capture baseline launcher screenshots if a device/emulator is available. Record actual widget bounds, density, font scale, launcher and OS. A Compose-only mock is not proof of Glance rendering.
5. Add debug timing around callback receipt, optimistic publication request, command dispatch/acknowledgment, essential refresh, enrichment and widget update completion. Use a monotonic clock. Avoid credentials or unnecessary household/media data in logs. Widget update completion is not proof of visible launcher rendering.

Acceptance: baseline build status and reproduction matrix recorded; source-inferred findings distinguished from observed defects.

## Phase 1 — Explicit state and capability contracts

Files: `widget/SonosWidgetState.kt`, `service/WidgetStateStore.kt`, `service/WidgetStateMapper.kt`, `data/SonosRepository.kt`, `widget/StatusBadge.kt`. Suggested additions: pure capability mapper and operation-state types.

1. Represent connection status, playback status, and pending user operations separately. Operation records need an ID, target room/group, affected field/action, start time and phase. Multiple independent controls must not overwrite one global loading flag.
2. Distinguish loading favorite, switching room, applying grouping, reconnecting, and actual firmware update. A transient `TRANSITIONING` response without metadata is insufficient evidence of firmware installation; display a neutral preparing state unless stronger evidence exists.
3. Add capabilities for play/pause, previous/next, seek, mute, volume, shuffle/repeat, queue, favorites and grouping. Derive them from transport/source support and available metadata; do not assume all radio supports identical actions or duration alone proves seekability.
4. Enforce capability rules in action handling as well as layouts, since an old rendered widget can still dispatch a stale action.
5. Preserve last-known content with explicit freshness. Store last successful essential refresh separately from local UI changes, so optimism does not make stale device data look fresh.
6. Make state deserialization backward compatible with missing fields and unknown enum values. Do not restore transient pending operations as active after process death; refresh to reconcile instead.

Acceptance: fixture/unit tests cover source/connection capability combinations, legacy JSON, unknown values, and loading-favorite copy. No firmware message for ordinary content loading.

## Phase 2 — Reliable commands and reconciliation

Files: `data/SonosRepository.kt`, `data/ActionDebouncer.kt`, `widget/WidgetActions.kt`, service refresh entry points. Suggested addition: a small command coordinator with injectable transport and clock.

1. Separate command execution from reconciliation. The network command has an operation-appropriate deadline; success schedules refresh outside that deadline. Playlist loading must not inherit an unsuitable universal three-second limit.
2. Represent acknowledged success, definite failure and unknown outcome separately. A network timeout can happen after a speaker acted. Reconcile unknown outcomes before offering replay of next, favorite playback or grouping.
3. Serialize state mutation and order commands per destination. Do not hold a broad state mutex while fetching artwork or running a full poll. Capture destination and a connection generation when dispatching.
4. Use per-field revisions/operation IDs to protect optimism: older polls and failures cannot replace newer intent. Room switches invalidate old destination responses. Keep confirmed state separate from requested state for field-specific rollback.
5. Accumulate rapid volume intent and coalesce unsent absolute targets, clamped to 0–100. Do not drop intended deltas. Maintain ordering for skips; resolve play/pause and mute taps against latest intent.
6. Publish pending/optimistic mute, shuffle and repeat immediately. Invalidate affected slow caches after acknowledgment. Do not pretend a skip changed track metadata before it is known; show skipping feedback.
7. Replace misleading retry text with real actions. Retry must bind to the specific failed operation and original destination and expire/invalidate when context changes. For unknown non-idempotent results, use Refresh/Check status rather than blind replay.
8. Simplify reconnection behavior consistently across callbacks and layouts: a visible Reconnect action wakes recovery; disconnected transport controls do not silently queue taps. Remove or retire unreachable debounce behavior carefully.

Required behavioral tests using fakes and a controllable clock:

- Command acknowledged quickly; enrichment delayed beyond three seconds: no failure or rollback.
- Five rapid volume-up taps from 50 produce intent 75 and converge to 75.
- Older command failure arriving after newer success cannot undo it.
- Poll for room A finishes after switching to B: B remains active.
- Unknown skip outcome never automatically issues a second skip.
- Definite mute failure restores confirmed mute without reverting unrelated fields.
- Repeated callback/service refresh requests do not create uncontrolled overlapping polls.

Acceptance: tests above pass; actionable errors preserve context; coroutine cancellation propagates correctly; command outcomes are independent of artwork availability.

## Phase 3 — Fast essential updates and coherent artwork

Files: repository polling, `service/AlbumArtLoader.kt`, `service/WidgetBackgroundRenderer.kt`, `service/ThemeExtractor.kt`, `service/WidgetStateStore.kt`, `widget/SonosWidget.kt`.

1. Split essential refresh (transport, track identity/position, applicable volume/mute) from queue, topology, favorites, offline probes and artwork enrichment. Retain cached secondary content while refreshing it, explicitly scoped to its destination.
2. Deduplicate concurrent refresh requests. Capture room/connection generation and media identity for every refresh; discard obsolete results before publication.
3. Use robust media identity rather than only queue track number. Source or track URI can change without the track number changing. Invalidate queue, source and artwork correctly on room/source changes.
4. Key artwork by resolved URL/destination and media identity as appropriate. Increase fetch size for largest supported art and density with a bounded memory budget. Keep foreground art sharp and background rendering small.
5. Publish art and background as a matching version referenced by state. Write complete assets atomically; avoid partially read shared files. Late artwork for the old song must not replace the new song. Failed new artwork should use a deliberate placeholder, not silently retain a different cover.
6. Reuse decoded art where valid; current foreground art reads decode the disk file during composition. Retain the existing background cache and avoid repeated palette work.
7. Coalesce redundant widget publications; compare render-relevant state instead of timestamps alone. Keep user-action updates immediate. Give queue items stable IDs to maintain scroll position.

Acceptance: slow or failed image loads do not delay essential updates; rapid room/track changes never mix title, art and background; settled unchanged polls do not repeatedly perform image work. Record timing before/after.

## Phase 4 — Rebuild hierarchy within real widget bounds

Files: `widget/SonosWidget.kt`, `MiniLayout.kt`, `CompactLayout.kt`, `ExpandedLayout.kt`, `GlassComponents.kt`, `WidgetTheme.kt`, `StatusBadge.kt`, `res/xml/sonos_widget_info.xml`, preview/loading resources.

1. Create a pure layout policy from supported width/height and content priorities. Keep `SizeMode.Responsive` unless actual evidence requires otherwise. Android chooses a best-fitting offered size; `LocalSize` is not automatically the exact host bounds in this mode.
2. Budget minimum dimensions for every row before assigning size buckets. Include 48dp interaction targets, font scaling, outer padding and status text. Increase the minimum supported height/width when necessary instead of advertising impossible 180×48dp content.
3. Mini: readable track and room label, play/pause; next and artwork are conditional on width. Artist is lower priority than destination identity.
4. Compact: room selector, art/track, transport and volume. Remove the inline grouping chip grid. Omit secondary controls before shrinking primary touch targets.
5. Expanded: stable player region plus one bounded secondary area. Tabs/section controls select Up next or Favorites; room selection and “Play in…” use the same bounded area with a clear Back action. Long room lists scroll inside that area.
6. If compact cannot contain a usable selector, open a focused companion activity surface via supported activity-launch actions; do not expand inline beyond bounds. Wire destination and widget context explicitly.
7. Show group summary and label group volume clearly. Room selection never mutates topology. Group editing presents selection and Apply, tracks partial failures and refreshes actual membership rather than presenting an all-or-nothing success.
8. Adapt transport to capabilities. TV prioritizes mute/volume; unknown-duration audio has no fake progress/seek; idle offers available favorites or Open Sonos; unsupported cloud controls are absent.
9. Simplify colors and typography: primary track, secondary artist/room, tertiary metadata. Use normal-case labels, consistent spacing/radii, quieter surfaces, and selected states that do not rely only on color.
10. Reserve an existing subtitle/status area for feedback. Add accessible descriptions for pending, selected, disabled and expanded states. Do not hard-code “double tap” into ordinary action descriptions; accessibility services announce interaction instructions.
11. Update widget picker preview and initial loading layout to match the delivered design. Verify existing widget instances survive new preferences and revised bounds.

Acceptance: screenshots of every offered bucket and boundary sizes show no clipped primary controls, overlapping text, inaccessible panels or zero-height required content. Test font scale 1.0, 1.3 and 2.0, long names, empty content and error states. At least 48dp touch targets for actions; normal text contrast at least 4.5:1, large text 3:1, tested on rendered bright/dark artwork. Do not claim compliance from token colors alone.

## Phase 5 — Predictable room following and simpler companion flow

Files: `data/SonosPreferences.kt`, room selection/discovery in repository, `app/SonosCompanionActivity.kt`, relevant widget actions.

1. Implement explicit Stay with room / Follow playing music preference and migration described above. Manual selection in stay mode remains selected while stopped. If unavailable, show recovery; do not silently control another room.
2. Define deterministic selection in follow mode when several rooms play: retain a playing current room; only choose another eligible playing coordinator when the policy allows it. Keep grouped member versus coordinator identity understandable to the user.
3. Persist the selected room identity, not just its last IP; re-resolve topology after discovery. Invalidate pending commands when target identity becomes invalid.
4. Reorganize companion UI around connection/setup, chosen room behavior, widget addition and normal preferences. Move manual IPs, cache maintenance and discovery-method details into troubleshooting.
5. Provide working permission/settings and Add widget actions where supported, with usable manual fallback. Keep Sonos sign-in clearly identified as cloud fallback and preserve existing authentication behavior.
6. Reuse focused room/grouping surfaces for compact navigation, with a direct return to the home screen. Preserve access to Open Sonos from artwork with an accurate accessibility label.

Acceptance: first-run setup works without understanding SSDP/IPs; existing preference migration tested; stopped selected room never changes in stay mode; opening two widget instances preserves separate panel state while sharing the documented playback target.

## Phase 6 — Idle recovery and lifecycle measurement

Files: `service/PlaybackService.kt`, `NetworkChangeReceiver.kt`, `WidgetRefreshWorker.kt`, `WifiReconnectWorker.kt`, `widget/SonosWidgetReceiver.kt`.

1. Trace callback and service ownership through idle teardown and process death. A process-local network callback cannot be assumed to wake a dead process.
2. Route user actions, explicit reconnect, companion resume and supported network recovery into one deduplicated refresh entry point. A refresh request must wake/reset a sleeping loop rather than wait for the previous backoff interval.
3. Audit foreground-service starts/stops against the existing SDK target and current Android documentation. Handle disallowed starts without crashing or presenting false connected state.
4. Retain a battery-aware periodic fallback. WorkManager periodic requests are inexact; do not promise a fifteen-minute deadline or real-time external playback detection after teardown.
5. Measure active playback, paused, long idle, screen off, returning to Wi-Fi, process restart and externally started playback. Report stale-display duration and background work alongside response latency.
6. If prompt external-change recovery cannot be achieved with supported lifecycle triggers, record the measured limitation and a follow-up proposal for eventing/service policy. Do not silently add permanent high-frequency polling or unsupported background starts.

Acceptance: no duplicate loops; explicit user recovery initiates promptly; no stale command replay after process death; final report documents recovery latency and any remaining idle limitation.

## Phase 7 — Release validation and handoff

The project now has a focused `app/src/test` suite covering state fixtures,
layout policy, state mapping/persistence, and room-follow migration. Expand it
with fake transports and an injected clock for command/reconciliation behavior;
use instrumentation or an appropriate Android test environment for Android
JSON/Glance behavior rather than relying on JVM Android stubs. The current
debug unit suite has 14 passing tests; that is not a substitute for the
remaining device, Sonos, and lifecycle scenarios below.

Run from PowerShell at repository root:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

If instrumentation tests are added, run `connectedDebugAndroidTest` on an available device/emulator. APK: `app/build/outputs/apk/debug/app-debug.apk`. Separate pre-existing failures from new regressions; do not assert device validation when no device is available.

Minimum end-to-end matrix:

| Scenario | Verify |
|---|---|
| Local single room, grouped rooms, surrounds | Correct coordinator routing; volume scope; no satellite AVTransport commands |
| Cloud, TV, radio, queue music | Accurate capabilities and useful empty states |
| Rapid volume/play/skip taps | No lost intent, stale rollback or duplicated ambiguous action |
| Favorite loading, grouping partial failure | Truthful localized progress and recovery |
| Artwork unavailable or late | Correct title/art association and no blocked controls |
| Resize and font scaling | Fit, legibility, stable actions, reachable secondary sections |
| Permission denied, Wi-Fi lost/restored | Actionable status, no crashes, correct reconnect behavior |
| Process death, idle teardown, multiple widgets | Safe persistence, honest freshness, correct panel isolation |

Performance objectives are proposed validation targets, not existing guarantees: pending state publication requested within 100ms of callback entry; visible feedback p95 under 300ms on the recorded reference launcher; essential reconciliation p95 under one second after acknowledgment on a healthy local network. Measure at least 30 representative actions and distinguish callback, publication, launcher and speaker latency. Record misses and their causes rather than masking them with optimistic state.

Final deliverables: code and focused regression tests; debug APK; before/after launcher captures; timing/recovery results with device details; migration notes; remaining limitations. Update `AGENTS.md` only for architecture/build facts that actually changed.

## Guardrails

- Mutate Glance `MutablePreferences` in place; never discard a `.toMutablePreferences()` copy.
- Preserve coordinator routing, surround parsing, DIDL decoding and relative artwork URL handling.
- Avoid exceeding Glance child limits in dynamic/permission states as well as normal states.
- Keep credentials in existing private configuration; do not include them in fixtures or reports.
- Do not upgrade Glance/SDK or introduce unsupported Compose widget components merely to reproduce a mockup.
- Do not claim UPnP eventing, independent room widgets or continuous progress animation is part of this release.

## Platform references

Consult current official documentation before adopting APIs; newer examples may exceed this project's Glance 1.2.0-rc01 / SDK 36 configuration.

- [Glance layouts, responsive sizing and stable list IDs](https://developer.android.com/develop/ui/compose/glance/build-ui)
- [Android widget design](https://developer.android.com/design/ui/mobile/guides/widgets)
- [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps)
- [Widget interaction constraints](https://developer.android.com/develop/ui/views/appwidgets/overview)

## Suggested implementation-agent starting instruction

“Implement `WIDGET_EXPERIENCE_IMPLEMENTATION_PLAN.md` in this repository. Read `AGENTS.md`, preserve existing user changes, and follow phases 0–7 with their tests and acceptance criteria. Use the documented product defaults. Make reviewable incremental changes, preserve local/cloud functionality, and report observed versus unverified behavior accurately. Do not stop after a cosmetic restyle; complete the state, command, layout and recovery work within the stated scope.”
