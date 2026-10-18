# App Cloner Studio — Google Play readiness checklist

> Status of this build (v2.0): release-signed, minified, English UI.
> This checklist is honest about what is ready and what is risky.

## 1. Technical readiness

- [x] `targetSdk 35` (Play requires targeting a recent API level)
- [x] 16 KB page-size compatibility: the app ships **no native libraries**
      (pure Kotlin/Java + Jetpack Compose), so there is nothing to rebuild
      for 16 KB ELF alignment.
- [x] Release signing with a dedicated keystore
      (`keystore/cloner-release.jks`, validity 30 years).
      **Back up this keystore + `keystore.properties` somewhere safe —
      losing it means you can never update the app again.**
      (Play App Signing can hold the upload key, but keep your own copy.)
- [x] `minSdk 26` — covers ~99% of active devices; fine for Play.
- [x] Versioning: `versionCode 6`, `versionName "2.0"`.
- [x] English-only resources (`resConfigs("en")`), minify + resource shrink
      enabled for a smaller download.
- [ ] **App icon / feature graphic / screenshots** — still needed for the
      store listing (512px icon, 1024x500 feature graphic, 2+ screenshots).
- [ ] **Privacy policy URL** — mandatory for Play. Must state: the app runs
      locally on the device, lists installed apps (via QUERY_ALL_PACKAGES)
      only to let the user pick what to clone, and **no personal data
      leaves the device** (true for this build: Room DB, sandbox dirs and
      crash logs all stay in app-private storage).
- [ ] **Data Safety form** — declare: no data collected, no data shared.
      (Accurate today. Revisit the moment any analytics/ads SDK is added.)

## 2. QUERY_ALL_PACKAGES declaration (sensitive permission)

Play treats `QUERY_ALL_PACKAGES` as sensitive. In Play Console
(App content → Sensitive permissions) you must declare a core use case.
What to write (adapt honestly):

> "App Cloner Studio's core functionality is creating isolated instances
> of apps the user has installed. To do this the app must enumerate the
> installed applications so the user can choose which one to clone.
> The package list never leaves the device."

**Honest note:** app cloners sit in a grey area. Play's policy says the
permission must be "core to the app's functionality" — it genuinely is
here — but reviewers have wide discretion, and similar utilities have
been both approved and rejected. There is no way to guarantee approval.

## 3. RISK SECTION — read before uploading

The virtualization core of this app (loading and executing *other apps'*
code inside our process, hooking `Instrumentation`/`ActivityThread` via
reflection, hidden-API access) can be flagged by Play's automated review
under:

- **Malware policy** — code that "interferes with other apps" or uses
  "deceptive" techniques;
- **Device and Network Abuse** — unauthorized access to, or modification
  of, other apps' behavior;
- **Deceptive Behavior** — an app whose real behavior (running third-party
  APK code in-process) doesn't match a casual description.

Real-world precedent cuts both ways: several "parallel space"
utilities have lived on Play for years; others (including big names)
were removed without a detailed explanation. **Approval is NOT
guaranteed, and a rejection can come with a strike against the developer
account.** Mitigations worth doing before submission:

1. Write the store description plainly: say exactly what the app does
   (runs isolated copies of installed apps in a virtual environment).
2. Keep the permission set minimal (today: only QUERY_ALL_PACKAGES +
   INSTALL_SHORTCUT — do not add more).
3. Never bundle the app with unrelated monetization SDKs at launch;
   each extra SDK is another review surface.
4. Consider a closed-test track first to see if review passes before a
   production rollout.

## 4. Monetization warning — AdMob / AdSense

**Do NOT integrate AdMob into this app.** Reasons:

1. AdMob policy enforcement is **account-level**. A suspension or
   "invalid activity" flag on this app's ad units hits the whole Google
   account.
2. That same Google account/payments profile is shared with the owner's
   **AdSense** (websites). One enforcement action can cascade across
   AdMob *and* AdSense earnings.
3. Utility apps with sensitive permissions + ads are a classic trigger
   for "invalid traffic" reviews.

If you want ad revenue from this project, ship monetization in a
**separate, policy-clean app** under a separate surface — never bolt it
onto the cloner. For this app, safer options are: a paid "Pro" unlock
via Play Billing, or no monetization at all.

## 5. Release hygiene

- [ ] `versionCode` bumped for every Play upload (Play rejects reuse).
- [ ] Test the **release** APK on a real device before uploading
      (minify can break reflection — our ProGuard rules keep
      `model.**` and `engine.**` for this reason).
- [ ] Keep `keystore/` out of any public repo (it is gitignored-local
      only; never commit or share it).
- [ ] Remember: the Play signature (upload key) differs from the old
      debug builds — existing testers must uninstall v1.x once.
