"""Destructive QA on a disposable emulator only; never targets a physical phone.

Usage: python3 tools/emulator_smoke.py --adb /path/to/adb --serial emulator-5556
Generate fixtures first with tools/make_fixtures.py. Resets this app's emulator data.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser()
parser.add_argument("--adb", required=True)
parser.add_argument("--serial", required=True)
args = parser.parse_args()
if not re.fullmatch(r"emulator-\d+", args.serial):
    raise SystemExit("This runner only accepts a disposable emulator serial.")
root = Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^\"]+)"', (root / "app/build.gradle.kts").read_text())[1]
out = root / "validation" / version / "playback"
out.mkdir(parents=True, exist_ok=True)
results = []


def adb(*arguments, binary=False):
    return subprocess.check_output([args.adb, "-s", args.serial, *arguments], text=not binary)


def record(name):
    results.append({"check": name, "result": "PASS"})
    (out / "emulator-results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("PASS", name, flush=True)


def screen():
    adb("shell", "uiautomator", "dump", "/sdcard/local-music-window.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/local-music-window.xml"))


def find(label, tree=None):
    for node in (tree if tree is not None else screen()).iter("node"):
        if label in (node.get("text"), node.get("content-desc"), node.get("resource-id")):
            return node
    raise AssertionError("UI element absent: " + label)


def tap(label):
    node = find(label)
    assert node.get("enabled") == "true", label + " disabled"
    x, y, right, bottom = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x + right) // 2), str((y + bottom) // 2))


def shot(name):
    (out / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))


def session():
    text = adb("shell", "dumpsys", "media_session")
    if "package=local.music" not in text:
        return -1, 0, text
    text = text[text.index("package=local.music"):]
    state, position = re.search(r"state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)\)?, position=(\d+)", text).groups()
    return int(state), int(position), text


def wait_state(expected, timeout=15, title=None):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        state, position, text = session()
        if state in expected and (title is None or title in text):
            return state, position, text
        time.sleep(0.5)
    raise AssertionError("Playback state mismatch: " + str(session()))


def media(command):
    adb("shell", "cmd", "media_session", "dispatch", command)


adb("install", "-r", str(root / f"dist/mz-music-{version}-release.apk"))
adb("shell", "pm", "clear", "local.music")
adb("shell", "content delete --uri content://media/external/audio/media --where \"_data LIKE '/storage/emulated/0/Music/LocalMusicQA/%'\"")
adb("shell", "rm", "-rf", "/sdcard/Music/LocalMusicQA")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("shell", "am", "start", "-n", "local.music/.MainActivity")
time.sleep(2)
find("Bring your music along")
tap("Allow music access")
tap("com.android.permissioncontroller:id/permission_deny_button")
find("Bring your music along")
record("Permission denial keeps a usable explanation and settings action")
shot("01-permission.png")
tap("Allow music access")
tap("com.android.permissioncontroller:id/permission_allow_button")
find("A little quiet here")
record("Granted permission with empty library shows an empty state")
shot("02-empty-library.png")

adb("shell", "mkdir", "-p", "/sdcard/Music/LocalMusicQA")
for fixture in sorted((root / ".tooling/fixtures").iterdir()):
    if fixture.suffix not in (".wav", ".mp3", ".m4a", ".flac"):
        continue
    destination = "/sdcard/Music/LocalMusicQA/" + fixture.name
    adb("push", str(fixture), destination)
    adb("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
        "-d", "'file://" + destination + "'")
time.sleep(2)
tap("Refresh music library")
find("01 - WAV test")
find("04 - FLAC test")
record("MediaStore discovers WAV, MP3, AAC/M4A and FLAC with metadata")
shot("03-library.png")
# Exercise seek, queue advance and error recovery with Tuned processing active.
tap("Sound")
tap("Tuned")
tap("Back from Sound")
tap("01 - WAV test")
wait_state({3})
find("NOW PLAYING")
record("Selecting a song starts playback and opens Now Playing")
shot("04-now-playing.png")
tap("Pause")
_, paused_position, _ = wait_state({2})
time.sleep(1)
assert abs(session()[1] - paused_position) < 500
record("Pause stops position progression")
tap("Play")
wait_state({3})

# Exercise the same media-button path used by lock-screen/headset controls.
media("pause")
wait_state({2})
media("play")
wait_state({3})
media("next")
wait_state({3})
assert "02 - MP3 test" in session()[2]
media("previous")
wait_state({3})
assert "01 - WAV test" in session()[2]
record("System media play, pause, next and previous commands control the session")

for title in ["02 - MP3 test", "03 - M4A test", "04 - FLAC test"]:
    tap("Next track")
    wait_state({3})
    find(title)
    record(title + " decodes and plays")
tap("Previous track")
wait_state({3})
tap("Previous track")
wait_state({3})
shot("05-album-art.png")

# Seek close to the end and observe the next item start automatically.
slider = find("Playback position")
x, y, right, bottom = map(int, re.findall(r"\d+", slider.get("bounds")))
adb("shell", "input", "tap", str(int(x + (right - x) * .96)), str((y + bottom) // 2))
time.sleep(7)
assert "03 - M4A test" in wait_state({3})[2]
record("Seek and automatic queue advance work")

adb("shell", "input", "keyevent", "KEYCODE_HOME")
time.sleep(3)
wait_state({3})
service = adb("shell", "dumpsys", "activity", "services", "local.music")
assert "isForeground=true" in service
notifications = adb("shell", "dumpsys", "notification", "--noredact")
assert "local.music" in notifications and "MediaStyle" in notifications
record("Playback continues outside activity with foreground media notification")
adb("shell", "input", "keyevent", "KEYCODE_SLEEP")
time.sleep(8)
wait_state({3})
record("Playback remains active with the screen off (short emulator check)")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("shell", "am", "start", "-n", "local.music/.MainActivity")
time.sleep(1)
media("pause")
_, before_restart, _ = wait_state({2})
assert before_restart > 0
time.sleep(1)
adb("shell", "am", "force-stop", "local.music")
adb("shell", "am", "start", "-n", "local.music/.MainActivity")
time.sleep(2)
restored_state, restored_position, restored_text = wait_state({0, 2}, title="03 - M4A test")
assert "03 - M4A test" in restored_text
assert abs(restored_position - before_restart) < 1000
assert "size=4" in restored_text
record("Process restart restores queue, current track and position without autoplay")
tap("Play")
wait_state({3})
record("Restored queue resumes on explicit Play")
media("pause")
wait_state({2})
adb("shell", "am", "force-stop", "local.music")
# Preserve the MediaStore entry but corrupt its backing file.
adb("shell", "printf 'not an audio file' > '/sdcard/Music/LocalMusicQA/03 - M4A test.m4a'")
adb("shell", "am", "start", "-n", "local.music/.MainActivity")
time.sleep(2)
tap("Play")
wait_state({7})
tree = screen()
assert any("unsupported" in n.get("text", "") or "could not be played" in n.get("text", "") for n in tree.iter("node"))
record("Corrupt file produces a visible actionable error")
shot("06-file-error.png")
tap("Next track")
wait_state({3})
assert "04 - FLAC test" in session()[2]
record("Next skips a failed file and starts a valid track")
media("previous")
wait_state({7})
media("next")
wait_state({3})
assert "04 - FLAC test" in session()[2]
record("System Next also recovers from a failed file")
media("pause")
wait_state({2})
adb("shell", "am", "force-stop", "local.music")
# Quoting is needed because adb shell joins arguments into a remote shell command.
adb("shell", "rm '/sdcard/Music/LocalMusicQA/04 - FLAC test.flac'")
adb("shell", "am", "start", "-n", "local.music/.MainActivity")
time.sleep(2)
tap("Play")
wait_state({7})
assert any("missing" in n.get("text", "") or "could not be played" in n.get("text", "")
           for n in screen().iter("node"))
record("Deleted file in a restored queue reports an error without a crash")
media("previous")
wait_state({7})
media("previous")
wait_state({3})
assert "02 - MP3 test" in session()[2]
record("System Previous recovers across corrupt and deleted files")
media("pause")
wait_state({2})
crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E")
(out / "runtime-crashes.txt").write_text(crashes)
assert "Process: local.music" not in crashes
record("No app runtime crashes in emulator log")
print("All emulator smoke checks passed.", flush=True)
