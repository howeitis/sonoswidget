# Sonos Widget — complete coding-team handoff

Updated: 2026-09-06. Scope: finish the experience improvements, fix review findings,
upgrade AGP and related build plugins, and adopt Gradle daemon JVM criteria.

This document is the full handoff. The new work packages below take precedence
over earlier completion claims and build instructions. Retain working features;
do not restart the original implementation. Original product decisions, phase
details and device observations follow as continuing requirements and history.
This revision changes the plan only; it does not perform the build migration.

## Current review baseline

Review reference: `acac78c` (recheck HEAD and user changes before implementing).
Observed locally on 2026-09-06:

- `testDebugUnitTest`: 14 tests passed, covering migration, mapper, persistence,
  layout policy and fixtures. There are no repository command-ordering tests.
- `assembleDebug`: succeeded using installed JDK 21.
- `lintDebug`: FAILED with 6 errors and 94 warnings. The six errors are
  `UseAppTint` in `widget_loading.xml` and `widget_preview.xml`, introduced by
  the replacement of text symbols with ImageViews. Previous statements that
  lint passed are historical, not the current acceptance result.
- Android Studio's bundled JBR on this machine is JDK 25.0.2; the documented
  JAVA_HOME command fails before compilation with the existing Gradle setup.
- The runtime/concurrency findings below are source-grounded. They were not
  reproduced against a Sonos household. Earlier emulator observations mainly
  cover offline states, not populated connected playback.

## Delivery order and completion rules

1. **B1:** upgrade the compatible build stack and adopt daemon JVM criteria.
2. **B2:** establish a green lint/test/build gate in local builds and CI.
3. **R1–R5:** fix display state, targeting, reconciliation, outcomes and volume;
   add failing regression tests before the corresponding fixes.
4. **U1:** correct the expanded layout's minimum-size budget and validate it.
5. Complete the remaining original phase acceptance criteria and publish evidence.

### Package status — 2026-09-07

| Package | Status |
|---|---|
| B1 build stack / daemon criteria | Complete (`73e92b5`), see the record below |
| B2 lint and CI gates | Complete, 0 errors / 88 warnings |
| R1 pending state and lifecycle | Complete, see the R1 completion record |
| R2 action and draft targeting | Implemented (`08b30a4`); acceptance tests not yet written |
| R3 revision ordering and ownership | Complete and CI-validated, see the R3 completion record |
| R4 transport outcomes | Implemented (`aee37da`); transport-boundary tests are thin |
| R5 volume intent coalescing | Implemented (`f5f1c8f`); `VolumeIntentPolicyTest` covers convergence, clamping and room scoping as pure policy. What is unproven is the lock scope itself — that a tap arriving during a slow reconcile is not made to wait — which needs the intent bookkeeping behind a seam like `WidgetStatePublisher`'s |
| U1 expanded minimum size | Bucket raised (`74839ad`); render-based validation still outstanding |

`main` was red from `7e600d1` until `707d796`; treat any completion claim made
between those commits as unverified, because nothing compiled. The next work is
R2, R4, R5 and U1 acceptance evidence, in that order of cheapness.

Note for whoever picks this up in a cloud session: this environment's egress
policy blocks Google's Maven and SDK hosts, so no Android build can run there.
CI is the only gate available — push early and read the run rather than
reporting a local result that was never produced.

Use separate reviewable commits for build migration, lint/CI, reliability, and
layout. No plugin upgrade or UI screenshot establishes that runtime reliability
is complete. Do not mark a package done without its acceptance evidence. Keep
the existing SDK levels (compile/target 36, minimum 35) and Glance version unless
a concrete migration dependency requires a separately documented change.

## B1/B2 completion record — 2026-09-07

**B1 is implemented in `73e92b5` (`Modernize Android build toolchain`).** The
resolved fixed versions are AGP 9.4.0, Gradle 9.6.0 (with regenerated wrapper
scripts/JAR and official checksum), Compose compiler 2.3.21, KSP 2.3.11 and
Hilt 2.60.1. The redundant Kotlin Android plugin was removed in favour of AGP
built-in Kotlin. Java source/target and Kotlin bytecode remain 17. Checked-in
daemon criteria require Adoptium Java 21 and contain generated resolver URLs,
not workstation paths.

