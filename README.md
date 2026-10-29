# Aayiravilly Temple Android

A lightweight Android app for [Chittattinkara Sree Aayiravilly Temple](https://aayiravillytemplechittattinkara.ai.studio/). Android 8.0 or later is required.

[Download the latest APK](https://github.com/Devan920/aayiravilly-temple-android/releases/latest)

## Install and use

Download the APK from the latest GitHub release and open it on an Android phone. Android may ask you to allow installation from the browser or file manager you used. The app includes quick links to the temple, announcements, pooja times and gallery, plus refresh, offline retry and fullscreen video support.

The app checks GitHub for newer versions when it comes to the foreground, at most once every six hours. **App update പരിശോധിക്കുക** in the top-right menu checks immediately. A new version shows a download prompt. Downloads are checked against the published SHA-256, application ID, increasing version code and the installed app's signing certificate. Android requires installation confirmation; this app cannot silently install itself. Update checks need internet access and are not scheduled while the app is closed.

Website changes that are published to the live URL appear on a fresh load or refresh. The current website's admin panel saves content to browser-local storage. Those edits are not shared with other devices: a shared backend and the website source are needed for true cross-device announcement synchronization. This repository contains the Android client, not the AI Studio website source.

The native shell uses hardware acceleration, normal HTTP caching and background update/network work. Startup and scrolling also depend on the website, Android System WebView, internet connection and device. A native wrapper alone cannot guarantee zero lag.

## Build locally

Use JDK 17 or 21 and the Android SDK with `platforms;android-36.1` and `build-tools;36.0.0`. Set `ANDROID_HOME` or create an untracked `local.properties` with `sdk.dir`. Gradle 8.13 is supplied through the wrapper.

```sh
bash gradlew assembleDebug
```

The debug application has a `.debug` package suffix and is separate from the installed release. The public update manifest is for the release package only.

For a signed release, set these environment variables using the original private signing backup, then build:

| Variable | Value |
| --- | --- |
| `TEMPLE_KEYSTORE` | Absolute path to the original release keystore |
| `TEMPLE_STORE_PASSWORD` | Keystore password |
| `TEMPLE_KEY_ALIAS` | `temple` |
| `TEMPLE_KEY_PASSWORD` | Key password |

```sh
bash gradlew assembleRelease -PappVersionName=1.0.1 -PappVersionCode=2
```

Windows users can run `gradlew.bat` instead of `bash gradlew`. The result is `app/build/outputs/apk/release/app-release.apk`. Keep the signing key and passwords private and backed up: future compatible updates must use this same signing key. Never commit or upload the private signing backup as a release asset.

## Publish a future update

The **Publish Android APK** GitHub Actions workflow builds and publishes a signed APK after you explicitly start it. It runs only from `main` and does not publish on ordinary source pushes.

The repository's signing secrets have been configured for releases. If migrating to another repository, the required Actions secrets are:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64 encoding of the original release keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | `temple` |
| `ANDROID_KEY_PASSWORD` | Key password |

Push and review the intended changes, open **Actions → Publish Android APK → Run workflow**, select `main`, and enter a new version name, a larger integer version code, and release notes. For example, after `1.0.0` / `1`, use `1.0.1` / `2`. The workflow validates the previous release, signs the APK, uploads a complete draft, then publishes the APK and `update.json` together. Existing app installations discover that release at their next update check. A failed upload remains a draft for inspection; do not publish an incomplete draft manually.

The stable update endpoint is:

`https://github.com/Devan920/aayiravilly-temple-android/releases/latest/download/update.json`

This distribution uses GitHub APK releases. It is not published in Google Play. A future Play distribution needs a separate build that removes the sideload installation permission and uses Play's update mechanism, plus the owner's Play Console listing and review.
