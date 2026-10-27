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
- [x] Deleted the Compose BOM, added Compose Multiplatform 1.12.2 and
      material3 1.12.0-alpha03 to `:shared`. This is the step the BOM was
      waiting for: a BOM versions `androidx.compose`, and these screens are
      `org.jetbrains.compose`.
- [x] Moved the whole `ui/` package. It was as mechanical as the import diff
      promised — the only Android API anywhere in it was `BackHandler`, now
      `SystemBackHandler` with an expect/actual, and a `SimpleDateFormat` call
      for today's date, now `todayIso()`.
- [x] `JayhawksViewModel`: `AndroidViewModel(app)` → constructor-injected
      `(dao, device)`. No `Upkeep` to carry over — that exists in volleyball
      for its widget and alerts, neither of which this app has.
- [x] Rewrote `MainActivity.kt` on volleyball's model.

The view model came forward from 3f, not by choice: `JayhawksApp` takes one,
and a `commonMain` screen cannot reference a class in `:app`. Once it moved,
`MainActivity` had to build it, which is the rest of that 3f item.

`LocalDevice` replaces volleyball's `LocalPhone`. Volleyball's `Phone`
carries match alerts and the share sheet as well as the device; this app has
neither, so what is left is the device and there is no interface worth
wrapping it in.

**`:app` is now one file.** `MainActivity.kt`, 30 lines: build the device,
build the view model, provide the one, hand over the other.

**3f — the iPhone entry point, and the first iPhone compile**
- [x] Added `iosMain/MainViewController.kt`, without the alerts wiring. It is
      the iPhone's `MainActivity` and does the same three things: build the
      device, build the view model, provide one and pass the other.
- [x] Added `.github/workflows/ios-shared.yml`, brought forward from phase 4.

The workflow is the point of this step, more than the file is. Everything
iPhone-only written since 3a — the Keychain storage, the NSURLSession HTTP,
the bundled-SQLite database, the back handler, now the entry point — had been
carried on the strength of being volleyball's working code, because nothing
here runs on a Mac and Kotlin/Native cannot cross-compile for iOS from Linux.

It compiles `iosArm64`, links the simulator framework, and runs the agreement
suite on a simulator. That last part matters most: those tests exist to prove
the pure-Kotlin replacements for `String.format`, `SimpleDateFormat`,
`org.json` and `MessageDigest` give the same answers as the JVM, and until
now they had only ever run on the JVM — which is to say, they had only ever
checked the side that was never in doubt.

**It failed on the first run, which is the point.** Two JVM-only APIs had
been sitting in `commonMain` since 3b and 3e, compiling perfectly on Android:

- `stats/Splits.kt` — `sortedMapOf`, which is `java.util.SortedMap`
- `ui/AskScreen.kt` — `e.javaClass.simpleName` on an exception

Neither is an import, which is why the sweeps for `^import java.` after every
move came back clean. Nothing short of a real iOS compile would have found
them, and in phase 4 they would have surfaced an hour into a signing build.

With those two fixed it is green, and the agreement suite ran where it was
always meant to:

```
PlatformAgreementTest.sha256_matches_the_standard_vectors[iosSimulatorArm64] PASSED
PlatformAgreementTest.numbers_are_written_as_on_android[iosSimulatorArm64]   PASSED
PlatformAgreementTest.json_reads_and_writes_as_on_android[iosSimulatorArm64] PASSED
PlatformAgreementTest.dates_read_and_write_as_on_android[iosSimulatorArm64]  PASSED
```

**Phase 3 is done.** The same Kotlin compiles for both phones, and the
replacements for `MessageDigest`, `String.format`, `org.json` and
`SimpleDateFormat` give Kotlin/Native the same answers they give the JVM —
checked on both, not assumed from one.

## Phase 4 — The iPhone app

- [x] `ios/project.yml` from volleyball's: name `KUWBB`, bundle id
      `io.github.inspectorgad.kuwbb`, display name "KU WBB",
      `MARKETING_VERSION "0.1.0"`, entitlements block deleted.
- [x] `ios/App/KUWBBApp.swift` — SwiftUI shell over `MainViewController`, no
      AppDelegate.
- [x] `ios/App/Assets.xcassets` plus a 1024×1024 icon, from
      `scripts/make-web-icons.mjs`, so the iPhone icon, the Android launcher
      icon and the PWA icon stay the same artwork.
- [x] `ios-testflight.yml`. `branch-ci.yml` was not needed: `build-apk.yml`
      already covers Android on a branch by hand, and the iPhone half landed
      in 3f as `ios-shared.yml`.
- [x] Watched the run. Build 1.1 uploaded first time: `Upload succeeded`,
      `** EXPORT SUCCEEDED **`.
- [ ] Open TestFlight on the phone — the only step left, and not mine.

Cloud signing worked with the key carried over from the volleyball app,
which is the part that could not be rehearsed. That also leaves the Admin
question open rather than settled: the export succeeded, so that key can use
the team's cloud-managed certificate, but whether it is Admin or whether App
Manager would have done is still unknown, because nothing here reads the
role back. `APPLE-SETUP.md` says Admin, which is the safe advice either way.

The icon needed more than a resize. App Store Connect rejects an icon with an
alpha channel at all, transparent or not, and a canvas only ever produces
RGBA — so the generator now re-encodes that one target as 8-bit RGB. The art
is a filled square, so nothing is composited; it refuses rather than guesses
if the art ever gains a transparent pixel, since flattening one against a
colour chosen in the script would be inventing artwork. The four `docs/`
icons come out byte-identical to before, which is how we know the change was
confined to the new target.

Three workflows now stand in front of a TestFlight build, cheapest first:
the keys check (Linux, ~30s) rules out the credentials, `ios-shared.yml`
(Mac, ~15m) rules out the Kotlin, and this one (~40m) is left to answer only
whether it signs and packages.

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