Windows verification used a JetBrains Java 21 launcher with an isolated,
writable Gradle user home. The criteria provisioned and actually ran the daemon
as Eclipse Adoptium 21.0.12.1; this verifies that criteria override a different
launcher vendor without modifying a developer cache or global Java settings.
The local gate `testDebugUnitTest lintDebug assembleDebug` passed in 9m 11s.
Android Studio AI-261.26222.65.2613.16025427 (2026.1.3, Java 21) is installed,
but an interactive IDE-sync check was not run: this automation environment does
not expose Android Studio as a controllable target. This remains a manual
workstation confirmation, not a known build failure.

**B2 is complete.** The six inapplicable `UseAppTint` errors are narrowly
suppressed with rationale in the two RemoteViews layouts; no lint baseline or
global error waiver was added. On the upgraded stack, lint reports **0 errors,
88 warnings** (remaining established project debt). The CI workflow now uses
Temurin 21, runs `testDebugUnitTest lintDebug assembleDebug`, uploads test/lint
reports even on failure, and uploads the debug APK only after a green gate.
The Linux CI run for `73e92b5` passed on 2026-09-07, including all report/APK
uploads: [Android Build run 34082511822](https://github.com/howeitis/sonoswidget/actions/runs/34082511822).
It exercised the 16 committed JVM tests; the subsequent local working tree
contains two additional, in-progress reliability tests and passed 18 tests.

## B1 — AGP upgrade and daemon JVM toolchain migration

### Proposed version contract

These are concrete starting pins researched on 2026-09-06, not a claim that the
entire combination has already been built in this repository. Verify artifact
availability and plugin compatibility at implementation, then record the exact
resolved versions in the completion report. Use fixed releases, never `+`,
`latest`, snapshots or preview versions. If a pin cannot be used, identify the
actual incompatibility and use a documented compatible stable alternative;
do not quietly abandon the AGP upgrade or leave compatibility opt-outs forever.

| Component | Existing | Planned result |
|---|---|---|
| Android Gradle plugin | 8.10.1 | 9.4.0 |
| Gradle wrapper | 8.11.1 | 9.6.0, the documented AGP 9.4 pairing |
| Daemon JVM | inherited from local/IDE JAVA_HOME | Java 21, Adoptium/Temurin, repository criteria |
| Java/Kotlin bytecode | 17 | retain 17 explicitly |
| Kotlin Android plugin | external 2.1.21 | AGP built-in Kotlin; remove redundant Android plugin |
| Compose compiler plugin | 2.1.21 | pin to the effective supported Kotlin compiler version |
| KSP | 2.1.21-2.0.1 | KSP2, candidate 2.3.11; validate generated sources |
| Hilt plugin/runtime/compiler | 2.58 | candidate 2.59.2, all three kept aligned |

AGP 9.4 documents Gradle 9.6.0 and a minimum JDK 17. Java 21 is this project's
chosen daemon policy, not an AGP requirement. Check Android Studio compatibility
as well: the published table lists Quail 4 (2026.1.4) for AGP through 9.4.
Sources: [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
[AGP/Gradle/Studio compatibility](https://developer.android.com/build/releases/about-agp).
Candidate plugin releases: [Hilt/Dagger 2.59.2](https://github.com/google/dagger/releases/tag/dagger-2.59.2),
[KSP 2.3.11](https://github.com/google/ksp/releases/tag/2.3.11).

### Build-script changes

Files: `gradle/libs.versions.toml`, `build.gradle.kts`, `app/build.gradle.kts`,
`settings.gradle.kts`, `gradle.properties`, all wrapper files, new
`gradle/gradle-daemon-jvm.properties`, `.github/workflows/android-build.yml`,
`AGENTS.md`, `README.md` and this document's completion record.

1. Capture existing test/build output with a working JDK 21 before changing
   versions. Preserve the current lint failure as a baseline, not a waiver.
2. Upgrade AGP and wrapper as a coordinated change. Regenerate the wrapper
   scripts/JAR using the wrapper task; commit them with its properties and
   official distribution SHA-256 checksum. Do not change only the URL and call
   wrapper migration complete. Bootstrap with a supported installed JVM when
   old build scripts cannot configure under the new Gradle version.
3. Migrate to built-in Kotlin: remove `libs.plugins.kotlin.android` from root,
   app and catalog; replace `android.kotlinOptions` with supported
   `kotlin.compilerOptions`. Keep Compose compiler applied and pin it according
   to the effective Kotlin version resolved by AGP, not the obsolete catalog
   value. Do not infer Kotlin's version from KSP's version number. Remove any
   temporary built-in-Kotlin/new-DSL opt-out before completion. Follow the
   [official Kotlin migration guide](https://developer.android.com/build/migrate-to-built-in-kotlin).
4. Upgrade Hilt plugin/runtime/compiler together and use supported KSP2. Confirm
   Hilt generated components, test code, BuildConfig and Compose compile from
   clean outputs. Audit AGP DSL/source-set changes, including the manual
   `src/test/kotlin` registration; keep all existing tests discovered exactly once.
5. Keep Java source/target compatibility and Kotlin JVM target at 17. Configure
   the compilation toolchain explicitly where needed using the supported AGP/
   Kotlin DSL; using JDK 21 tools must not silently change emitted bytecode to 21.
   The daemon JVM and compile target are separate settings. Preserve the
   configuration-cache setting for this migration; reassess its old explanatory
   comment instead of making an unrelated performance change.

### Daemon criteria and provisioning

Generate and commit `gradle/gradle-daemon-jvm.properties` with:

```powershell
.\gradlew.bat updateDaemonJvm --jvm-version=21 --jvm-vendor=adoptium
```

Configure a pinned supported toolchain resolver in settings when needed for URL
generation. Generate real provisioning URLs for Windows x64 and Linux x64 (the
current desktop/CI), plus macOS x64/ARM64 if supported by the resolver. No fake
URLs or machine-local paths. Criteria take precedence over JAVA_HOME and
`org.gradle.java.home`; a compatible launcher Java is still needed to start the
wrapper. Provisioning requires network access, or a matching preinstalled JDK
for offline use. Record that this pins a major version/vendor, not necessarily
identical patches across machines. Source:
[Gradle daemon JVM criteria](https://docs.gradle.org/current/userguide/gradle_daemon.html#sec:daemon_jvm_criteria).

Stop old daemons and verify a real task uses Temurin 21. Capture `--version` and
daemon-selection/build logs; do not mistake launcher Java for daemon Java.
Test both an installed matching JDK and provisioning with an isolated temporary
Gradle user home; do not delete a developer's caches. Verify selection when the
launcher points at another wrapper-supported Java version. Do not edit global
JAVA_HOME, user Gradle properties or unrelated IDE projects. Replace the old
hardcoded Android Studio JBR build instruction with wrapper-based instructions
and one-time supported-Java bootstrap guidance.

B1 acceptance: clean Windows build and Linux CI use the chosen daemon criteria;
IDE sync succeeds on a compatible Studio; bytecode remains 17; Hilt/KSP/Compose
and all existing tests compile; committed configuration contains no workstation
paths; first-run provisioning/offline setup is documented. A working local JDK
path in a command is not completion of this migration.

## B2 — Restore lint and CI as release gates

1. Resolve all six current tint errors in `app/src/main/res/layout/widget_loading.xml`
   and `widget_preview.xml`. These are widget resources: do not blindly replace
   platform views/attributes with AppCompat-only behavior. Prefer drawable-level
   tinting or, if the lint rule is inapplicable to a specific RemoteViews resource,
   narrowly scoped suppression with rationale and rendered verification. No
   blanket baseline or `abortOnError=false` to hide failures.
2. Triage warnings into introduced issues versus existing debt. Fix correctness,
   accessibility and build-migration warnings introduced by this work; record
   justified remaining warnings and counts. Verify lint on the new AGP, whose
   checks may differ from the old baseline.
3. Update CI's JDK setup from 17 to Temurin 21 to match daemon criteria. Retain
   a usable launcher Java, existing read-only permissions and wrapper execution.
   Update Gradle setup action only if required for the selected Gradle release.
4. CI currently runs assemble and tests but omits lint. Require
   `testDebugUnitTest lintDebug assembleDebug`; publish test/lint reports even
   on failure and the APK only after gates pass. Preserve wrapper checksums and
   use appropriate wrapper validation. No OAuth credentials are needed to
   compile the debug build; do not introduce secrets into build logs.

B2 acceptance: the complete command succeeds locally and in CI, and the report
contains actual test totals, lint counts and versions. Update the historical
“all checks passed” claim only after observing a current passing run.

## R1 — Preserve pending state during normal display decoding (P1)

Evidence: `service/WidgetStateStore.kt` sets `pendingOperations = emptyList()` in
the decoder used by `widget/SonosWidget.kt` on every render. This prevents normal
Switching room, Preparing favorite and grouping feedback from reaching layouts.

Separate display serialization from process-start recovery. Normal display
decoding must retain pending operations. Repository recovery must deliberately
clear/reconcile operations from an earlier session using a session identity or
equivalent explicit lifecycle policy. Polling must merge active operations, not
drop a still-running favorite/grouping request when building a fresh state.

Tests: live serialization round-trip preserves operations; an actual recovery
path clears obsolete operations; an ordinary poll during slow favorite loading
does not clear loading state; widget fixtures exercise decoded state, not just
directly constructed model objects. Replace the misleading test that labels
every deserialize call a process restart.

**R1 completion record (2026-09-07).** Complete. Display decoding already
retained operations; two gaps in opposite directions remained, and both are now
closed and covered.

*Polling erased running requests.* `WidgetStateMapper` never sets
`pendingOperations`, so every polled state carried an empty list. Whenever a
poll's STATUS field was still unclaimed, publishing that list cleared
"Preparing favorite" while the playlist was still loading. Operations are now
exempt from polling inside `WidgetStatePublisher` rather than at each assembly
site, so no future poll builder can reintroduce this. `pollCloud` was also
bypassing the guarded path entirely — a full `pushState` with no snapshot — and
now snapshots before its network call and publishes through `pushPollState`.

*Display decoding resurrected dead requests.* Retaining operations is right
within the publishing process and wrong across process death: the request lives
in the memory of a process that is gone, so it can never complete, fail or be
cancelled, and the widget showed "Switching room" until the next poll — after an
idle teardown, up to fifteen minutes. `WidgetStateStore` now stamps each
published state with a per-process session id, and `deserializeForDisplay`,
used by the widget's render path, keeps operations only from the publishing
process. State written before session identity existed is treated as an earlier
process, which is what it is.

This replaced `deserializeForProcessRecovery`. That function had no production
caller: its test asserted a lifecycle policy the app never applied, which is
exactly the unearned completion claim this plan warns against. The session
policy is the same rule, actually wired to the render path.

Covered by `WidgetStatePublisherTest` (a poll cannot clear a running request;
only the request's own completion retires it) and `WidgetStateStoreTest`
(operations kept from the publishing session, dropped from an earlier process,
and dropped from state stored before session identity). `WidgetStateFixturesTest`
now decodes through `deserializeForDisplay`, so it covers the decoder its name
claims.

## R2 — Bind actions and grouping drafts to their original room (P1)

Evidence: `widget/WidgetActions.kt:canDispatch` checks availability but not the
rendered target. `ApplyGroupingDraftAction` stores/reads only a set of speaker
IDs; `data/SonosRepository.kt:applyGroupingDraft` applies it to the current room.

Include expected target ID/generation in action parameters and draft state;
carry that identity into the repository and validate before dispatch. Invalidate
drafts on shared-target change across all widget instances. Capture destination
before suspension, not after topology fetch or optimistic publication. Reject
stale callbacks even after switching has completed and controls are available.
Guard a grouping operation against a target change during execution; refreshing
topology alone is not proof the draft belongs to that destination.

Tests: delayed A-screen tap after B is confirmed sends no command to B; A's
grouping draft cannot apply to B; switching while topology loads cannot retarget
the operation; both widget instances display one consistent target and recovery.

## R3 — Protect newer intent from enrichment and old rollbacks (P1)

**R3 completion record (2026-09-07).** Complete and validated in CI.

The repository has a single serialized widget-state publication path, per-field
revisions, and operation ownership for optimistic playback, volume and mute
changes. `pollLocal()` applies its essential result only to fields unchanged
during the poll, and applies queue/favorites and artwork as narrow patches to
the latest state rather than republishing an old snapshot. Room changes discard
room-scoped optimism. `pollCloud()` was migrated onto the same guarded path
during R1.

*The earlier validation failure was not a classpath problem.* `7e600d1` added
`import kotlinx.coroutines.await`, which does not exist in
`kotlinx-coroutines-core` — `Deferred.await()` is a member function needing no
import. In Kotlin an unresolved import cascades into unresolved references
throughout the file, which is what the previous session saw and attributed to a
lost app source classpath. CI had been failing `:app:compileDebugKotlin` on
every commit since, so R3's code had never compiled. Removing the import is
`707d796`; the gate passed on it in
[run 34160611073](https://github.com/howeitis/sonoswidget/actions/runs/34160611073).

*The acceptance tests needed a seam, not fakes of the controller.*
`SonosRepository` cannot be constructed in a JVM unit test — it needs a
`Context`, `android.util.Log` and Hilt — so the publication path itself (the
lock, the revision ledger, the field merge, both state writes) moved into
`WidgetStatePublisher`, parameterized by a suspend sink. The repository keeps
its `_widgetState` name and every read site; only the two `_widgetState.value =`
assignments moved, and the push/snapshot helpers became one-line delegates.
Publication ordering is unchanged: record, merge, flow write and sink still all
happen under one lock.

`WidgetStatePublisherTest` covers each acceptance scenario against the real
publication path rather than the ledger alone: pause during delayed artwork
stays paused while the artwork still lands; a poll that outlived a tap keeps the
tap and applies its untouched fields; an older failure cannot undo a newer
success while the newest command can still roll itself back; a mute failure
cannot undo a poll that confirmed it afterwards, though a tap that still owns
its field may undo its own guess; room A's optimism cannot restore its volume
after a switch to room B; and a stalled publication blocks a newer one instead
of being overtaken, so what the widgets last received is what the app believes
it published. That last test drives genuinely concurrent coroutines, which is
why `kotlinx-coroutines-core` was added as a test dependency.

Evidence: `pollLocal()` applies optimism before essential publication, then
publishes its old snapshot again after enrichment. Its target-generation check
does not detect newer actions within the same room. Failure rollback likewise
does not verify per-field operation ownership.

Introduce a single controlled state-mutation path with field revisions and
operation ownership. Merge queue/artwork enrichment into current state only
when its destination/media identity remains valid. Older failures may restore
only fields they still own, never the whole prior snapshot. Scope optimism to
destination and invalidate it on room changes. Publish widget state in revision
order so concurrent publishers cannot leave different instances on older state.

Tests: pause during delayed artwork stays paused; older mute/play failure cannot
undo newer success; room-A optimism cannot leak into B; delayed publication to
two widgets cannot overwrite a newer revision. Reapplying a fixed two-second
override before the final write is not an adequate substitute for ownership.

## R4 — Preserve unknown outcomes from the transport upward (P1)

Evidence: `sonos/local/SonosSoapClient.kt` catches IOException and returns null;
`SonosControlActions.kt` converts null to false; repository interprets false as
DEFINITE_FAILURE. A lost response after execution therefore bypasses the new
UNKNOWN handling and can incorrectly roll back state.

Return structured transport outcomes and retain them through controller and
repository layers. Distinguish explicit SOAP rejection from timeout/lost
response. Do not replay ambiguous skip/favorite/grouping requests. Preserve
coroutine cancellation and cancel the underlying call appropriately. Apply
operation-appropriate deadlines, including slow playlist operations.

Tests: speaker accepts command but response times out => UNKNOWN and reconcile,
not rollback/replay; explicit SOAP rejection => definite failure; cancellation
does not become ordinary failure; slow accepted favorite does not duplicate its
queue insertion. Test at the transport boundary as well as the repository fake.

## R5 — Keep volume intent responsive under slow reconciliation (P2)

Evidence: `adjustVolume()` holds `volumeIntentMutex` across `setVolume()`, which
awaits command and full refresh. Later taps wait behind unrelated enrichment.

Use a short critical section to accumulate/clamp desired volume and publish it
immediately. Drain/coalesce unsent absolute targets per destination outside that
lock; reconciliation and artwork must not block receipt of further intent. Keep
acknowledged, desired and in-flight values distinct. Room changes invalidate
unsent old-target intent rather than applying it to the new room.

Tests: five +5 taps at 50 request/display 75 before releasing a blocked refresh,
and ultimately converge to 75; alternating taps/clamps work; destination change
does not reroute queued volume; older failures preserve newer desired volume.

## U1 — Validate connected Expanded at its real minimum size (P1)

Evidence: 400×340dp is still offered; 128dp artwork, 60dp transport, volume,
progress, header and spacing require roughly 460dp before secondary content.
Room selection/error content still adds rows above the player. This is a
source-derived space budget; offline screenshots omit important playing rows.

Implement the original phase 4 hierarchy with a measured height budget. Reduce
or rearrange fixed content, or raise the expanded bucket's minimum; do not
advertise 340dp while hoping the launcher compresses it. Reserve a fixed player
region and one bounded secondary region. Room/group editors replace secondary
content rather than pushing controls down. Overflow requires a reachable list
or focused companion surface; truncating room choices alone is not completion.

Validate populated local playback at exact minimum width/height, with progress,
queue/favorites, an error, room selector, grouping editor and large fonts. Add
render-based checks or repeatable device fixtures, not only bucket threshold
unit tests. Verify actual 48dp action targets: Expanded currently retains 40dp
volume buttons and 40×32dp seek targets. Preserve defensive undersized handling.

## Final review checklist for the next delivery

- All B/R/U packages above have evidence and focused regression tests.
- Existing product decisions and original acceptance criteria below remain met.
- Run clean tests/lint/debug assembly after build migration, then the final
  incremental command after runtime/UI work. Document any release-variant smoke
  check without changing signing or publishing anything.
- Record daemon JDK, wrapper, AGP, Kotlin/Compose, KSP and Hilt versions; test and
  lint reports; populated launcher screenshots; timing results; unverified
  household scenarios and any remaining lifecycle limitations.
- Update `AGENTS.md`/README for the actual build contract and remove obsolete
  debounce/size/polling comments where touched. Do not retain claims of completed
  command-ordering protection without its behavior tests.

## Historical implementation checkpoint and original product plan

The sections below preserve the original intent and prior observations. Their
completion labels are superseded by the 2026-09-06 review and work packages above.

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

Use the B1/B2/R1–R5/U1 delivery order above for the remaining work. Phases 0–7 below remain the original feature acceptance specification; preserve completed work and finish unmet criteria. The AGP/built-in-Kotlin migration in B1 is explicitly in scope. Do not turn it into an unrelated application framework migration or a wholesale repository rewrite.

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

After B1, run from PowerShell at repository root with a supported launcher Java
available. The committed daemon criteria select Java 21; do not add the old
hardcoded Android Studio JBR override:

```powershell
.\gradlew.bat --version
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

“Implement the remaining work in `WIDGET_EXPERIENCE_IMPLEMENTATION_PLAN.md`. Read `AGENTS.md` and preserve existing user changes. Start with B1 (AGP/build-plugin upgrade and checked-in daemon JVM criteria), then B2 (lint/CI), R1–R5 (review fixes with regression tests), and U1 (connected layout validation). Preserve completed experience features and satisfy the original product decisions and remaining phase criteria. Use fixed compatible tool versions, retain SDK/bytecode targets as specified, and make reviewable incremental changes. Report actual build/test/lint results, resolved versions, device evidence and unverified household scenarios. Do not mark source-inferred or untested behavior as validated.”
