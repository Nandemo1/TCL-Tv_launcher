# TV Home — raw commands (copy/paste)

Everything below is already automated in `resume.py`. These are the literal commands
if you ever want to run them by hand in PowerShell.

## 0. Set up (once per session)

```powershell
$ws   = "C:\Users\perfe\OneDrive\Documents\deepseek-harness\default-workspace"
$adb  = "$ws\tv-tools\adb.exe"
$d    = "192.168.100.55:5555"
$env:USERPROFILE = "$ws\tv-tools\home"
```

## 1. Connect (TV must be on and on the same Wi-Fi)

```powershell
& $adb connect $d
& $adb devices
```

`device` = ready. If it says `unauthorized`, look at the TV: tick
"Always allow from this computer" and press **Allow**.

If it says `offline`, the TV is asleep — wake it with the remote, then reconnect:

```powershell
& $adb disconnect $d
& $adb connect $d
```

## 2. Wake the TV and stop it sleeping

```powershell
& $adb -s $d shell "input keyevent 224"
& $adb -s $d shell "svc power stayon true"
```

## 3. Install / update the launcher

```powershell
& $adb -s $d install -r "$ws\tv-launcher\build\tvhome.apk"
```

If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the signing key changed —
uninstall first, then install:

```powershell
& $adb -s $d uninstall com.tvhome.launcher
& $adb -s $d install -r "$ws\tv-launcher\build\tvhome.apk"
```

## 4. Make TV Home the default launcher

```powershell
& $adb -s $d shell "pm set-home-activity --user 0 com.tvhome.launcher/.HomeActivity"
```

Then verify:

```powershell
& $adb -s $d shell "dumpsys role" | Select-String "HOME|holders"
```

Expected: `name=android.app.role.HOME` → `holders=com.tvhome.launcher`

If the physical Home button still opens Google TV, open the system picker and choose
**TV Home** with the remote:

```powershell
& $adb -s $d shell "am start -a android.settings.HOME_SETTINGS"
```

## 5. Permissions and smoothness tweaks

```powershell
& $adb -s $d shell "pm grant com.tvhome.launcher android.permission.READ_EXTERNAL_STORAGE"
& $adb -s $d shell "settings put global window_animation_scale 0.0"
& $adb -s $d shell "settings put global transition_animation_scale 0.0"
& $adb -s $d shell "settings put global animator_duration_scale 0.0"
& $adb -s $d shell "mkdir -p /sdcard/Pictures/TVHome"
```

## 6. Launch it and grab a screenshot

```powershell
& $adb -s $d shell "am force-stop com.tvhome.launcher"
& $adb -s $d shell "am start -n com.tvhome.launcher/.HomeActivity"
Start-Sleep -Seconds 3
& $adb -s $d shell "screencap -p /sdcard/s.png"
& $adb -s $d pull /sdcard/s.png "$ws\tv-launcher\build\shot.png"
```

## 7. Rebuild the APK (no TV needed)

```powershell
$py = "C:\Users\perfe\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
& $py "$ws\tv-launcher\build.py"
```

---

# ✅ MAKING TV HOME THE DEFAULT (this is what worked)

Both commands are needed: the watchdog outranks TV Home and hijacks HOME if only the
launcher is disabled.

```powershell
& $adb -s $d shell "pm disable-user --user 0 com.google.android.tungsten.setupwraith"
& $adb -s $d shell "pm disable-user --user 0 com.google.android.apps.tv.launcherx"
& $adb -s $d shell "input keyevent 3"
& $adb -s $d shell "dumpsys window | grep mCurrentFocus"    # expect com.tvhome.launcher
```

**Rollback to stock Google TV home:**

```powershell
& $adb -s $d shell "pm enable com.google.android.tungsten.setupwraith"
& $adb -s $d shell "pm enable com.google.android.apps.tv.launcherx"
& $adb -s $d shell "input keyevent 3"
```

`com.android.tv.settings/.system.FallbackHome` is intentionally left enabled as a safety
net, so a broken home still has something to show.

# Handy diagnostics

```powershell
& $adb -s $d shell "dumpsys window | grep mCurrentFocus"                      # what's on screen
& $adb -s $d shell "pm path com.tvhome.launcher"                             # is it installed
& $adb -s $d shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
& $adb -s $d shell "logcat -d -b crash | tail -20"                           # crash log
```

# Remote app-button keycodes

Handled inside `HomeActivity.dispatchKeyEvent`, active **while TV Home is on screen**:

| Button | keyCode | scanCode |
|---|---|---|
| Netflix | 4062 | 242 |
| YouTube | 4065 | 245 |
| Prime Video | 4099 | 752 |

Confirm they are arriving:

```powershell
& $adb -s $d shell "logcat -d -v time" | Select-String "TVHOME_KEY"
```

Expected: `I/TVHOME_KEY: key=4062 scan=242` and so on.

Note: pressing these buttons while another app is in the foreground does nothing — the
foreground app owns the key, and the framework's own route to Google's launcher is blocked by
a signature permission we cannot hold.
