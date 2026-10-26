# Porting KU WBB to a shared Android + iPhone codebase

The KU Volleyball app has already made this move and is shipping on TestFlight.
This app was originally ported *from* that one, so the two share architecture,
naming and idiom, and almost every question this port raises has already been
answered there. Read `inspectorgad/ku-volleyball` at `main` as the reference;
where this plan says "copy", it means copy from there.

What that saves, concretely: the Apple certificates and App Store Connect API
key already exist and are per-account, so nothing has to be re-created at
developer.apple.com except an App ID and an app record. `shared/build.gradle.kts`
needs one line changed. `gradle.properties` needs none — the two files are
already byte-identical. The platform layer (`platform/`, `text/`, `json/`) copies
verbatim.

What it does not save: the toolchain is a year behind volleyball's and must
catch up; the Room schema has eight shipped migrations and no golden to check
them against; and the migration test has no counterpart there at all.

## Where the code ends up

29 of the 31 Kotlin files under `app/src/main/java/com/example/` move to
`shared/src/commonMain`. `app/src/main` ends up holding two files:
`MainActivity.kt` and `AndroidPhone.kt`. The iPhone app is a thin Swift shell
over the same Kotlin.

Twelve of the thirteen files in `app/src/test/` stay exactly where they are —
including the Roborazzi screenshot test. Volleyball's equivalent screenshot
test still sits in `app/src/test` while the Composable it photographs lives in
`commonMain`, on the same Roborazzi and Robolectric versions this app already
pins.

## Decisions to make before starting

**The Android Keystore alias.** This app encrypts the reader's Anthropic API
key under `ku_wbb_ask_api_key`; volleyball's shared `AndroidDevice` hard-codes
`ku_ask_api_key`. Keep this app's alias, or every existing install silently
loses its key and the reader has to type it in again with no explanation.

**Where `migrations()` lives.** `MigrationTest` walks the real migration set
through `JayhawksDatabase.migrations()`. Volleyball has no migration test and
keeps its migrations file-private, so there is nothing to copy. Keep them
reachable from `androidMain` — it is the one place worth deviating from the
reference.

**Push notifications.** This app's game-night watcher sends none:
`scripts/game-night-watch.mjs` has no APNs code and `game-night.yml` passes
only `GH_TOKEN`. So the `aps-environment` entitlement, both `APNS_*` secrets,
the AppDelegate and two workflow steps all drop out. The iPhone app is simpler
than volleyball's. Register the App ID with no push capability unless alerts
are on the roadmap.

**The App Store name.** Globally unique, and not checkable without trying.
"KU WBB" first, with "Kansas WBB Stats" or "Jayhawks WBB" in reserve. The name
under the icon on the phone is `CFBundleDisplayName` and is independent of it,
so a compromise here changes nothing the user sees.

---

## Phase 0 — Freeze the current schema

Do this before touching anything else.

The database is at v9 with `exportSchema = false`, so no schema has ever been
written down, and v9 reached phones in October. The port changes three things
that all feed Room's schema hash at once: the Room version, the KSP version,
and Android codegen becoming multiplatform codegen. A schema that comes out
different is a crash on launch for anyone upgrading, and without a golden there
is no way to notice before they do.

- [x] Set `exportSchema = true` in `data/JayhawksDatabase.kt` and point KSP at a
      directory: `ksp { arg("room.schemaLocation", "$projectDir/schemas") }` in
      `app/build.gradle.kts`.
- [x] Build on the **current** toolchain and commit the generated
      `app/schemas/com.example.data.JayhawksDatabase/9.json`.
- [x] Add a CI step that regenerates the schema and fails if it differs from
      the committed copy. This is the actual protection; the golden on its own
      only helps someone who remembers to look.

**The number that matters: `identityHash` is `2c20466365b2a25ef4beb786cf1e9928`.**
That is what Room compares when it opens the database on a phone. If a later
step changes it, every installed app crashes on launch instead of migrating.

