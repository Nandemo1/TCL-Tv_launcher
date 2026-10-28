"""Build, sign and install the TV Home launcher APK.

Usage:  python build.py [--install] [--name <package>]
"""
import json
import os
import shutil
import subprocess
import sys
import zipfile

BASE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.join(BASE, "app")
TOOLS = os.path.join(os.path.dirname(BASE), "tv-tools")
OUT = os.path.join(BASE, "build")
WORK = os.path.join(OUT, "work")
LOG = os.path.join(WORK, "build.log")

PKG = "com.tvhome.launcher"
DEVICE = "192.168.100.55:5555"
KS = os.path.join(BASE, "tvhome.keystore")
KS_PASS = "tvhome123"
KS_ALIAS = "tvhome"

with open(os.path.join(TOOLS, "toolchain.json"), encoding="utf-8") as fh:
    TC = json.load(fh)

JAVA_HOME = TC["javaHome"]
ANDROID_JAR = TC["androidJar"]
BUILD_TOOLS = TC["buildTools"]

JAVAC = os.path.join(JAVA_HOME, "bin", "javac.exe")
JAR = os.path.join(JAVA_HOME, "bin", "jar.exe")
KEYTOOL = os.path.join(JAVA_HOME, "bin", "keytool.exe")
AAPT2 = os.path.join(BUILD_TOOLS, "aapt2.exe")
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
ADB = os.path.join(TOOLS, "adb.exe")


def env():
    e = dict(os.environ)
    e["JAVA_HOME"] = JAVA_HOME
    e["PATH"] = os.path.join(JAVA_HOME, "bin") + os.pathsep + e.get("PATH", "")
    e["USERPROFILE"] = os.path.join(TOOLS, "home")
    e.setdefault("HOME", os.path.join(TOOLS, "home"))
    return e


def run(cmd, step):
    if cmd[0].lower().endswith(".bat"):
        cmd = ["cmd.exe", "/c"] + cmd
    with open(LOG, "a", encoding="utf-8") as log:
        log.write("\n\n### " + step + "\n" + " ".join(cmd) + "\n")
        log.flush()
        proc = subprocess.run(cmd, cwd=BASE, stdout=log, stderr=subprocess.STDOUT, env=env())
    if proc.returncode != 0:
        print(f"FAILED at: {step}")
        with open(LOG, encoding="utf-8", errors="replace") as log:
            print(log.read()[-6000:])
        sys.exit(1)
    print(f"ok  {step}")


def sources():
    files = []
    for root, _dirs, names in os.walk(os.path.join(APP, "java")):
        files += [os.path.join(root, n) for n in names if n.endswith(".java")]
    gen = os.path.join(WORK, "gen")
    for root, _dirs, names in os.walk(gen):
        files += [os.path.join(root, n) for n in names if n.endswith(".java")]
    return files


def merge_dex(base_apk, dex_file, dest):
    """Copy aapt2's APK and add classes.dex without disturbing entry order."""
    with zipfile.ZipFile(base_apk) as zin, zipfile.ZipFile(dest, "w") as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            item = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            item.compress_type = info.compress_type
            item.external_attr = info.external_attr
            zout.writestr(item, data)
        zout.write(dex_file, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)
    print(f"ok  merge classes.dex -> {os.path.basename(dest)}")


def ensure_keystore():
    if os.path.exists(KS):
        return
    run([KEYTOOL, "-genkeypair", "-keystore", KS, "-alias", KS_ALIAS,
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
         "-storepass", KS_PASS, "-keypass", KS_PASS,
         "-dname", "CN=TV Home, OU=Personal, O=Home, C=PK"], "create keystore")


def main():
    install = "--install" in sys.argv
    os.makedirs(OUT, exist_ok=True)
    # Intermediates live in their own folder: a stale locked file from an older run
    # then can never block the build again.
    if os.path.isdir(WORK):
        shutil.rmtree(WORK, ignore_errors=True)
    os.makedirs(os.path.join(WORK, "gen"), exist_ok=True)
    os.makedirs(os.path.join(WORK, "classes"), exist_ok=True)
    os.makedirs(os.path.join(WORK, "dex"), exist_ok=True)
    open(LOG, "w", encoding="utf-8").close()

    res_zip = os.path.join(WORK, "res.zip")
    base_apk = os.path.join(WORK, "base.apk")

    run([AAPT2, "compile", "--dir", os.path.join(APP, "res"), "-o", res_zip], "aapt2 compile")
    run([AAPT2, "link", "-o", base_apk,
         "-I", ANDROID_JAR,
         "--manifest", os.path.join(APP, "AndroidManifest.xml"),
         "-R", res_zip,
         "--java", os.path.join(WORK, "gen"),
         "-A", os.path.join(APP, "assets"),
         "--min-sdk-version", "23",
         "--target-sdk-version", "30",
         "--version-code", "1",
         "--version-name", "1.0",
         "--auto-add-overlay"], "aapt2 link")

    files = sources()
    print(f"    compiling {len(files)} java files")
    run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-nowarn",
         "-classpath", ANDROID_JAR, "-d", os.path.join(WORK, "classes")] + files, "javac")

    classes_jar = os.path.join(WORK, "classes.jar")
    run([JAR, "cf", classes_jar, "-C", os.path.join(WORK, "classes"), "."], "jar")
    run([D8, "--min-api", "23", "--lib", ANDROID_JAR,
         "--output", os.path.join(WORK, "dex"), classes_jar], "d8")

    merged = os.path.join(WORK, "merged.apk")
    merge_dex(base_apk, os.path.join(WORK, "dex", "classes.dex"), merged)

    aligned = os.path.join(WORK, "aligned.apk")
    run([ZIPALIGN, "-p", "-f", "4", merged, aligned], "zipalign")

    ensure_keystore()
    signed = os.path.join(OUT, "tvhome.apk")
    run([APKSIGNER, "sign", "--ks", KS, "--ks-pass", "pass:" + KS_PASS,
         "--key-pass", "pass:" + KS_PASS, "--ks-key-alias", KS_ALIAS,
         "--out", signed, aligned], "apksigner sign")
    run([APKSIGNER, "verify", "--print-certs", signed], "apksigner verify")

    size = os.path.getsize(signed) / 1048576
    print(f"\nAPK ready: {signed}  ({size:.1f} MB)")

    if install:
        run([ADB, "-s", DEVICE, "install", "-r", signed], "adb install")
        run([ADB, "-s", DEVICE, "shell", "pm", "path", PKG], "verify install")
    return 0


if __name__ == "__main__":
    sys.exit(main())
