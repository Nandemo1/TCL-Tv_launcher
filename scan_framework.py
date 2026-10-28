"""Scan the pulled services.jar for the vendor key -> broadcast action strings."""
import re
import zipfile

JAR = r"C:\Users\perfe\OneDrive\Documents\deepseek-harness\default-workspace\tv-launcher\build\services.jar"

NEEDLES = [b"netflix", b"primevideo", b"prime_video", b"youtube"]
INTERESTING = re.compile(rb"[ -~]{6,}")


def strings_near(blob, needle, span=110):
    out = []
    low = blob.lower()
    start = 0
    while True:
        idx = low.find(needle, start)
        if idx < 0:
            break
        chunk = blob[max(0, idx - span): idx + span]
        for match in INTERESTING.finditer(chunk):
            text = match.group().decode("ascii", "replace")
            if len(text) >= 6:
                out.append(text)
        start = idx + len(needle)
    return out


def main():
    found = {}
    with zipfile.ZipFile(JAR) as jar:
        names = [n for n in jar.namelist() if n.endswith(".dex") or n.endswith(".jar")]
        print("entries to scan:", names[:10], "of", len(jar.namelist()))
        for name in names:
            try:
                blob = jar.read(name)
            except Exception as exc:
                print("skip", name, exc)
                continue
            for needle in NEEDLES:
                for text in strings_near(blob, needle):
                    found.setdefault(needle, set()).add(text)

    for needle, texts in found.items():
        interesting = sorted(
            t for t in texts
            if any(k in t.lower() for k in ("action", "intent", "com.", "tcl", "button", "key"))
            and len(t) < 90
        )
        print(f"\n=== {needle.decode()} ({len(texts)} strings, {len(interesting)} relevant) ===")
        for text in interesting[:40]:
            print("   ", text)


if __name__ == "__main__":
    main()
