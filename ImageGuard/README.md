# ImageGuard

An Android app that finds explicit/pornographic images on your phone and removes them automatically.
Everything runs on the phone: the app has **no internet permission**, so photos are never uploaded.

## What it does

- **Auto-protect**: watches the photo library and checks every new photo, screenshot and download as
  soon as it is saved. Explicit images are removed straight away, and you get a notification.
  Restarts by itself after a reboot.
- **Scan all photos**: checks every image already on the phone (Gallery, Downloads, WhatsApp, etc.).
- **Removed images**: flagged images are moved into a private, hidden quarantine, not deleted outright.
  You can restore false alarms (they won't be flagged again) or delete everything permanently.
- **Sensitivity**: choose how strict it is (default: remove anything scoring 60% or higher).

## Install on a Samsung phone

1. Open the latest **ImageGuard** release on this repository's *Releases* page on your phone and
   download `ImageGuard-N.apk`.
2. Open the file. If asked, allow your browser / My Files to **install unknown apps**.
   (If Samsung **Auto Blocker** is on, turn it off in Settings → Security and privacy → Auto Blocker first.)
3. Open ImageGuard and complete setup:
   - **Allow file access** → switch on *Allow access to manage all files*.
   - **Allow notifications**.
   - **Turn off battery optimisation** → *Allow*. On Samsung also check
     Settings → Battery → Background usage limits and make sure ImageGuard is not in *Sleeping/Deep sleeping apps*.
4. Switch on **Auto-protect**, then tap **Scan all photos now**.

## Limits

- It checks **image files** saved on the phone. It does not scan videos, and it cannot block images
  shown inside other apps or websites that are never saved to storage.
- No classifier is perfect: some explicit images may be missed and some normal photos
  (e.g. swimwear) may be flagged. Use the sensitivity slider and the Removed-images screen to tune it.

## Building

GitHub Actions builds the APK on every push (`.github/workflows/imageguard-apk.yml`) and publishes it
as a release. To build locally: install the Android SDK and run `./gradlew assembleRelease` in this folder.

### Stable signing key (optional)

Without one, each build is signed with a throwaway key, so updating means uninstalling the old
version first (which empties the quarantine). To sign every build with the same key, create one with

    keytool -genkeypair -v -keystore release.jks -alias imageguard -keyalg RSA -keysize 2048 -validity 10000

and add these repository secrets: `IG_KEYSTORE_BASE64` (output of `base64 -w0 release.jks`),
`IG_KEYSTORE_PASSWORD`, `IG_KEY_ALIAS`, `IG_KEY_PASSWORD`. Never commit the keystore.

## Credits

The classifier is Yahoo's [open_nsfw](https://github.com/yahoo/open_nsfw) model (BSD 2-Clause),
using the TensorFlow Lite conversion from
[devzwy/open_nsfw_android](https://github.com/devzwy/open_nsfw_android) (Apache 2.0).