After every step below that touches Gradle or Room, that check must stay green.

## Phase 1 — Toolchain, still Android-only

Land this as its own commit while the repo is still a single module, so that
Kotlin-upgrade failures and KMP-configuration failures cannot appear in the
same log.

| | from | to |
|---|---|---|
| Kotlin | 2.2.10 | **2.4.21** |
| AGP | 9.1.1 | **9.2.1** |
| Gradle | 9.3.1 | **9.4.1** |
| KSP | 2.3.5 | **2.3.12** |
| Room | 2.7.0 | **2.8.5** |
| compileSdk | 36.1 | **37** |
`compileSdk` drops the minor-API qualifier: `release(37)`, not
`release(36) { minorApiLevel = 1 }`. `targetSdk` stays at 36, as in volleyball.

- [x] Bump the version catalog.
- [x] `gradle :app:testDebugUnitTest :app:assembleDebug` green.
- [x] Phase 0's schema check still green.

**Compose does not move in this phase.** An earlier draft of this plan had the
Compose BOM deleted here, on the reasoning that Compose Multiplatform 1.12.2
replaces it. It does — but not until phase 3, and deleting it early means
choosing an androidx `material3` version by guesswork: material3 is versioned
independently of `compose-ui`, the BOM is the thing that aligns them, and
volleyball is no help because its material3 comes from `org.jetbrains` on
different numbering. Left alone, BOM 2024.09.00 compiles cleanly under Kotlin
2.4.21, which settles the only real question. It goes in phase 3, where
`composeMultiplatform` 1.12.2 and `composeMultiplatformMaterial3`
1.12.0-alpha03 replace it as a set — a prerelease, and the set that has been
through an App Store upload, so do not try to improve on it.

**Room 2.7.0 → 2.8.5 did not change the schema.** The identity hash is still
`2c20466365b2a25ef4beb786cf1e9928` and the phase 0 check passed on the first
build. This was the single biggest risk in the whole port — a changed hash is
a crash on launch for every phone already holding data — and it is now a
measured fact rather than a hope. Volleyball made the same jump with no way to
check it.

## Phase 2 — Apple, in parallel

None of this blocks on the code, and it can be done while Phase 1 runs.

- [ ] Register bundle ID `io.github.inspectorgad.kuwbb` at developer.apple.com
      → Identifiers → App IDs. No capabilities (see push, above).
- [ ] Create the app record in App Store Connect against that bundle ID.
- [ ] Copy four secrets from the volleyball repo into this one, unchanged —
      they are per-account, not per-app:
      `APPLE_TEAM_ID`, `ASC_ISSUER_ID`, `ASC_KEY_ID`, `ASC_KEY_P8`.
      The two `APNS_*` secrets are not needed.
- [ ] Port `ios/check_apple_keys.py` and `apple-keys-check.yml` with the push
      half removed, and run it to confirm the keys work before anything depends
      on them.

Signing is cloud signing: `CODE_SIGN_STYLE: Automatic` plus the App Store
Connect key. There is no certificate to generate, no provisioning profile to
download and no device to register.

## Phase 3 — The shared module

- [ ] `include(":app", ":shared")` in `settings.gradle.kts`; add the three
      `apply false` plugin aliases to the root build file.
- [ ] Create `shared/build.gradle.kts` as a copy of volleyball's, changing
      **one line**: `namespace = "io.github.inspectorgad.kuwbb.shared"`.
      Keep `baseName = "Shared"` verbatim so the Xcode search paths and the
      Swift `import Shared` copy across unchanged.
- [ ] Copy the platform layer in **before** moving any of this app's code:
      `commonMain/platform/{Device,Http}.kt`, `commonMain/json/Json.kt`,
      `commonMain/text/{Numbers,Dates,Sha256}.kt`, and the matching
      `androidMain`/`iosMain` actuals. Copy their tests with them.
