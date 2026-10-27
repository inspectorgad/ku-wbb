# Setting up Apple for the iPhone app

Everything else in this port is code, and I do it. **These are the three
things only you can do**, because they happen inside your Apple account and
nobody else can sign in to it.

You do them in a web browser. **You do not need a Mac**, you do not need
Xcode, and you do not need to plug in a phone. Set aside about 30 minutes.

Nothing here is urgent — the Android build keeps working throughout, and the
code isn't blocked until phase 4. Do it early anyway: if something is wrong
with a brand-new Apple account, the problem surfaces in *days*, not minutes,
and it's better to find that out now than on the day everything else is ready.

---

## What you are producing

Four values. That's the whole output of this document.

| Secret name | What it is | What it looks like |
|---|---|---|
| `APPLE_TEAM_ID` | Identifies your developer team | `A1B2C3D4E5` — 10 capitals and digits |
| `ASC_ISSUER_ID` | Identifies your team's API keys as a group | `69a6de7e-1a2b-3c4d-5e6f-7a8b9c0d1e2f` — a long dashed code |
| `ASC_KEY_ID` | Identifies one specific key | `ABCD123456` — 10 capitals and digits |
| `ASC_KEY_P8` | The key itself: a small text file | starts `-----BEGIN PRIVATE KEY-----` |

At the end you paste those four into GitHub, and the iPhone build can sign
itself and deliver to your phone without any of it ever touching this
repository.

### The three IDs people mix up

This is the single most common way this goes wrong, so it's worth reading
once before you start. There are three different identifiers and they are all
called "ID":

- **Team ID** — *one per developer account*. Ten characters. Lives on
  Apple's developer site, not App Store Connect.
- **Issuer ID** — *one per account*, shared by every API key you ever make.
  Long, with dashes. Lives above the list of keys.
- **Key ID** — *one per key*. Ten characters, so it **looks exactly like the
  Team ID** and that's the trap. Lives in the row of the key you created.

If the build later fails to sign, it is almost always because the Team ID and
the Key ID got swapped. There's a checker at the end of this document that
catches that in thirty seconds.

### Where to write these down

In your password manager, or Apple Notes, or anywhere private.

**Not** in this repository, not in a file in the project folder, and not in a
chat message. The `.p8` file especially: anyone holding it can upload apps as
you. GitHub's secret storage (part 4) is the right home for it; the copy in
your Downloads folder should be deleted once it's safely pasted in.

---

## Part 1 — Register the App ID

This tells Apple that an app called `io.github.inspectorgad.kuwbb` exists and
belongs to you. That string is the app's permanent name as far as Apple is
concerned — it already matches what the Android app uses, which is deliberate.

1. Go to **https://developer.apple.com/account** and sign in.

2. Find **Certificates, Identifiers & Profiles**. On the account home page
   it's one of the large links in the middle; if you've landed somewhere
   else, the direct address is
   **https://developer.apple.com/account/resources/identifiers/list**.

3. In the left sidebar, click **Identifiers**.

4. Click the blue **⊕** (a plus in a circle) next to the "Identifiers"
   heading.

5. You're asked what kind of identifier to register. Choose **App IDs**, then
   **Continue**.

6. Now it asks for a *type*. Choose **App** (not "App Clip"), then
   **Continue**.

7. Fill in the form:

   - **Description** — free text, for your eyes only, and it is **not** the
     name anyone sees. Put `KU Women's Basketball`. Apple rejects punctuation
     here, so no apostrophe: use `KU Womens Basketball` if it complains.
   - **Bundle ID** — make sure **Explicit** is selected (not "Wildcard"), and
     type exactly:

     ```
     io.github.inspectorgad.kuwbb
     ```

     Check it character by character. A typo here is painful to undo: Apple
     does not let you edit or delete a bundle ID once an app record uses it.

8. Below that is a long list of **Capabilities**. These are iOS features an
   app is allowed to use. **Leave them all unticked.** The app doesn't use
   any of them yet.

   > One judgement call: if you think you may eventually want the game-night
   > watcher to send alerts to your phone, you can tick **Push Notifications**
   > now. It costs nothing to have it ticked and unused, and it saves a return
   > trip. It is also perfectly easy to come back and tick it later — the
   > cloud signing we use picks up the change automatically. Either choice is
   > fine; unticked is the simpler default.

9. **Continue**, check the summary, then **Register**.

✅ **Done when** `io.github.inspectorgad.kuwbb` appears in the Identifiers
list.

---

## Part 2 — Create the app record in App Store Connect

Part 1 registered the *identifier*. This creates the *app* — the thing that
holds builds, testers and TestFlight.

These are two different Apple websites with two different purposes, which is
confusing at first. `developer.apple.com` is for identifiers and keys;
`appstoreconnect.apple.com` is for apps and builds. You'll use both.

1. Go to **https://appstoreconnect.apple.com** and sign in with the same
   Apple ID.

