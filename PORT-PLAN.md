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

Done in increments, each with its own CI build, rather than one commit that
either works or does not. Two of them departed from this plan on purpose, and
both departures were about keeping one risk per build; see the notes below.

**3a — the module and the platform boundary** ✅
- [x] `include(":app", ":shared")`; the `apply false` plugin aliases in the
      root build file.
- [x] `shared/build.gradle.kts` from volleyball's, changing **one line**:
      `namespace = "io.github.inspectorgad.kuwbb.shared"`. `baseName = "Shared"`
      stays verbatim so the Xcode search paths and `import Shared` copy across.
- [x] The platform layer, copied in before any of this app's code:
      `platform/Http.kt` with its OkHttp and NSURLSession actuals,
      `json/Json.kt`, `text/{Numbers,Dates,Sha256}.kt`.
- [x] The cross-platform agreement suite, **digests included**. The earlier
      reading that Golden had to be re-recorded here was wrong: the samples
      hold no sport-specific content, every input is seeded or fixed, and the
      code under test is identical. It passed first run.

*Deviation: no Compose and no Room in `:shared` yet.* The app still resolves
Compose through the BOM, and Compose Multiplatform alongside it is a version
clash with nothing to arbitrate it. Each arrives with the code that needs it.

**3b — the entities, the DAO and the stats engine** ✅
- [x] `data/{Entities,JayhawksDao,TeamNames}.kt` and the whole `stats/`
      package, moved unchanged. Room's annotations are available in
      `commonMain` via `api(libs.androidx.room.runtime)`.
- [x] `stats/BasketballStats.kt`: `java.util.Locale` and two `String.format`
      calls replaced by `fixed()`. Proven, not guessed — 3a's suite compares
      `fixed()` against Java's `%.Nf` over tens of thousands of values.

*Deviation: the database did not move with the entities.* Room's KSP writes
the exported schema, so moving the compiler moves the schema's path and the
check guarding it, in the same change that moves the entities the schema
describes. Two independent ways to break a database already on phones; worth
separating.

**3c — the database split** ✅
- [x] `@Database` + `@ConstructedBy` + a bare `companion object` to
      `commonMain`; `expect object JayhawksDatabaseConstructor`.
- [x] Migrations, `instance`, and `get(context)` to `androidMain`.
      `migrations()` is **public**, as a companion extension — `MigrationTest`
      lives in `:app`'s test source set, so `internal` would not reach it.
      The one deliberate deviation from volleyball, which has no migration
      test and keeps its migrations private.
- [x] `createDatabase()` in `iosMain`, bundled SQLite driver.
- [x] Room's compiler moved to `:shared` as `kspAndroid`, `kspIosArm64`,
      `kspIosSimulatorArm64`, with Room's Gradle plugin placing the schema —
      one file at `shared/schemas/`, not one per target.

**The schema survived the move byte for byte.** The pre-split file was
restored at the new path rather than the generated one being committed, which
makes the check an assertion instead of a recording: it passes only if moving
`@Database` to `commonMain` and running Room's compiler per platform produced
an identical schema. It did. `identityHash` is still
`2c20466365b2a25ef4beb786cf1e9928`.

That was the largest risk in the port, and it is now spent. Everything
remaining can be fixed by editing code; this was the one step that could have
broken a database already on a phone.

**3d — the platform-touching files**
- [x] `platform/Device.kt` and its two actuals. This app's keystore alias
      stays `ku_wbb_ask_api_key`; `IosDevice`'s Keychain service is
      `io.github.inspectorgad.kuwbb`.
- [x] Rewrote the boundary in `Seeder.kt`, `SeasonSync.kt`, `AskEngine.kt`:
      `Context` → `Device`, `org.json` → `com.example.json`, OkHttp →
      `platform/Http`. `AskKeyStore.kt` is the `Device`-based version.

Done in three commits rather than one, because the files are coupled and a
single build could not say which move broke what: the platform boundary and
the key store; then the seeder and the sync, which share a JSON type and so
had to move together; then the engine. `AskModel` and the model list came out
of `AskEngine` into `AskModels.kt` so the key store could move before the
engine — the volleyball app keeps them inside `AskEngine`.

`app/src/main/java/com/example/data/` is now empty. Every byte of the data
layer is shared, and what is left in `:app` is the UI, the view model and
`MainActivity`.

Nothing a phone already holds moved: the same SharedPreferences files and
keys (`ask_key`/`api_key`, `ask_prefs`/`model`, `season_sync`/`last_hash` and
its siblings), the same Keystore alias, the same AES/GCM scheme. An installed
app finds its API key, its chosen model, and its last sync state where it
left them.

**Not yet compiled: `IosDevice`.** No workflow here builds for iPhone until
phase 4 adds a Mac runner, so that file rests on being volleyball's working
code rather than on a green build. Same for `DarwinHttp`, `NSDataBytes` and
the iOS database. The first iOS compile in phase 4 should be expected to turn
up something.

**3e — the UI, and Compose Multiplatform**
- [ ] Add the `QueryStats` icon to `AppIcons.kt` — the Season tab uses it and
      volleyball's hand-drawn set does not have it. The Material icons
      libraries stopped at 1.7.3 and have no iOS build, which is why that file
      exists. Take the 24dp path from google/material-design-icons.
- [ ] Delete the Compose BOM, add Compose Multiplatform 1.12.2 and material3
      1.12.0-alpha03 to `:shared`. This is the step the BOM was waiting for.
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
