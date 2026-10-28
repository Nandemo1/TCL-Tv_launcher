"""Health check for the TCL Google TV: RAM, storage, memory hogs, background services, wakelocks.

    python health.py

Prints a readable report and writes build/health_report.txt plus build/health_raw.txt
(the raw dumps, kept in case something needs a closer look).
"""
import os
import re
import subprocess
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.join(os.path.dirname(BASE), "tv-tools")
ADB = os.path.join(TOOLS, "adb.exe")
DEVICE = "192.168.100.55:5555"
OUT = os.path.join(BASE, "build")


def adb(*args, timeout=120):
    env = dict(os.environ)
    env["USERPROFILE"] = os.path.join(TOOLS, "home")
    env.setdefault("HOME", os.path.join(TOOLS, "home"))
    try:
        proc = subprocess.run([ADB] + list(args), capture_output=True, text=True,
                              env=env, timeout=timeout)
        return ((proc.stdout or "") + (proc.stderr or "")).replace("\r", "")
    except subprocess.TimeoutExpired:
        return "<timeout>"


def sh(command):
    return adb("-s", DEVICE, "shell", command)


def connect(timeout=45):
    adb("disconnect", DEVICE)
    deadline = time.time() + timeout
    while time.time() < deadline:
        adb("connect", DEVICE)
        time.sleep(1.5)
        if adb("get-state").strip() == "device":
            return True
        if "unauthorized" in adb("devices"):
            print("TV needs permission: tick 'Always allow' and press Allow on screen.")
            return False
        time.sleep(2)
    return False


def parse_meminfo(text):
    wanted = ["MemTotal", "MemFree", "MemAvailable", "Cached", "SwapTotal", "SwapFree"]
    values = {}
    for line in text.splitlines():
        match = re.match(r"^(%s):\s+(\d+)\s*kB" % "|".join(wanted), line)
        if match:
            values[match.group(1)] = int(match.group(2))
    return values


def parse_pss(text):
    """'  123,456K: com.package (pid 1234)' lines from dumpsys meminfo."""
    rows = []
    for line in text.splitlines():
        match = re.match(r"^\s*([\d,]+)K:\s+(\S+)\s+\(pid", line)
        if match:
            rows.append((int(match.group(1).replace(",", "")), match.group(2)))
    rows.sort(reverse=True)
    return rows


def parse_services(text):
    counts = {}
    for match in re.finditer(r"ServiceRecord\{[^}]*?\s(u\d+)\s+([\w.]+)/([\w.$]+)", text):
        pkg = match.group(2)
        counts[pkg] = counts.get(pkg, 0) + 1
    return dict(sorted(counts.items(), key=lambda kv: kv[1], reverse=True))


def parse_wakelocks(text):
    lines = []
    capture = False
    for line in text.splitlines():
        if "Wake Locks:" in line:
            capture = True
            continue
        if capture:
            if line.strip() == "" or "mWakefulness" in line:
                if lines:
                    break
                continue
            lines.append(line.strip())
    return lines


def fmt_mb(kilobytes):
    return f"{kilobytes / 1024:.0f} MB"


