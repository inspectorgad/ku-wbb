# Setting up Apple for the iPhone app

Everything else in this port is code, and I do it. **These are the things only
you can do**, because they happen inside your Apple account and nobody else
can sign in to it.

You do them in a web browser. **You do not need a Mac**, you do not need
Xcode, and you do not need to plug in a phone. Set aside about 30 minutes —
possibly ten, see the next section.

Nothing here is urgent. The Android build keeps working throughout and the
code isn't blocked until phase 4. Do it early anyway: a problem with a new
Apple account surfaces in *days*, not minutes.

---

## Read this first: you may already be most of the way there

You set up KU Volleyball the same way. **An App Store Connect API key belongs
to your whole team, not to one app** — Apple's words: *"Team API keys are
applied across all apps, so app access can't be limited for an API key."*

So the key already uploading volleyball builds will upload these ones too.
That matters for more than convenience: it is **proven to work for this exact
cloud-signing flow**, which takes all the guesswork out of part 3.

Two of the four values are identical for every app on your account:

| Secret | Same as volleyball? |
|---|---|
| `APPLE_TEAM_ID` | **Yes** — one per account, always |
| `ASC_ISSUER_ID` | **Yes** — one per account, always |
| `ASC_KEY_ID` | Yes, *if* you reuse the key |
| `ASC_KEY_P8` | Yes, *if* you reuse the key **and still have the file** |

The catch is that `.p8`. GitHub will not show you a saved secret again, ever,
so the copy in the volleyball repository cannot be read back out. You can only
reuse it if you kept the file — in a password manager, in Notes, anywhere.

**Go and look now, before anything else.** If you have it:

