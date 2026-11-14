#!/usr/bin/env python3
"""Map screenshots for before and after looks, on an emulator.

Loads each save from the Load window, pauses as soon as the game shows, and takes the map at the default zoom, then
pinched in to the closest and out to the widest, each pinch about the screen's centre so the view stays put. Saves must
already be in the app's files/game (see push below). Pinches go straight into the emulator's touch device, so it needs adbd as root
(`adb -s SERIAL root`).

  look_ref.py push SERIAL [SAVE_DIR...]        copy every NAME.ichg and NAME.info in the dirs (SAVE_DIRS if none) into files/game;
                                               needs a debuggable build (assembleDebug), and saves stay over a later install -r
  look_ref.py shoot SERIAL OUT LABEL [NAME...]  OUT/LABEL-NAME-{default,closest,widest}.png
  look_ref.py diff OUT LABEL_A LABEL_B          OUT/diff-NAME-ZOOM.png and side-NAME-ZOOM.png for each pair, means to stdout

Pass --gl 0 or --gl 1 to shoot to set debug.interchange.gl first, for builds that still have that switch.
"""
import os
import re
import struct
import subprocess
import sys
import time

ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
PACKAGE = "com.rm.interchange"
SAVES = ["showcase_summer", "showcase_autumn", "showcase_winter", "curves_showcase"]
# Where the saves come from; each NAME.info's money finds the save's row in the Load window.
SAVE_DIRS = ["/srv/downloads/temp/debug/interchange/showcase", "/srv/downloads/temp/debug/interchange/curves/c2"]
TOUCH = "/dev/input/event2"  # virtio_input_multi_touch_1, the main display
WAIT_LOAD = 25


def adb(serial, *args, binary=False, timeout=120):
    out = subprocess.run([ADB, "-s", serial, *args], capture_output=True, check=True, timeout=timeout).stdout
    return out if binary else out.decode()