def main():
    if not connect():
        print("TV unreachable (powered off or off Wi-Fi).")
        return 1

    os.makedirs(OUT, exist_ok=True)
    raw = {}
    report = []

    def section(title):
        report.append("")
        report.append("=" * 68)
        report.append(title)
        report.append("=" * 68)

    # ---------------------------------------------------------------- device
    model = sh("getprop ro.product.model").strip()
    android = sh("getprop ro.build.version.release").strip()
    build = sh("getprop ro.build.fingerprint").strip()
    section(f"DEVICE  {model}  ·  Android {android}")
    report.append(build)

    # ---------------------------------------------------------------- memory
    meminfo_raw = sh("cat /proc/meminfo")
    raw["meminfo"] = meminfo_raw
    mem = parse_meminfo(meminfo_raw)
    section("MEMORY")
    if mem:
        total = mem.get("MemTotal", 0)
        avail = mem.get("MemAvailable", mem.get("MemFree", 0))
        used = total - avail
        report.append(f"Total RAM      : {fmt_mb(total)}")
        report.append(f"Used           : {fmt_mb(used)}  ({used / total * 100:.0f}%)")
        report.append(f"Available      : {fmt_mb(avail)}  ({avail / total * 100:.0f}%)")
        if mem.get("Cached"):
            report.append(f"Cached         : {fmt_mb(mem['Cached'])}")
        if mem.get("SwapTotal"):
            report.append(f"Swap/ZRAM      : {fmt_mb(mem['SwapTotal'])} total, "
                          f"{fmt_mb(mem.get('SwapFree', 0))} free")
    else:
        report.append(meminfo_raw[:500])

    # ---------------------------------------------------------------- storage
    storage = sh("df -h /data /cache")
    raw["storage"] = storage
    section("STORAGE")
    report.append(storage.strip())

    # ---------------------------------------------------------------- RAM hogs
    meminfo_dump = adb("-s", DEVICE, "shell", "dumpsys meminfo", timeout=180)
    raw["dumpsys_meminfo"] = meminfo_dump
    pss = parse_pss(meminfo_dump)
    section("TOP MEMORY CONSUMERS  (PSS)")
    if pss:
        for size, name in pss[:12]:
            report.append(f"{size / 1024:8.1f} MB  {name}")
    else:
        report.append("(could not parse dumpsys meminfo)")

    # ---------------------------------------------------------------- services
    services_dump = adb("-s", DEVICE, "shell", "dumpsys activity services", timeout=180)
    raw["dumpsys_services"] = services_dump
    services = parse_services(services_dump)
    section("APPS HOLDING BACKGROUND SERVICES")
    if services:
        for pkg, count in services.items():
            report.append(f"{count:3d} service(s)  {pkg}")
        report.append("")
        report.append(f"total: {sum(services.values())} service records "
                      f"across {len(services)} packages")
    else:
        report.append("(none found)")

    # ---------------------------------------------------------------- wakelocks
    power_dump = adb("-s", DEVICE, "shell", "dumpsys power", timeout=120)
    raw["dumpsys_power"] = power_dump
    locks = parse_wakelocks(power_dump)
    section("WAKELOCKS (what keeps the TV awake)")
    report.extend(locks if locks else ["(none held)"])
    wakefulness = re.search(r"mWakefulness=(\w+)", power_dump)
    if wakefulness:
        report.append(f"wakefulness: {wakefulness.group(1)}")

    # ---------------------------------------------------------------- housekeeping
    section("SETTINGS & DEBLOAT STATE")
    for key in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
        report.append(f"{key:26s}= {sh(f'settings get global {key}').strip()}")
    disabled = [line.replace("package:", "").strip()
                for line in sh("pm list packages -d").splitlines() if line.strip()]
    third_party = [line.replace("package:", "").strip()
                   for line in sh("pm list packages -3").splitlines() if line.strip()]
    report.append("")
    report.append(f"disabled packages ({len(disabled)}):")
    for pkg in disabled:
        report.append("  " + pkg)
    report.append("")
    report.append(f"third-party packages ({len(third_party)}):")
    for pkg in third_party:
        report.append("  " + pkg)

    text = "\n".join(report)
    print(text)

    with open(os.path.join(OUT, "health_report.txt"), "w", encoding="utf-8") as handle:
        handle.write(text + "\n")
    with open(os.path.join(OUT, "health_raw.txt"), "w", encoding="utf-8") as handle:
        for name, blob in raw.items():
            handle.write(f"\n\n########## {name} ##########\n{blob}")

    print(f"\n\nsaved: {os.path.join(OUT, 'health_report.txt')}")
    print(f"raw  : {os.path.join(OUT, 'health_raw.txt')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
