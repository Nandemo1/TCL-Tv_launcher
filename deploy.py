"""Deploy TV Home to the TV: connect, install, set as default home, apply tweaks, screenshot.

Usage:  python deploy.py [--screenshot]
Safe to re-run; each step is idempotent.
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
HOME = PKG + "/.HomeActivity"


def adb(*args, check=True):
    env = dict(os.environ)
    env["USERPROFILE"] = os.path.join(TOOLS, "home")
    env.setdefault("HOME", os.path.join(TOOLS, "home"))
    proc = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                          env=env, timeout=180)
    out = (proc.stdout or "") + (proc.stderr or "")
    out = out.replace("\r", "").strip()
    if check and proc.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {out}")
    return out.strip()


def wait_for_device(timeout=120):
    adb("disconnect", DEVICE, check=False)
    deadline = time.time() + timeout
    while time.time() < deadline:
        out = adb("connect", DEVICE, check=False)
        time.sleep(1.5)
        state = adb("get-state", check=False)
        if state == "device":
            print(f"connected: {out or state}", flush=True)
            return True
        print(f"  waiting… ({out or state or 'no response'})", flush=True)
        time.sleep(2)
    return False


def main():
    screenshot = "--screenshot" in sys.argv
    if not os.path.exists(APK):
        print(f"APK missing: {APK} — run build.py first")
        return 1

    if not wait_for_device():
        print("TV never came back on the network. Is network debugging still enabled?")
        return 1

    print("model:", adb("-s", DEVICE, "shell", "getprop", "ro.product.model"))

    print(adb("-s", DEVICE, "install", "-r", APK))

    # Become the default HOME so the power button lands here.
    print(adb("-s", DEVICE, "shell", "cmd", "package", "set-home-activity", HOME, check=False))

    # Let the wallpaper picker read /sdcard/Pictures/TVHome without a dialog.
    print(adb("-s", DEVICE, "shell", "pm", "grant", PKG,
              "android.permission.READ_EXTERNAL_STORAGE", check=False))

    # Zero animation scales: the TV UI reacts instantly instead of easing.
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        adb("-s", DEVICE, "shell", "settings", "put", "global", key, "0.0", check=False)
    print("animation scales set to 0")

    # Make sure the wallpaper folder exists for dropping in your own images.
    adb("-s", DEVICE, "shell", "mkdir", "-p", "/sdcard/Pictures/TVHome", check=False)

    adb("-s", DEVICE, "shell", "am", "start", "-n", HOME, check=False)
    time.sleep(2.5)
    print("home resolved to:", adb("-s", DEVICE, "shell", "cmd", "package",
                                   "resolve-activity", "--brief",
                                   "-a", "android.intent.action.MAIN",
                                   "-c", "android.intent.category.HOME", check=False))

    if screenshot:
        remote = "/sdcard/tvhome_shot.png"
        adb("-s", DEVICE, "shell", "screencap", "-p", remote)
        local = os.path.join(BASE, "build", "screen_home.png")
        print(adb("-s", DEVICE, "pull", remote, local))
        adb("-s", DEVICE, "shell", "rm", "-f", remote, check=False)
        print("screenshot:", local)

    return 0


if __name__ == "__main__":
    sys.exit(main())
