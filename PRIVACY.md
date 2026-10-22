# Trawl's privacy, and how to check it yourself

Trawl says it cannot send your files anywhere. You don't have to take our word for it.

## What we claim
- **No internet permission.** The Android app cannot go online.
- **No account, no ads, no analytics.**
- **Reports only if you send them.** A problem report is shown to you first and leaves only when you press Send, through another app of your choice.
- **The desktop preview makes no network connections.** Every desktop build is run for ten minutes with every feature on while all of its connections are watched, and it must show none (see "Desktop" below).

## Why the internet permission matters (Android)
Android describes the internet permission as one that "allows applications to open network sockets", which is how an app talks to the internet. It applies to the whole app, including every library inside it. Without it, nothing in Trawl can go online. Android grants it silently at install, which is why the way to know is to read the app's permission list. Sources: [Manifest.permission](https://developer.android.com/reference/android/Manifest.permission), [Permissions on Android](https://developer.android.com/guide/topics/permissions/overview).

## Check it yourself
**On the phone, in a minute:** install Trawl, get the models once over Wi-Fi, then turn on Airplane mode and turn Wi-Fi off. Search still works exactly the same.

**On a computer, in two minutes:** download the APK from the [release page](https://github.com/Crane-AI-Labs/trawl/releases), install Android's command-line tools, and run:

```
$ apkanalyzer manifest permissions trawl-1.0.0.apk
android.permission.READ_MEDIA_VIDEO
android.permission.READ_MEDIA_AUDIO
android.permission.FOREGROUND_SERVICE
android.permission.READ_MEDIA_IMAGES
android.permission.RECEIVE_BOOT_COMPLETED
android.permission.MANAGE_EXTERNAL_STORAGE
android.permission.READ_MEDIA_VISUAL_USER_SELECTED
android.permission.USE_BIOMETRIC
android.permission.ACCESS_NETWORK_STATE
android.permission.WAKE_LOCK
android.permission.POST_NOTIFICATIONS
android.permission.FOREGROUND_SERVICE_DATA_SYNC
```

`android.permission.INTERNET` is not there. One more line, a permission named after Trawl itself, is left out above. Trawl Go's APK gives the same list. `aapt2 dump permissions` gives the same answer. The release notes carry each file's SHA-256 and the signing certificate's fingerprint, so you can check you have the genuine file.

| Permission | What it lets Trawl do | Why |
|---|---|---|
| MANAGE_EXTERNAL_STORAGE | Read documents anywhere on the phone | So Trawl can search them; with no internet permission it cannot upload them |
| READ_MEDIA_IMAGES / VIDEO / AUDIO | Read those kinds of files | So Trawl can search them |
| READ_MEDIA_VISUAL_USER_SELECTED | Android's "selected photos only" option | So Trawl still works if you choose it |
| POST_NOTIFICATIONS | Show a notification | To show progress |
| USE_BIOMETRIC | Android's own unlock prompt | For Trawl's app lock; Android does the check, Trawl never sees your fingerprint or face |
| FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC | Run with an ongoing notification | So the overnight reading can finish |
| WAKE_LOCK, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED | Keep awake; ask whether the phone is connected; hear about a reboot | Brought in by the background-work library (androidx.work); none of them lets an app send anything |

## Desktop (developer preview)
A desktop operating system has no app-level internet permission, so we prove the promise differently. Every release build runs the zero-network gate (`desktop/scripts/net-gate.py`). It is a scripted ten-minute session of the packaged app: first run, models, indexing documents, photos, a video and a recording, the private-photo pass, searches, the quick panel and Settings. While it runs, every connection the app and its helper processes open is watched: `strace` on Linux, `lsof`/`nettop` on macOS, `Get-NetTCPConnection` on Windows. A deliberate test connection proves the watcher works. The gate fails on any connection that isn't on the computer itself. The 1.0.0 preview showed none on macOS, Windows and Linux.

## What this does not cover
- **The one time you go online:** Trawl's *Get the models* opens your browser on the right file, and your browser downloads it. Trawl itself sends nothing.
- **Things you choose to send:** a problem report goes out through another app, only after you read it and press Send.
- **Other versions:** we checked 1.0.0. Repeat the check after an update.
- **Other apps:** this covers Trawl, not your browser, keyboard or chat apps.
