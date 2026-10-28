"""Resume TV Home after a power cut / TV standby, in one command.

    python resume.py              # connect, install, set home, tweaks, screenshot
    python resume.py --picker     # also open the system "Default home app" screen

Safe to re-run as many times as needed. Nothing here is destructive.
"""
import os
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(os.path.dirname(BASE), "tv-tools")
APK = os.path.join(BASE, "build", "tvhome.apk")
ADB = os.path.join(TOOLS, "adb.exe")
DEVICE = "192.168.100.55:5555"
PKG = "com.tvhome.launcher"
HOME_COMPONENT = "com.tvhome.launcher/.HomeActivity"


def adb(*args, timeout=180):
    env = dict(os.environ)
    env["USERPROFILE"] = os.path.join(TOOLS, "home")
    env.setdefault("HOME", os.path.join(TOOLS, "home"))
    proc = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                          env=env, timeout=timeout)
    return ((proc.stdout or "") + (proc.stderr or "")).replace("\r", "").strip()


def connect(timeout=90):
    print("connecting to", DEVICE, flush=True)
    adb("disconnect", DEVICE)
    deadline = time.time() + timeout
    while time.time() < deadline:
        adb("connect", DEVICE)
        time.sleep(1.5)
        state = adb("get-state")
        if state == "device":
            print("  connected", flush=True)
            return True
        if "unauthorized" in state or "unauthorized" in adb("devices"):
            print("  TV is asking for permission: tick 'Always allow from this computer'"
                  " and press Allow on the TV screen.", flush=True)
        else:
            print(f"  waiting ({state or 'no response'})", flush=True)
        time.sleep(3)
    return False


def main():
    if not os.path.exists(APK):
        print(f"APK missing at {APK}\nBuild it first:  python build.py")
        return 1
    if not connect():
        print("TV unreachable. Check it is powered on and on the same Wi-Fi.")
        return 1

    print("model:", adb("-s", DEVICE, "shell", "getprop", "ro.product.model"))

    # Wake it and stop it sleeping during the session.
    adb("-s", DEVICE, "shell", "input", "keyevent", "224")
    time.sleep(1)
    adb("-s", DEVICE, "shell", "svc", "power", "stayon", "true")

    out = adb("-s", DEVICE, "install", "-r", APK)
    if "UPDATE_INCOMPATIBLE" in out:
        print("  signature changed -> reinstalling cleanly", flush=True)
        adb("-s", DEVICE, "uninstall", PKG)
        out = adb("-s", DEVICE, "install", "-r", APK)
    print("install:", out.splitlines()[-1] if out else "no output")

    # Make TV Home the default launcher (role). The system picker mirrors this.
    print("set home:", adb("-s", DEVICE, "shell", "pm", "set-home-activity", "--user", "0",
                           HOME_COMPONENT) or "(no output)")

    # Let the wallpaper picker read Pictures/TVHome without a prompt.
    adb("-s", DEVICE, "shell", "pm", "grant", PKG,
        "android.permission.READ_EXTERNAL_STORAGE")

    # Instant UI: no easing anywhere in the system.
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("-s", DEVICE, "shell", "settings", "put", "global", key, "0.0")

    adb("-s", DEVICE, "shell", "mkdir", "-p", "/sdcard/Pictures/TVHome")

    adb("-s", DEVICE, "shell", "am", "force-stop", PKG)
    adb("-s", DEVICE, "shell", "am", "start", "-n", HOME_COMPONENT)
    time.sleep(3)
    print("focus:", adb("-s", DEVICE, "shell", "dumpsys", "window", "|",
                        "grep", "mCurrentFocus"))

    remote = "/sdcard/resume_home.png"
    adb("-s", DEVICE, "shell", "screencap", "-p", remote)
    local = os.path.join(BASE, "build", "resume_home.png")
    adb("-s", DEVICE, "pull", remote, local)
    adb("-s", DEVICE, "shell", "rm", "-f", remote)
    print("screenshot:", local)

    if "--picker" in sys.argv:
        adb("-s", DEVICE, "shell", "am", "start", "-a", "android.settings.HOME_SETTINGS")
        print("Default-home-app picker opened: choose 'TV Home' with the remote.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