2. Click **Apps**. (If your account shows an older layout, this may read
   **My Apps** — same place.)

3. Click the blue **⊕** above the list, then **New App**.

4. A dialog appears. Field by field:

   - **Platforms** — tick **iOS** only.
   - **Name** — this *is* public, and it must be unique across the entire App
     Store. Try **`KU WBB`**, which matches the Android app's name. If Apple
     says it's taken, `KU Women's Basketball` or `Jayhawks WBB` will do. Since
     this is only going to TestFlight, the name matters little for now and can
     be changed later.
   - **Primary Language** — **English (U.S.)**.
   - **Bundle ID** — a dropdown. Pick
     **`io.github.inspectorgad.kuwbb`** from it.

     > If it isn't in the list, part 1 didn't finish. Go back and check the
     > identifier was registered. (Occasionally it takes a minute to appear;
     > reloading the page is worth a try before assuming the worst.)

   - **SKU** — an internal reference number. It is never shown to anyone, it
     just has to be unique within *your* account, and it cannot be changed.
     Put `kuwbb`.
   - **User Access** — **Full Access**.

5. Click **Create**.

You'll land on the app's page with a lot of empty sections — screenshots,
description, pricing, age rating, a privacy policy. **Ignore all of it.**
Those are for submitting to the App Store. A build going to TestFlight for
you and people on your own team needs none of them filled in.

✅ **Done when** the app appears in your Apps list and has a **TestFlight**
tab across the top.

> **About the name.** This is an unofficial fan app that uses a university's
> name. For TestFlight, among your own testers, that is a non-issue. If you
> ever decide to put it on the public App Store, a university's marks are
> worth a thought first — not a reason to stop now, just something I'd rather
> flag early than have you discover at submission.

---

## Part 3 — Create the API key, and collect all four values

This is the key that lets GitHub's computers build and upload the app without
you. It is the fiddliest part, so take it slowly.

### 3a. Create the key

1. Still in **App Store Connect**, click **Users and Access** in the top bar.

2. Look along the row of tabs for **Integrations**. Click it.

   > Apple used to call this tab **Keys**. If your account still shows
   > "Keys", that's the same place — click that instead.

3. In the sidebar under that tab, select **App Store Connect API**, and make
   sure you are on **Team Keys** rather than "Individual Keys". A team key
   belongs to the account; an individual key belongs to you personally and is
   not what we want.

4. Click **⊕** to generate a key.

5. - **Name** — for your reference only. Put `GitHub Actions`.
   - **Access** — choose **App Manager**.

     > This is the one setting worth getting right first time. "Developer"
     > sounds like the obvious choice and is **not** enough — the key has to
     > be allowed to manage the signing certificates Apple creates for us,
     > which a Developer-level key cannot do. App Manager can. The failure if
     > you pick too low a role arrives forty minutes into a build with an
     > unhelpful message, so pick App Manager now.

6. Click **Generate**.

### 3b. Download the `.p8` — you only get one chance

The new key appears in the list with a **Download** link.

**Click it now.** Apple lets you download this file exactly once. There is no
"show it again". If you lose it, the only remedy is to revoke the key and
make a new one — not a disaster, but an annoyance, and the old one must then
be cleaned up.

A file called something like `AuthKey_ABCD123456.p8` lands in your Downloads
folder. Keep it there for the next few minutes; you'll paste its contents into
GitHub in part 4 and then delete it.

### 3c. Collect the four values

Now gather everything. Write each one down as you go.

**`ASC_KEY_ID`** — on the key's row in the list you just used, in the column
headed **KEY ID**. Ten characters.

**`ASC_ISSUER_ID`** — on the same page, **above** the list of keys, labelled
**Issuer ID**. It's long with dashes. There's a copy button next to it; use
that rather than selecting it by hand.

**`ASC_KEY_P8`** — the *contents* of the file you downloaded, not the
filename.

- **On a Mac**: open Terminal, and run:

  ```
  cat ~/Downloads/AuthKey_*.p8 | pbcopy
  ```

  That puts the whole file on your clipboard, exactly as it is. Nothing is
  displayed, so nothing ends up in your terminal history.

- **On Windows**: open PowerShell and run:

  ```
  Get-Content $HOME\Downloads\AuthKey_*.p8 -Raw | Set-Clipboard
  ```

- **Either way, by hand** is fine too: open the file in a plain text editor
  (TextEdit, Notepad — *not* Word) and copy everything, **including** the
  `-----BEGIN PRIVATE KEY-----` and `-----END PRIVATE KEY-----` lines. Those
  lines are part of the key, not decoration. Leaving them off is the single
  most common mistake here.

**`APPLE_TEAM_ID`** — this one is on the *other* website.

1. Go back to **https://developer.apple.com/account**.
2. In the sidebar, click **Membership details** (older accounts: **Membership**).
3. Your **Team ID** is listed there. Ten characters.

> Double-check this is the Team ID and not the Key ID from a moment ago. They
> are the same shape and this is the classic mix-up.

✅ **Done when** you have all four written down and the `.p8` on your
clipboard or open in front of you.

---

## Part 4 — Put them into GitHub

1. Go to **https://github.com/inspectorgad/ku-wbb/settings/secrets/actions**.

   (The long way round: the repository → **Settings** → in the sidebar,
   **Secrets and variables** → **Actions**.)

2. Click **New repository secret**.

3. For each of the four:

   - **Name** — type it **exactly** as written below. Capitals, underscores,
     no spaces. The build looks for these precise names.
   - **Secret** — paste the value.
   - Click **Add secret**, then **New repository secret** again for the next.

   | Name | Value |
   |---|---|
   | `APPLE_TEAM_ID` | the 10 characters from Membership details |
   | `ASC_ISSUER_ID` | the long dashed code |
   | `ASC_KEY_ID` | the 10 characters from the key's row |
   | `ASC_KEY_P8` | the entire file contents, BEGIN and END lines included |

   For `ASC_KEY_P8`, the box will show several lines. That's correct — don't
   try to join them into one. Don't add quotes around it.

4. Once `ASC_KEY_P8` is saved, **delete the `.p8` from your Downloads
   folder.** GitHub has it now, and a private key sitting in Downloads is a
   loose end.

> **You cannot read a secret back** after saving it. GitHub will show you the
> name but never the value. That's by design, and it means the way to fix a
> typo is to overwrite the secret with the correct value — click the secret,
> paste again, update. You don't need to know what's currently in there.

✅ **Done when** four secrets are listed on that page.

---

## Part 5 — Check it worked

You don't have to take any of this on trust, and you shouldn't have to wait
for a 40-minute Mac build to find out you mistyped something.

Once you tell me parts 1–4 are done, I'll add a small **"Apple keys check"**
workflow to this repository — the same one the volleyball app uses. You run it
from the **Actions** tab and in about thirty seconds it reports:

```
PASS  APPLE_TEAM_ID is set
PASS  APPLE_TEAM_ID looks right
PASS  ASC_KEY_P8 is a whole .p8 file
PASS  App Store Connect accepts the key
PASS  the app exists in App Store Connect - "KU WBB"
PASS  the App ID is registered
```

It asks Apple real questions using your real key, and prints only pass/fail
lines — **none of your secrets are ever printed**, so the log is safe to look
at and safe to share with me.

If something's wrong it says which of the four it is, which turns "the build
failed" into "the Key ID and the Team ID are swapped".

---

## What you do *not* have to do

Worth saying plainly, because most guides to shipping an iPhone app are full
of these and we skip every one:

- **No Mac.** GitHub rents us one for each build.
- **No Xcode.**
- **No certificates.** Normally you'd generate a signing certificate, store it
  somewhere safe and renew it yearly. We use *cloud signing*: Apple keeps the
  certificate and applies it at upload, using the API key from part 3.
- **No registering your iPhone's device ID.** That's for a different kind of
  build; TestFlight doesn't need it.
- **No provisioning profiles** to download and babysit.
- **No screenshots, description, pricing, age rating or privacy policy.**
  Those are App Store submission; internal TestFlight needs none of them.
- **No export compliance questions.** Normally every TestFlight upload asks
  whether your app uses encryption. The app declares the answer in its own
  settings (`ITSAppUsesNonExemptEncryption`), so the question never appears.
- **No waiting for Apple to review anything.** Internal TestFlight builds skip
  review entirely. External testers would need it; you're not using those.

---

## When something goes wrong

| What you see | What it means |
|---|---|
| The bundle ID isn't in the dropdown in part 2 | Part 1 didn't complete. Check the Identifiers list. |
| "An App ID with Identifier ... is not available" | That bundle ID is already registered — possibly by you, in an earlier attempt. Look for it in the Identifiers list rather than creating it again. |
| The app name is rejected | Someone has it. Pick another; it's cosmetic for now. |
| You closed the page before downloading the `.p8` | Revoke that key and generate a new one. Takes two minutes. Then update `ASC_KEY_ID` and `ASC_KEY_P8` in GitHub — the Issuer ID and Team ID don't change. |
| The checker says "the Issuer ID, Key ID and .p8 do not belong together" | One of those three is from a different key, or the Key ID is actually the Team ID. |
| The checker says the `.p8` isn't a whole file | The `BEGIN`/`END` lines were left out of the paste. |

---

## After this

Nothing, on your side. When the code reaches phase 4 I'll add the iOS project
and the build workflow, run it, and the app appears in the TestFlight app on
your phone 5–30 minutes later while Apple processes it.

You'll need the **TestFlight** app from the App Store on your iPhone, signed
in with the same Apple ID — but that's a thirty-second job for later, not now.
