# TV Home — project state & resume notes

Custom launcher built from scratch for the TCL Google TV (192.168.100.55), installed and
running. Everything needed to continue after a power cut lives in this folder.

## One command to resume

```
python resume.py
```

That script: reconnects over ADB (retrying, and telling you if the TV wants permission),
wakes the TV, keeps it awake, installs the current APK, sets **TV Home** as the default
home role, grants storage, zeroes the system animation scales, and saves a screenshot to
`build/resume_home.png`.

If the TV ever stops treating TV Home as home, run:

```
python resume.py --picker
```

and pick **TV Home** on the *Default home app* screen with the remote.

## Toolchain facts (do not re-download)

| Thing | Path |
|---|---|
| adb | `../tv-tools/adb.exe` |
| JDK 17 | `../tv-tools/dl/jdk/jdk-17.0.20.1+1` |
| android.jar (API 30) | `../tv-tools/dl/platform30/android-11/android.jar` |
| build-tools (aapt2/d8/apksigner) | `../tv-tools/dl/buildtools/android-14` |
| paths json | `../tv-tools/toolchain.json` |

Build only (no TV needed): `python build.py`
Rebuild + deploy: `python build.py` then `python resume.py`

## Signing key — keep it

`tvhome.keystore` (password `tvhome123`, alias `tvhome`) sits **outside** `build/` on
purpose. `build/` is wiped every build, so a key stored there gets regenerated and the
install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. If that error appears, the
script now uninstalls and reinstalls automatically.

## TV facts

- `192.168.100.55:5555`, TCL "Smart TV Pro" (`G03_4K_GB` / BeyondTV4), Google TV, Android 11 (API 30)
- Panel renders 1920x1080 @ 320dpi → design canvas is **960x540 dp**
- CPU is **armeabi-v7a only** → any sideloaded APK must ship a 32-bit ARM build
- Google account `ourhome933@gmail.com` is held by Play services (`com.google.android.gms`),
  never by a launcher — swapping launchers cannot sign you out

## Hard-won warnings

1. **Never disable `com.google.android.apps.tv.launcherx`.** It triggers
   `com.google.android.tungsten.setupwraith/.RecoveryActivity` (setup-recovery watchdog),
   which is one step away from a factory reset. Recovering immediately:
   `adb shell pm enable com.google.android.apps.tv.launcherx` then press HOME.
2. `cmd package set-home-activity` alone does nothing here. What works is
   `pm set-home-activity --user 0 com.tvhome.launcher/.HomeActivity`, mirrored by the
   *Default home app* screen (`android.settings.HOME_SETTINGS`).
3. `adb connect` replies "already connected" — a readiness check must test `get-state`,
   not the connect output (this caused a 3-minute silent hang earlier).
4. Packaging needs `QUERY_ALL_PACKAGES` + a `<queries>` block, otherwise on API 30 the
   launcher cannot see other apps (all icons fall back to the Android placeholder).
5. `resources.arsc` must stay uncompressed for API 30 — the merge step preserves entry
   compress types and `zipalign -p 4` fixes alignment before signing.
6. Don't launch the internal activities with `am start` while testing; they are
   `exported="false"`, so shell cannot start them. Navigate with key events instead.

## Current UI (as built)

- Top-left: large clock + date; beside it a weather chip (Open-Meteo, city configurable,
  shows temp, condition, high/low, humidity)
- Favourites row: Netflix, YouTube, ARY Plus, Cricfy TV, PlayTorrio, Tamasha TV, Kodi,
  Spotify, Downloader — then a **`+ Add apps`** tile at the *end of the row*, so it is
  hidden until you scroll right (as requested)
- `+ Add apps` opens a picker: OK toggles any app in/out of favourites, `✓` marks members
- Dock: wallpaper strip (6 built-ins) + Settings + All Apps
- Green focus ring on the highlighted tile, no animation anywhere
- Wallpapers: bundled 6; drop extra JPG/PNG into `/sdcard/Pictures/TVHome` and they appear
  in the picker automatically

## Still unverified visually

- **All Apps** screen (`AllAppsActivity`) — reachable from the dock
- **Wallpaper picker** (`WallpaperActivity`)
- **Settings** (`SettingsActivity`) — the "refreshed every 20 minutes" line was removed
- Whether the physical **Home button** lands on TV Home (ADB-injected HOME keys kept
  opening Google TV even though `dumpsys role` reports the role holder as ours)

## Files

```
tv-launcher/
  app/                 source: 10 java classes, manifests, layouts, assets
  build.py             aapt2 -> javac -> d8 -> zipalign -> apksigner
  resume.py            reconnect + install + set home + tweaks + screenshot
  deploy.py            earlier deploy helper (kept)
  make_assets.py       regenerates wallpapers, weather icons, app icon, banner
  tvhome.keystore      signing key (KEEP)
  build/tvhome.apk     signed output, ~1.6 MB
```

