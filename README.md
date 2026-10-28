# TV Home

A home screen for Android TV that I built because I got tired of waiting for Google TV to
boot its feed, ask me what I want to watch, and then recommend things I already own.

It is one screen. Clock, weather, your apps, a dock, and whatever is currently playing. No
accounts, no sign in, no ads, no recommendations. Nothing phones home except a weather
request and a latency ping.

Written in plain Java. No AndroidX, no Gradle, no third party libraries, nothing to download
before it compiles.

## What's on the home screen

**Header.** Big clock and date on the left. A weather pill beside it with the condition,
high and low, humidity and city. A network pill on the right that says Wi-Fi or Ethernet and
shows a live TCP latency in milliseconds, with a dot that turns green under 80 ms, amber
under 200 ms, and red when it can't reach anything.

**Favourites row.** The apps you pick, with their real icons. Long press OK on a tile to
enter arrange mode, slide it left or right with the D-pad, OK to drop it. The row ends with
an Add apps tile that opens the picker.

**Now playing card.** Reads the active media session, so it follows Spotify, YouTube or
anything else that publishes one. Shows the track and artist, with previous, play/pause and
next buttons you can actually focus and press with the remote. Selecting the left half opens
Spotify.

**Dock.** One row: All Apps, Wallpapers, Play Store, Live TV, the HDMI inputs it finds on
its own, and Settings.

**Netflix, YouTube and Prime buttons.** The dedicated buttons on TCL remotes keep working,
which matters once Google's launcher is no longer the default home.

## Screens

- **All apps.** Every launchable app in a D-pad grid, long press to rearrange the order.
- **Pick apps.** Toggle which apps belong in your favourites. BACK saves and returns.
- **Wallpapers.** 12 bundled backgrounds, plus anything you drop into
  `/sdcard/Pictures/TVHome`.
- **Live TV and HDMI.** Opens the input full screen instead of dumping you into a channel
  list. BACK comes home.
- **Settings.** Listed below.

## Settings panel

Reached from the Settings tile in the dock, or by pressing MENU (keycode 82) on the remote.
Changes apply on the next resume and BACK returns to the home screen. The physical Settings
button on the remote still opens TCL's own settings, on purpose.

| Section | What you can change |
| --- | --- |
| Weather city | 8 cities with coordinates baked in, so no location permission is involved |
| Tile size | Small, Medium, Large, Extra large (0.82x up to 1.34x) |
| Corners | Square, Slight, Rounded, Pill |
| Background dimmer | 35% to 75%, with a live preview of your actual wallpaper at the top |
| Network | Current connection type and latency |
| Actions | Refresh weather now, Change wallpaper, jump to TCL system settings |
| About | Version, wallpaper folder path, where to put your own images |

Every control zooms when it takes focus, and the panel remembers which one you were last on.

## Details I care about

- Focus memory. Leave to watch something, come back, and the cursor is on the tile you left.
- Nothing runs on the main thread. Package scans, icon loads, weather and latency probes all
  go through one small shared pool.
- App discovery happens once per process and the results are cached, so opening All apps
  again is instant.
- Wallpapers are decoded with a sample size instead of at full resolution, because a 4K jpg
  on the heap will ruin an Android TV.
- IPv4 is forced at startup. The TV's resolver hands out IPv6 addresses its router cannot
  actually route, and requests die in connect timeouts without this.

## Building

You need a JDK, the Android SDK build tools and `adb`. The paths are set near the top of
`build.py`. Then:

```
python build.py
```

That runs aapt2 compile, aapt2 link, javac, jar, d8, zipalign and apksigner, and leaves
`build/tvhome.apk`. To also install and relaunch on the TV:

```
python build.py --install
```

The signing key is not in this repo. `build.py` creates one on first run if it is missing, so
a fresh clone builds without any setup. Note that a regenerated key means you have to
uninstall the old build before installing yours.

## Getting it on your TV

```
adb connect 192.168.1.50:5555
python build.py --install
python resume.py
```

`resume.py` reconnects, wakes the TV, installs the APK, sets TV Home as the default home app,
grants the permissions it needs, turns system animations off and saves a screenshot so you
can tell whether it worked.

If you would rather do it by hand: Settings, then Device Preferences, then Home app, then
pick TV Home. To go back to stock, same menu, pick Google TV. Nothing in here touches the
system partition.

## Helper scripts

| Script | What it does |
| --- | --- |
| `build.py` | Packages the APK offline, optionally installs it |
| `resume.py` | Reconnect, install, set default home, wake the TV |
| `deploy.py` | Install and relaunch, nothing else |
| `health.py` | Report device state and what is missing |
| `make_assets.py` | Generate the bundled wallpapers and icons |
| `scan_framework.py` | Print the SDK facts the build depends on |

`COMMANDS.md` is the pile of raw adb one liners I kept having to look up.

## Notes

Personal project, tested on exactly one TV, a TCL running Google TV. It should work on other
Android TV boxes but I have not tried one. Not affiliated with TCL, Google or anyone else.

No analytics and no tracking. The only outbound traffic is the Open-Meteo weather call and a
TCP connect used to measure latency.