def ui(serial):
    """(text or content-desc, centre x, centre y) for every labelled node on screen."""
    try:
        adb(serial, "shell", "uiautomator", "dump", "/data/local/tmp/ui.xml", timeout=20)
    except subprocess.TimeoutExpired:
        return []
    xml = adb(serial, "shell", "cat", "/data/local/tmp/ui.xml")
    nodes = []
    for m in re.finditer(r'(?:text|content-desc)="([^"]+)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x0, y0, x1, y1 = map(int, m.group(2, 3, 4, 5))
        nodes.append((m.group(1), (x0 + x1) // 2, (y0 + y1) // 2))
    return nodes


def tap_text(serial, pred, what):
    for _ in range(20):
        for text, x, y in ui(serial):
            if pred(text):
                adb(serial, "shell", "input", "tap", str(x), str(y))
                return
        time.sleep(1)
    sys.exit(f"no {what} on screen")


def screen_size(serial):
    w, h = re.search(r"(\d+)x(\d+)", adb(serial, "shell", "wm", "size")).groups()
    return int(w), int(h)


def pinch(serial, closer, frames=24):
    """One two-finger pinch about the screen's centre: fingers apart for closer, together for wider."""
    w, h = screen_size(serial)
    near, far = 80, min(w, h) * 0.42
    a, b = (near, far) if closer else (far, near)
    events = b""

    def ev(t, c, v):
        return struct.pack("<qqHHi", 0, 0, t, c, v)
    for i in range(frames + 1):
        d = a + (b - a) * i / frames
        for slot, sign in ((0, -1), (1, 1)):
            x = int((w / 2 + sign * d * 0.7) * 32767 / w)
            y = int((h / 2 + sign * d * 0.7) * 32767 / h)
            events += ev(3, 0x2F, slot) + ev(3, 0x39, slot + 1) + ev(3, 0x35, x) + ev(3, 0x36, y) + ev(3, 0x3A, 1000)
        events += ev(0, 0, 0)
    for slot in (0, 1):
        events += ev(3, 0x2F, slot) + ev(3, 0x3A, 0) + ev(3, 0x39, -1)
    events += ev(0, 0, 0)
    frame = 11 * 24
    local = "/tmp/look_ref_pinch.bin"
    with open(local, "wb") as f:
        f.write(events)
    adb(serial, "push", local, "/data/local/tmp/pinch.bin")
    os.remove(local)
    release = len(events) - (frames + 1) * frame
    # Each write has to be whole events, so the release goes in one piece from the end.
    script = (f"i=0; while [ $i -le {frames} ]; do "
              f"dd if=/data/local/tmp/pinch.bin of={TOUCH} bs={frame} skip=$i count=1 2>/dev/null; "
              f"sleep 0.016; i=$((i+1)); done; tail -c {release} /data/local/tmp/pinch.bin > {TOUCH}")
    adb(serial, "shell", script)


def money_of(info):
    for line in info.splitlines():
        if line.startswith("money="):
            return int(line[6:])
    return None


def shoot(serial, out, label, names, gl):
    os.makedirs(out, exist_ok=True)
    if gl is not None:
        adb(serial, "shell", "setprop", "debug.interchange.gl", gl)
    for name in names:
        info = next(open(f"{d}/{name}.info").read() for d in SAVE_DIRS if os.path.exists(f"{d}/{name}.info"))
        money = f"${money_of(info):,}"
        adb(serial, "shell", "am", "force-stop", PACKAGE)
        adb(serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(4)
        tap_text(serial, lambda t: t == "Load", "Load button")
        time.sleep(1.5)
        tap_text(serial, lambda t: t.startswith(money), f"save {name}")
        # Paused as soon as the game shows, so shots of one save in different builds are of the same day.
        tap_text(serial, lambda t: t == "Pause", "Pause button")
        time.sleep(WAIT_LOAD)
        date = next((t for t, _, _ in ui(serial) if re.match(r"\w{3} \d+ \w{3} \d{4}$", t)), "?")

        def cap(zoom):
            png = adb(serial, "exec-out", "screencap", "-p", binary=True)
            with open(os.path.join(out, f"{label}-{name}-{zoom}.png"), "wb") as f:
                f.write(png)
        cap("default")
        for _ in range(5):
            pinch(serial, closer=True)
            time.sleep(0.5)
        time.sleep(4)
        cap("closest")
        for _ in range(20):
            pinch(serial, closer=False)
            time.sleep(0.5)
        time.sleep(6)
        cap("widest")
        print(name, date, "done")
    adb(serial, "shell", "am", "force-stop", PACKAGE)


def push(serial, dirs):
    adb(serial, "shell", "run-as", PACKAGE, "mkdir", "-p", "files/game")
    for d in dirs:
        for f in sorted(os.listdir(d)):
            if f.endswith(".ichg") or f.endswith(".info"):
                adb(serial, "push", os.path.join(d, f), f"/data/local/tmp/{f}")
                adb(serial, "shell", "chmod", "644", f"/data/local/tmp/{f}")
                adb(serial, "shell", "run-as", PACKAGE, "cp", f"/data/local/tmp/{f}", f"files/game/{f}")
                adb(serial, "shell", "rm", f"/data/local/tmp/{f}")


def diff(out, a, b):
    from PIL import Image, ImageChops
    for f in sorted(os.listdir(out)):
        if not f.startswith(a + "-"):
            continue
        rest = f[len(a) + 1:]
        other = os.path.join(out, f"{b}-{rest}")
        if not os.path.exists(other):
            continue
        ia = Image.open(os.path.join(out, f)).convert("RGB")
        ib = Image.open(other).convert("RGB")
        d = ImageChops.difference(ia, ib)
        px = list(d.getdata())
        mean = sum(sum(p) for p in px) / len(px)
        over = sum(1 for p in px if sum(p) > 30) / len(px)
        d.point(lambda v: min(255, v * 4)).save(os.path.join(out, f"diff-{rest}"))
        side = Image.new("RGB", (ia.width * 2, ia.height))
        side.paste(ia, (0, 0))
        side.paste(ib, (ia.width, 0))
        side.resize((ia.width, ia.height // 2)).save(os.path.join(out, f"side-{rest}"))
        print(f"{rest}: mean {mean:.1f} of 765, {over * 100:.1f}% of pixels over 30")


def main():
    args = sys.argv[1:]
    gl = None
    if "--gl" in args:
        i = args.index("--gl")
        gl = args[i + 1]
        del args[i:i + 2]
    if not args:
        sys.exit(__doc__)
    cmd = args[0]
    if cmd == "push":
        push(args[1], args[2:] or SAVE_DIRS)
    elif cmd == "shoot":
        shoot(args[1], args[2], args[3], args[4:] or SAVES, gl)
    elif cmd == "diff":
        diff(args[1], args[2], args[3])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main()