- [ ] Change `AndroidDevice`'s alias to `ku_wbb_ask_api_key` and `IosDevice`'s
      Keychain service to `io.github.inspectorgad.kuwbb`.
- [ ] Add the `QueryStats` icon to `AppIcons.kt` — the Season tab uses it and
      volleyball's hand-drawn icon set does not have it. The Material icons
      libraries stopped at 1.7.3 and have no iOS build, which is why that file
      exists. Take the 24dp path from google/material-design-icons.
- [ ] Move `data/{Entities,JayhawksDao,TeamNames}.kt` and the whole `stats/`
      package unchanged.
- [ ] `stats/BasketballStats.kt`: drop `java.util.Locale`, rewrite the two
      `String.format` calls against `text/Numbers.kt`.
- [ ] Split `data/JayhawksDatabase.kt` three ways — `@Database` plus
      `@ConstructedBy` in `commonMain`, the migrations and `get(context)` in
      `androidMain`, a `createDatabase()` in `iosMain`.
- [ ] Rewrite the boundary in `Seeder.kt`, `SeasonSync.kt`, `AskEngine.kt`:
      `Context` → `Device`, `org.json` → `com.example.json`, OkHttp →
      `platform/Http`.
- [ ] Gut `AskKeyStore.kt` down to volleyball's `Device`-based version, keeping
      this app's `SECRET` and `MODEL_PREFS` key names.
- [ ] Move the whole `ui/` package. A diff of every `material3` and `foundation`
      import in this app against volleyball's `commonMain` comes back empty, so
      this is mechanical.
- [ ] `JayhawksViewModel`: `AndroidViewModel(app)` → constructor-injected
      `(dao, device)`. There is no `Upkeep` to carry over — that exists in
      volleyball for its widget and alerts, neither of which this app has.
- [ ] Rewrite `MainActivity.kt` on volleyball's model.
- [ ] Add `iosMain/MainViewController.kt`, without the alerts wiring.
- [ ] Fix imports in the thirteen test files; none of them move.
- [ ] Add `shared/src/commonTest/` with the agreement suite. Its digests have
      to be recorded from a real JVM run — take them from CI output, they are
      not transferable from volleyball.

## Phase 4 — The iPhone app

- [ ] `ios/project.yml` from volleyball's: name `KUWBB`, bundle id
      `io.github.inspectorgad.kuwbb`, display name "KU WBB",
      `MARKETING_VERSION "0.1.0"`, entitlements block deleted.
- [ ] `ios/App/KUWBBApp.swift` — SwiftUI shell over `MainViewController`, no
      AppDelegate.
- [ ] `ios/App/Assets.xcassets` plus a 1024×1024 icon. Extend
      `scripts/make-web-icons.mjs` with a 1024 target and flatten the alpha, so
      the iPhone icon, the Android launcher icon and the PWA icon stay the same
      artwork.
- [ ] `ios-testflight.yml` and `branch-ci.yml`, adapted.
- [ ] Push to a branch, watch the run, open TestFlight on the phone.

The `.xcodeproj` is never committed. `ios/project.yml` is an XcodeGen spec and
CI generates the project from it; on a Mac, `brew install xcodegen` and
`xcodegen generate` in that folder does the same.

---

## Known risks

**The Room jump is the one that can hurt users.** Room 2.7.0 → 2.8.5, a new KSP
version and multiplatform codegen all land on a schema that is already on
phones. Phase 0 exists entirely to make a mismatch visible. Volleyball offers no
comfort here — it also runs `exportSchema = false`, so it made the same jump
without checking.

**`material3` is pinned to an alpha.** Eleven of this app's Material 3 APIs
ride on it, including `PullToRefreshBox`. It is the pin that shipped; treat the
version numbers as a set, not as individually negotiable.

**The screenshot test will render differently.** Neither repo commits a golden
PNG, so today it proves the card renders without throwing and nothing more.
Worth adding a baseline *after* the toolchain settles, not before.
