# ImageGuard

An Android app that removes explicit/pornographic images from your phone and filters them out of
web searches, websites and ads. Photos are checked on the phone and are never uploaded.

## What it does

- **Auto-protect**: watches the photo library and checks every new photo, screenshot and download as
  soon as it is saved. Explicit images are removed straight away, and you get a notification.
  Restarts by itself after a reboot.
- **Scan all photos**: checks every image already on the phone (Gallery, Downloads, WhatsApp, etc.).
- **Removed images**: flagged images are moved into a private, hidden quarantine, not deleted outright.
  You can restore false alarms (they won't be flagged again) or delete everything permanently.
- **Sensitivity**: choose how strict it is (default: remove anything scoring 60% or higher).
- **Web search, adult site & ad filter** (works in every browser and app):
  - forces **SafeSearch** on Google, Bing and DuckDuckGo, so explicit images are hidden from
    search and image results (YouTube is deliberately left out of Restricted Mode: its lookups
    skip the family DNS and go to Cloudflare 1.1.1.1);
  - blocks ~150,000 adult, advertising, tracker and malware domains (StevenBlack hosts list);
  - looks up every other site through **AdGuard Family DNS**, a second filter for adult sites and ads;
  - blocks common ways apps get around it (Firefox/Chrome encrypted DNS, hard-coded DNS servers).

  It runs as a local VPN that only handles DNS (website-name lookups). Your other traffic is not
  routed through it. AdGuard sees which site names are looked up, not the pages or content.

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
5. Switch on **Filter web searches, adult sites & ads** and accept the VPN request. Then:
   - tap **Make it always-on**, tap ⚙️ next to ImageGuard, and turn on **Always-on VPN** and
     **Block connections without VPN** (keeps it running and makes it harder to switch off);
   - in Chrome: Settings → Privacy and security → **Use secure DNS** → off;
   - in phone Settings → Connections → More connection settings → **Private DNS** → Off or Automatic.

## Limits

- The photo scanner checks **image files** saved on the phone, not videos.
- The web filter works per website, not per image: it can't hide individual explicit images on
  general-purpose sites that aren't blocked (e.g. Reddit, X/Twitter), and ads served from the same
  domain as the content (YouTube, Instagram, Facebook in-app ads) still show.
- Only one VPN can run at a time, so it can't be combined with another VPN app.
- No classifier is perfect: some explicit images may be missed and some normal photos
  (e.g. swimwear) may be flagged. Use the sensitivity slider and the Removed-images screen to tune it.

## Building

GitHub Actions builds the APK on every push (`.github/workflows/imageguard-apk.yml`), runs the unit
tests, and publishes the APK as a release. To build locally: install the Android SDK, save the domain
list from the workflow's *Download web blocklist* step as `app/src/main/assets/blocklist.txt`
(optional; without it only AdGuard's filtering applies), and run `./gradlew assembleRelease`.

### Stable signing key (optional)

Without one, each build is signed with a throwaway key, so updating means uninstalling the old
version first (which empties the quarantine). To sign every build with the same key, create one with

    keytool -genkeypair -v -keystore release.jks -alias imageguard -keyalg RSA -keysize 2048 -validity 10000

and add these repository secrets: `IG_KEYSTORE_BASE64` (output of `base64 -w0 release.jks`),
`IG_KEYSTORE_PASSWORD`, `IG_KEY_ALIAS`, `IG_KEY_PASSWORD`. Never commit the keystore.

## Credits

Web blocklist: [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (MIT), downloaded at build
time. Upstream filtering: [AdGuard Family DNS](https://adguard-dns.io/en/public-dns.html).

The classifier is Yahoo's [open_nsfw](https://github.com/yahoo/open_nsfw) model (BSD 2-Clause),
using the TensorFlow Lite conversion from
[devzwy/open_nsfw_android](https://github.com/devzwy/open_nsfw_android) (Apache 2.0).