- Parts 1 and 2 still need doing (they're per-app).
- **Part 3 collapses to copying four values you already own.**
- You can ignore everything in this document about choosing an access role.

If you don't have it, no harm done — part 3 makes a fresh key, and the old one
keeps working for volleyball. A team can hold several keys at once.

---

## What you are producing

Four values. That's the whole output of this document.

| Secret name | What it is | What it looks like |
|---|---|---|
| `APPLE_TEAM_ID` | Identifies your developer team | `Z7P62XVNWC` — 10 capitals and digits |
| `ASC_ISSUER_ID` | Identifies your team's API keys as a group | `57246542-96fe-1a63-e053-0824d011072a` |
| `ASC_KEY_ID` | Identifies one specific key | `ABCD123456` — 10 capitals and digits |
| `ASC_KEY_P8` | The key itself: a small text file | starts `-----BEGIN PRIVATE KEY-----` |

### The three IDs people mix up

The commonest way this goes wrong. Three different identifiers, all called
"ID":

- **Team ID** — one per account. Ten characters. On Apple's **developer**
  site.
- **Issuer ID** — one per account, shared by every key you make. Long, with
  dashes. In **App Store Connect**.
- **Key ID** — one per key. Ten characters, so it **looks exactly like the
  Team ID**. That's the trap. Also in App Store Connect.

If signing later fails, it is usually the Team ID and the Key ID swapped.
Part 5 catches that in thirty seconds.

### Where to write them down

Your password manager, or Apple Notes. **Not** in this repository, not in a
file in the project folder, not in a chat message. Anyone holding the `.p8`
can upload apps as you.

This time, **keep the `.p8` somewhere you'll find it again** — that's the
thing that would have made today a ten-minute job.

---

## Before you start: two things that silently block everything

1. **Signed agreements.** Apple: *"You can't add an app to your account until
   the Account Holder signs the latest agreement in the Business section."*
   New accounts almost always have one waiting, and Apple doesn't warn you —
   the Create button just refuses. In App Store Connect, open **Business**
   and clear anything pending.

2. **The right team.** If your Apple ID belongs to more than one team, check
   the team name at the top right before you start. An App ID registered
   under the wrong team won't appear in App Store Connect later, and the
   symptom is an empty dropdown with no explanation.

---

## Part 1 — Register the App ID

This tells Apple that `io.github.inspectorgad.kuwbb` exists and is yours. That
string already matches the Android app's `applicationId`, deliberately.

**This step is genuinely required.** App Store Connect's Bundle ID field in
part 2 is a dropdown that lists only identifiers that already exist — it will
not create one for you, and there is no free-text option.

1. Go to **https://developer.apple.com/account** and sign in.

2. Click **Certificates, Identifiers & Profiles** (under "Program resources").
   Direct link: **https://developer.apple.com/account/resources**

3. Click **Identifiers** in the sidebar, then the **+** button at the top left.

4. Select **App IDs** → **Continue**.

5. Select the type **App** (not "App Clip") → **Continue**.

6. Fill in:

   - **Description** — a label for you, inside the portal. Users never see it.
     Put `KU WBB`. Apple rejects punctuation here, so no apostrophes.
   - **Bundle ID** — select **Explicit** (not "Wildcard") and type exactly:

     ```
     io.github.inspectorgad.kuwbb
     ```

     Explicit is mandatory: Apple requires it for anything that goes through
     App Store Connect, and a wildcard ID will not appear in part 2's dropdown.

     **Proofread this character by character.** It is the point of no return —
     see the warning below.

7. **Capabilities** — tick nothing.

   > **You'll see In-App Purchase already ticked and greyed out.** That's
   > normal and not something you did. Apple: *"In-App Purchase is enabled by
   > default for an explicit App ID."* You can't turn it off and it obliges
   > you to sell nothing.
   >
   > Optional: if you might one day want the game-night watcher to alert your
   > phone, tick **Push Notifications** now. It's free to leave unused, and
   > equally easy to add later — cloud signing picks up the change by itself.

8. **Continue**, review, then **Register**.

> ⚠️ **A bundle ID is permanent.** Once an app record uses it you cannot
> rename it, and Apple never releases the string — *"Deleting an existing app
> record doesn't make its bundle ID available."* A typo means abandoning that
> identifier forever and registering a different one. Read it twice before
> clicking Register.

✅ **Done when** `io.github.inspectorgad.kuwbb` is in the Identifiers list.

---

## Part 2 — Create the app record in App Store Connect

Part 1 registered the *identifier*. This creates the *app* — the thing that
holds builds, testers and TestFlight.

Two different Apple websites, which is confusing at first: `developer.apple.com`
holds identifiers and keys; `appstoreconnect.apple.com` holds apps and builds.

1. Go to **https://appstoreconnect.apple.com** and sign in.

2. Click **Apps**, then the **+** at the top left, then **New App**.

3. The dialog, field by field:

   - **Platforms** — tick **iOS** only.
   - **Name** — public, and unique across the entire App Store. Try
     **`KU WBB`**. If it's taken, `KU Women's Basketball` or `Jayhawks WBB`
     will do; it's cosmetic while you're only on TestFlight.
   - **Primary Language** — **English (U.S.)**.
   - **Bundle ID** — a dropdown. Choose **`io.github.inspectorgad.kuwbb`**.

     > Not there? Either part 1 didn't finish, you registered a *wildcard*
     > rather than an explicit ID, or you're in a different team than you were
     > a moment ago. New identifiers also sometimes take a minute to appear —
     > reload before assuming the worst.

   - **SKU** — an internal reference, never shown to anyone, unique within
     your account, and unchangeable. Put `kuwbb`.
   - **User Access** — **Full Access**.

4. Click **Create**.

You'll land on a page full of empty sections — screenshots, description,
pricing, age rating, privacy policy. **Ignore all of it.** That is App Store
submission. Internal TestFlight needs none of it.

> **Useful escape hatch:** you can still change the app record's bundle ID
> from its App Information page, right up until the first build is uploaded.
> After that it's fixed.

✅ **Done when** the app is in your Apps list with a **TestFlight** tab.

> **On the name.** This is an unofficial fan app using a university's name.
> For TestFlight, among your own testers, that's a non-issue. If you ever
> consider the public App Store, a university's marks are worth a thought
> first — not a reason to stop now, just better flagged early than discovered
> at submission.

---

## Part 3 — The API key

> **If you kept the volleyball `.p8`, skip this entire part.** Reuse that key:
> copy its four values straight into part 4. It is already proven against this
> exact build, which is a better guarantee than anything below.

This key lets GitHub's computers build and upload without you. It's the
fiddliest part — take it slowly, and note that **nothing here can be edited
afterwards**.

### 3a. Find the key screen

1. In **App Store Connect**, click **Users and Access**.

2. Click the **Integrations** tab.

   > Older guides say to look for a **Keys** tab. That tab is gone — the row
   > now reads People, Sandbox, Integrations, Xcode Cloud.
   >
   > Confusingly, **"Keys" still exists one level in, as a sidebar heading**
   > inside Integrations. So a guide saying "Users and Access → Integrations →
   > Keys → App Store Connect API" is describing the sidebar, and is correct.
   > Don't give up when the tab isn't there.

3. In the left sidebar, under the heading **Keys**, click **App Store Connect
   API**. (Usually already selected. The other entry, "In-App Purchase", is a
   different kind of key.)

4. Make sure the **Team Keys** sub-tab is selected, not "Individual Keys".

   > Individual keys don't have an Issuer ID at all, so one would break the
   > build in a way that's hard to diagnose.

### 3b. Generate it — and choose Admin

1. Click **Generate API Key** (or **+** if you already have keys).

2. - **Name** — for your reference only; not part of the key. Put
     `GitHub Actions`.
   - **Access** — choose **Admin**.

> ⚠️ **Choose Admin, and don't let anything talk you into App Manager.**
>
> Nearly every guide online — including Apple's own GitHub Action README —
> says App Manager is the minimum. That's true for *uploading an
> already-signed app*. It is not true for what we do, which is **cloud
> signing**: Apple mints the distribution certificate at upload time, which is
> exactly why you need no Mac and no certificate of your own.
>
> Granting a key that power needs the "Access to Cloud Managed Distribution
> Certificate" permission — and that is a **per-user checkbox that has no
> equivalent on the key screen**. The only way a key gets it is by being
> Admin. An App Manager key fails like this, deep inside the build:
>
> ```
> error: exportArchive: Cloud signing permission error
> You haven't been given access to cloud-managed distribution certificates.
> ```
>
> Developer-level is worse still: Apple's permissions matrix gives it no
> ability to create distribution profiles at all.
>
> **And the role cannot be changed later.** Apple: once a key is generated
> *"you can't edit its name or access level."* Getting this wrong means
> revoking, regenerating, and redoing both secrets.

3. Click **Generate**.

### 3c. Download the `.p8` — one chance only

The key appears with a **Download API Key** link.

**Click it now.** Apple allows this download exactly once and keeps no copy.
Lose it and the only cure is to revoke the key and make another.

A file named like `AuthKey_ABCD123456.p8` lands in Downloads. **Note that
filename** — you need it in a moment, and the Key ID is embedded in it.

### 3d. Collect the four values

**`ASC_KEY_ID`** — the **KEY ID** column on the key's row. Ten characters.
It's also the part of the filename after `AuthKey_`.

**`ASC_ISSUER_ID`** — on the same page, **above** the list, labelled
**Issuer ID**. Use the **Copy** button beside it rather than selecting by hand.

**`ASC_KEY_P8`** — the file's *contents*, not its name.

> ⚠️ **Name the file exactly. Do not use a `*` wildcard.** You probably still
> have the volleyball `AuthKey_*.p8` in Downloads, and a wildcard silently
> glues both keys together into one clipboard. GitHub accepts it without
> complaint and Apple then rejects it with an error that says nothing about
> the real cause.

- **macOS** — list them first, then copy the one you want by its real name:

  ```
  ls ~/Downloads/AuthKey_*.p8
  cat ~/Downloads/AuthKey_ABCD123456.p8 | pbcopy
  ```

- **Windows** — same idea in PowerShell:

  ```
  Get-ChildItem $HOME\Downloads\AuthKey_*.p8
  Get-Content $HOME\Downloads\AuthKey_ABCD123456.p8 -Raw | Set-Clipboard
  ```

  Use PowerShell rather than copying out of Notepad: Notepad can turn the
  line endings into Windows ones, which some signing libraries reject while
  reporting it as a bad key.

- **By hand** — open it in a plain text editor (TextEdit, Notepad — *not*
  Word) and copy everything, **including** the `-----BEGIN PRIVATE KEY-----`
  and `-----END PRIVATE KEY-----` lines. Those lines are part of the key.
  Omitting them is the most common mistake in this whole document.

**`APPLE_TEAM_ID`** — back on the *other* site:

1. **https://developer.apple.com/account**
2. Click **Membership details** in the top navigation.
3. **Team ID** is listed there. Ten characters.

> Check this is the Team ID and not the Key ID you noted minutes ago. Same
> shape, different value, classic mix-up.

✅ **Done when** you have all four, and the `.p8` saved somewhere you'll find
it next time.

---

## Part 4 — Put them into GitHub

1. Go to **https://github.com/inspectorgad/ku-wbb/settings/secrets/actions**
   (the long way: repository → **Settings** → **Secrets and variables** →
   **Actions**).

2. Click **New repository secret**, and repeat for each of the four:

   | Name | Value |
   |---|---|
   | `APPLE_TEAM_ID` | the 10 characters from Membership details |
   | `ASC_ISSUER_ID` | the long dashed code |
   | `ASC_KEY_ID` | the 10 characters from the key's row |
   | `ASC_KEY_P8` | the entire file, BEGIN and END lines included |

   Type the names **exactly** — capitals, underscores, no spaces.

   > ⚠️ **A misspelled name doesn't produce an error.** GitHub hands the
   > workflow an empty string instead, so `ASC_KEYID` fails much later with a
   > confusing Apple message rather than an obvious one. Copy the names from
   > the table.

   For `ASC_KEY_P8` the box will show several lines. That's correct — don't
   join them, don't add quotes, don't base64 anything.

3. **Delete the `.p8` from Downloads** — after saving your own copy somewhere
   private. GitHub has it now, and a private key loose in Downloads is a
   liability.

> **Secrets can't be read back.** GitHub shows the name and never the value.
> So you fix a typo by overwriting: open the secret, paste again, update. You
> don't need to know what's in there now.

✅ **Done when** four secrets are listed.

---

## Part 5 — Check it worked

You shouldn't have to wait for a 40-minute Mac build to discover a typo.

Tell me when parts 1–4 are done and I'll add an **"Apple keys check"**
workflow — the same one volleyball uses. You run it from the **Actions** tab
and in about thirty seconds it reports:

```
PASS  APPLE_TEAM_ID is set
PASS  APPLE_TEAM_ID looks right
PASS  ASC_KEY_P8 is a whole .p8 file
PASS  the two IDs are different values
PASS  App Store Connect accepts the key
PASS  the app exists in App Store Connect - "KU WBB"
PASS  the App ID is registered
```

It asks Apple real questions with your real key and prints **only** pass/fail
lines — no secret is ever printed, so the log is safe to read and safe to send
me. When something's wrong it names which of the four it is, turning "the
build failed" into "the Key ID and Team ID are swapped".

---

## Part 6 — The one step after the first build

The first build will upload, process, and then sit there reaching nobody,
because **internal testing goes through a group and a new app has none**.
Owning the account does not make you a tester, and the TestFlight app on your
phone shows an empty list without saying why.

Apple's own name for that state says so, if you know to read it: a build
marked **"Ready to Test"** means *"Your build can be tested by external and
internal testers, but you haven't added any testers yet."*

Once, in App Store Connect:

1. Your app → the **TestFlight** tab
2. In the sidebar, the **+** next to **Internal Testing**
3. Name the group — `Internal` will do — and **tick "Enable automatic
   distribution"**
4. **Invite Testers** → tick yourself → **Add**

> **Tick that checkbox.** Without it, Apple's words are: *"you must manually
> add all builds to the group"* — every build, forever. With it, each one
> reaches your phone on its own.

You can only invite people who are already in **Users and Access**; internal
testers are App Store Connect users, up to 100 of them, and builds last 90
days.

The **iOS build status** workflow in the Actions tab reports all of this —
the groups, who is in them, and whether the newest build has actually
reached anybody.

## What you do *not* have to do

Most guides to shipping an iPhone app are mostly these. We skip every one:

- **No Mac.** GitHub rents one per build.
- **No Xcode.**
- **No certificates.** Normally you'd generate one, store it safely and renew
  it yearly. Cloud signing means Apple keeps it and applies it at upload —
  that's what the Admin key in part 3 buys.
- **No registering your iPhone's device ID.** TestFlight doesn't need it.
- **No provisioning profiles** to download and babysit.
- **No screenshots, description, pricing, age rating or privacy policy.**
- **No waiting for review.** Internal TestFlight skips it entirely. (External
  testers need Beta App Review; you're not using those.)
- **No export-compliance question** — with one caveat worth knowing.

> **The export-compliance caveat.** This one is *not* automatically harmless.
> Every TestFlight upload normally asks whether your app uses encryption, and
> until it's answered the build sits in **"Missing Compliance"** and never
> reaches testers. It is the single commonest reason an uploaded build never
> shows up on the phone.
>
> You won't see it, because the app answers in advance: its `Info.plist` will
> carry `ITSAppUsesNonExemptEncryption = false`, which Apple accepts as a
> standing answer. That's my job in phase 4, and volleyball already ships it.
> I'm noting it so that if a build ever does stall in Missing Compliance, you
> know what it is and that it's mine to fix, not yours.

---

## When something goes wrong

| What you see | What it means |
|---|---|
| The **Create** button won't complete the new app | An agreement is unsigned. App Store Connect → **Business**. |
| The bundle ID isn't in the dropdown in part 2 | Part 1 didn't finish, you made a *wildcard* instead of explicit, or you're in a different team. |
| "An App ID with Identifier … is not available" | Already registered — possibly by you in an earlier attempt. Look in the Identifiers list rather than creating it again. |
| The app name is rejected | Someone has it. Pick another; it's cosmetic for now. |
| There's no **Integrations** tab | Usually permissions, not a rename — generating team keys needs Account Holder or Admin. |
| You closed the page before downloading the `.p8` | Revoke the key, generate another. Then update `ASC_KEY_ID` and `ASC_KEY_P8`; Issuer ID and Team ID are unchanged. |
| `Cloud signing permission error` during a build | The key is App Manager or Developer, not Admin. Revoke it and generate an Admin key. |
| The checker says the Issuer ID, Key ID and `.p8` don't belong together | One is from a different key, or the Key ID is actually the Team ID. |
| The checker says the `.p8` isn't a whole file | The `BEGIN`/`END` lines were left out — or a `*` wildcard concatenated two keys. |
| A build sits in **Missing Compliance** | The `Info.plist` key is missing. Mine to fix. |

---

## After this

Nothing on your side. When the code reaches phase 4 I'll add the iOS project
and the build workflow, run it, and the app appears in TestFlight on your
phone 5–30 minutes later while Apple processes the upload.

You'll need the **TestFlight** app from the App Store on your iPhone, signed
in with the same Apple ID — a thirty-second job for later, not now.