---

# SOLVED (27 Oct, 11:05) — TV Home IS the default home screen

Confirmed by `dumpsys window | grep mCurrentFocus` → `com.tvhome.launcher/.HomeActivity`,
both after pressing HOME on an idle home screen and after launching YouTube and pressing
HOME from inside it.

## Why the earlier attempts failed

`android:priority` on an activity `<intent-filter>` is **ignored by this firmware**. The
installed APK demonstrably contains `android:priority` (verified by pulling the APK off
the device and dumping its manifest), yet `cmd package query-activities` kept reporting
ours as `priority=0` — even after raising it to `1000`. So the ranking was fixed at:

| candidate | priority |
|---|---|
| `com.google.android.apps.tv.launcherx` | 2 |
| `com.google.android.tungsten.setupwraith` | 1 |
| `com.tvhome.launcher` | 0 |
| `com.android.tv.settings` FallbackHome | -1000 |

The role (`android.app.role.HOME` → ours) and the *Default home app* picker were both
correct, but resolution still followed priority. Disabling only Google's launcher dropped
HOME onto `setupwraith`, which is what produced the black recovery screen — not the
launcher removal itself.

## What actually worked

Disable **both** higher-ranked candidates, watchdog first:

```powershell
& $adb -s $d shell "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
& $adb -s $d shell "pm disable-user --user 0 com.google.android.apps.tv.launcherx"
& $adb -s $d shell "input keyevent 3"
```

Candidate list then contains only TV Home (0) and FallbackHome (-1000), so HOME resolves
to ours. FallbackHome is deliberately left enabled as a safety net.

## Rollback (restores stock behaviour, takes seconds)

```powershell
& $adb -s $d shell "pm enable com.google.android.tungsten.setupwraith"
& $adb -s $d shell "pm enable com.google.android.apps.tv.launcherx"
& $adb -s $d shell "input keyevent 3"
```

## Consequences of this setup (accepted by the user)

- **Lost:** Google TV home with *For You* rows, *Continue watching*, Google TV search page,
  profile switching. That UI lives inside the disabled launcher.
- **Unaffected:** Google account (`com.google.android.gms`), Play Store, Gboard
  (`com.google.android.inputmethod.latin`), Assistant (`katniss` + `tv.assistant` + remote
  mic service), casting (`mediashell`), Netflix/Spotify logins.
- **Gained:** ~189 MB RAM and 3 background services (launcherx's footprint) freed on a
  2 GB device that was at 75% memory use.

## Still to verify

- **Boot persistence** — reboot the TV and confirm it comes up on TV Home (not yet tested)
- All Apps screen, Wallpaper picker, Settings screen (never seen rendered — they are
  `exported="false"`, so navigate with the remote, not `am start`)
- Wallpaper strip right-key fix (cells shrunk to 96x56dp so six fit in 668dp)

---

# REMOTE BUTTONS — SOLVED (27 Oct, 11:36)

The remote's app buttons are vendor keycodes that reach whatever app currently has focus.
`HomeActivity.dispatchKeyEvent` matches them and launches the app directly:

| Button | keyCode | scanCode | Launches |
|---|---|---|---|
| Netflix | **4062** | 242 | `com.netflix.ninja` |
| YouTube | **4065** | 245 | `com.google.android.youtube.tv` |
| Prime Video | **4099** | 752 | `com.amazon.amazonvideo.livingroom` |

Match on keyCode **or** scanCode (the code originally only checked 242/245/752 and silently
missed everything, because the remote reports 4062/4065/4099).

Three things worth remembering:

1. **These work while TV Home is on screen** — that is where the user presses them. Pressing
   them inside another app does nothing: the foreground app gets the key, and the framework's
   own route is a broadcast protected by Google's signature permission
   (`com.google.android.permission.GLOBAL_BUTTON` → `com.google.global_button.ACTION_LAUNCH_APP`),
   held only by `com.google.android.apps.tv.launcherx`. A third-party app can never receive it.
2. **Guard, Media and TCL Channel** work independently of the launcher:
   `com.tcl.guard` handles its own key, media keys are framework-level, and TCL Channel's
   mapping came back when `com.tcl.partnercustomizer` was re-enabled.
3. **HDMI inputs cannot be switched by any third-party app.** Input switching is guarded by
   TCL's signature permission (`com.tcl.sourcemenu.insrc.ShortcutKeyReceiver`). The launcher
   exposes a **TV tile** (`com.tcl.tv/.TVActivity`) instead; use the remote's HDMI buttons for
   inputs, which are handled by the TV app and still work.

`KeyBridgeReceiver` in the app is a dead end kept for reference: it tried to catch the button
broadcasts (`com.tcl.ShortcutKey`, `GLOBAL_BUTTON`, `NETFLIX_KEY_START`) and never fired once,
which is how the permission gating above was proven.


