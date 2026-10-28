# TV Home

A home screen for Android TV. I wrote it because the stock Google TV home took eight seconds to
show me a feed I never asked for, and I wanted one page with a clock, the weather, my apps, and
whatever is currently playing.

No accounts, no feed, no ads, nothing to sign into. The only thing that leaves the TV is a weather
request to Open-Meteo and a TCP ping used to measure latency.

It is plain Java. No AndroidX, no Gradle, no libraries to download. It runs on a TCL Google TV and
I have not tried it on anything else, though there is nothing TCL specific in it.

## Download

The APK is on the [releases page](https://github.com/Nandemo1/TCL-Tv_launcher/releases/latest).
Grab it, then install it over the network once you have ADB working (see the next section):

    adb install tvhome-v1.0.apk

That is the whole install. You do not need Python or an SDK to use it.

## What you get

One page, no scrolling. Clock and date top left, a weather pill next to it, and a network pill on
the right that says Wi-Fi or Ethernet and shows a live latency number, with a dot that goes green
under 80 ms, amber under 200 ms, and red when it cannot reach anything.

Below that is your favourites row, big cards with the real app icons. Long press OK on one to
rearrange it: arrows slide the tile, OK drops it. The row ends with an Add apps tile that opens a
picker, and BACK there saves your selection.

Near the bottom sits a now playing card. It reads the active media session, so it follows Spotify,
YouTube or anything else that publishes one, shows the track and artist, and has previous,
play/pause and next buttons you can actually focus and press with the remote. Selecting the left
half opens Spotify.

Along the very bottom is the dock: All Apps, Wallpapers, Play Store, Live TV, whichever HDMI inputs
the TV reports, and Settings. HDMI and Live TV open the input full screen instead of dumping you
into a channel list, and BACK comes home.

The Netflix, YouTube and Prime buttons on the remote keep working. That only happens because the
launcher catches those key events itself, which it has to do once Google's launcher is not the
default home.

Focus is remembered. Leave to watch something, come back, and the cursor is on the tile you left
instead of the first one.

### Settings

Reached from the dock tile or by pressing MENU (keycode 82). The physical Settings button on the
remote still opens TCL's own settings, on purpose, because that is where picture mode and audio
output live.

In the panel you can change the weather city, tile size (small through extra large), corner radius
(square, slight, rounded, pill), and how dark the wallpaper scrim sits, from 35% to 75%, with a live
preview of your own wallpaper at the top of the screen. There is a network readout, a button to
refresh weather, one to change the wallpaper, one that jumps to the system settings, and an about
block. BACK closes the panel and returns home.

Weather works anywhere on earth. Eight cities are preset for quick switching, and there is a search
box that queries Open-Meteo's geocoder, so you can type Springfield, Perth or Shenzhen and pick the
one you mean from a list that shows region and country. It stores the coordinates, so nothing about
your location is sent anywhere except the latitude and longitude you chose.

### Wallpapers

Twelve backgrounds are bundled. Anything you drop into `/sdcard/Pictures/TVHome` shows up in the
picker too, JPG or PNG, and the picker decodes them at screen size rather than full resolution
because a 4K photo on the heap will ruin an Android TV.

## Connecting to your TV

You need this before you can install anything. Two minutes, once.

**1. Find the TV's IP address.**
On the TV, open Settings, then Network, then your Wi-Fi or Ethernet connection, then Status. Write
down the IPv4 address. It looks like 192.168.1.50.

**2. Turn on ADB debugging.**
Settings, Device Preferences, About. Highlight Build number and press OK seven times. A small toast
tells you that you are now a developer. Go back a level, open Developer options, and turn on Android
debugging. Some TCL builds label it USB debugging, and some also have a separate Debugging over
network switch, so turn that on if you see it.

**3. Connect from your PC.**
Download [platform-tools](https://developer.android.com/tools/releases/platform-tools) from Google,
unzip it, open a terminal in that folder, then:

    adb connect 192.168.1.50:5555

Use your TV's address, not mine.

**4. Say yes on the TV.**
The TV pops up a dialog that says *Allow USB debugging from this computer?* Tick **Always allow from
this computer**, then press OK. Nothing works until you do this. If you dismissed it by accident,
`adb devices` will list the address as `offline`, and you can toggle Android debugging off and on
again in Developer options to make the prompt come back.

**5. Check it took.**

    adb devices

You want one line with your IP and port and the word `device`. Here is what the other answers mean:

- `unauthorized`, you did not accept the dialog in step 4.
- `offline`, the TV was asleep when you connected, or the dialog is still waiting for you.
- `List of devices attached` with nothing under it, wrong address, or the TV is on a different
  subnet or a guest network that blocks device to device traffic.

Two things that catch people out. A TV that has been idle overnight drops the ADB listener even
though it is still on the network, so reconnect after waking it. And if `adb` behaves strangely after
you change anything, `adb kill-server` then `adb connect` again fixes most of it.

## Making it your home screen

    adb install tvhome-v1.0.apk
    adb shell am start -n com.tvhome.launcher/.HomeActivity

Then tell the TV to use it as home. On the TV: Settings, Device Preferences, Home app, then pick
**TV Home**. It may also appear as a popup the first time you press the HOME button.

To go back to stock, same menu, pick Google TV. Nothing here deletes or modifies system apps, so
undoing it is always one menu away.

## Debloating with an AI assistant

Optional, and it is the part where you can actually hurt yourself, so read this before asking a
chatbot to remove all the bloat from your TV.

Disabling is not the same as uninstalling. `pm disable-user --user 0 <package>` hides an app and
stops it running, and `pm enable <package>` puts it back exactly as it was. Guides that claim to
uninstall system apps are usually running `pm uninstall --user 0`, which looks permanent, survives a
reboot, and can quietly break OTA updates.

The workflow that gets good results:

1. **Give the AI your real package list instead of letting it guess your model.**

       adb shell pm list packages -3 > thirdparty.txt
       adb shell pm list packages -s > system.txt

2. **Ask for reasoning before commands.** Something like:

   > This is the package list from my TCL Google TV, model and Android version included. For each
   > third party package, tell me what it does, whether it is safe to disable, and what breaks if I
   > disable it. Do not give me commands yet.

3. **Then ask for the commands and the undo.**

   > Now give me one `pm disable-user --user 0` command per package you marked safe, and a second
   > script that re-enables every one of them.

4. **Run them one at a time and press HOME after each.** If the screen goes black, the remote stops
   responding, or you lose the settings menu, you found something important. Re-enable it.

5. **Keep the restore script somewhere you can find it**, not just in a chat window you will close.

Never disable these, whatever an AI tells you: `android`, `com.android.systemui`, anything with
`inputmethod` in its name, the TV input framework (`com.android.tv.settings`, `com.android.tv.tuner`
and the HDMI input services), and the network or Wi-Fi stack. If the assistant cannot tell you what
a package does in one sentence, leave it alone.

For TV Home specifically, two packages need to be disabled so it can take over cleanly:

    adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
    adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith

The first is the Google TV home itself. The second is the setup wizard that keeps trying to drag you
back to it. Do the setup wizard last, and only after TV Home is already set as the default, because
if you disable it first you can lose your way to the settings menu with nothing to navigate with.
Re-enable either one to undo it.

An AI is genuinely useful for the boring half of this, which is reading a two hundred line package
list and spotting the telemetry. It is bad at knowing your specific firmware. Paste real output, ask
what things do before you touch them, and keep the undo script.

## Building from source

You do not need this if you just want the app. Skip to the release APK.

The build is Windows only and needs a JDK 17, an `android.jar`, and Android SDK build tools with
`aapt2`, `d8`, `zipalign` and `apksigner`. `build.py` reads their locations from a JSON file that
lives outside this repo, one folder up, in `tv-tools/toolchain.json`. That is why a fresh clone fails
at first. Copy `toolchain.example.json` to that path and point the three keys at your own installs.
It also expects `adb.exe` in the same `tv-tools` folder.

    python build.py

That runs aapt2 compile, aapt2 link, javac, jar, d8, zipalign and apksigner, and leaves
`build/tvhome.apk`. Add `--install` to push it to the TV at the address set near the top of the
file, which is mine, so change it.

The signing key is not in this repo, and `build.py` generates one on first run if it is missing. The
password is sitting in `build.py` as well, which is fine for a self-signed local key. It does mean
your builds are signed differently from my release APK, so uninstall the other one before installing
yours or the install fails with a signature mismatch.

### Scripts

| Script | What it does |
| --- | --- |
| `build.py` | Compile and package the APK, optionally install it |
| `resume.py` | Reconnect, install, set default home, wake the TV, take a screenshot |
| `deploy.py` | Install and relaunch, nothing else |
| `health.py` | Report what the device is missing |
| `make_assets.py` | Generate the bundled wallpapers and icons |
| `scan_framework.py` | Print the SDK facts the build relies on |

`COMMANDS.md` is my pile of raw adb one liners that I kept having to look up.

## Rough edges

- Tested on one TV. Other Android TV boxes should work but nobody has confirmed it.
- The now playing card is only as good as the app feeding it. Some apps publish sessions lazily and
  the card lags a few seconds behind.
- Weather refreshes every 30 minutes, so a fast moving front makes it lie briefly.
- HDMI switching goes through the TV input framework, which is slow on some firmware builds.
- Text entry on the weather search uses the TV's on screen keyboard, which is fine but not quick.

## Contact

Use it, fork it, break it. If you fix something I would genuinely like to see it.
