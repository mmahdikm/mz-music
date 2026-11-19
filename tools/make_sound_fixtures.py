"""Test-only: generate quiet tones then attach ReplayGain tags with mutagen 1.47.0."""
from pathlib import Path
import runpy
import shutil
from mutagen.mp3 import MP3
from mutagen.id3 import TXXX
from mutagen.flac import FLAC
from mutagen.mp4 import MP4, MP4FreeForm

root = Path(__file__).resolve().parents[1]
runpy.run_path(str(root / "tools/make_fixtures.py"), run_name="__main__")
folder = root / ".tooling/fixtures"
mp3 = MP3(folder / "02 - MP3 test.mp3")
for key, value in {"replaygain_track_gain": "-6 dB", "replaygain_album_gain": "-9 dB",
                   "replaygain_track_peak": "0.8", "replaygain_album_peak": "0.9"}.items():
    mp3.tags.add(TXXX(encoding=3, desc=key, text=[value]))
mp3.save()
m4a = MP4(folder / "03 - M4A test.m4a")
for key, value in {"replaygain_track_gain": "-3 dB", "replaygain_album_gain": "-9 dB"}.items():
    m4a.tags["----:com.apple.iTunes:" + key] = [MP4FreeForm(value.encode())]
m4a.save()
flac = FLAC(folder / "04 - FLAC test.flac")
flac["replaygain_track_gain"] = "-12 dB"
flac["replaygain_album_gain"] = "-9 dB"
flac.save()
boost_folder = root / ".tooling/security-review"
boost_folder.mkdir(parents=True, exist_ok=True)
boost_path = boost_folder / "05 - Boost tag test.flac"
shutil.copyfile(folder / "04 - FLAC test.flac", boost_path)
boost = FLAC(boost_path)
boost["title"] = "05 - Boost tag test"
boost["replaygain_track_gain"] = "+30 dB"
boost.save()
print("ReplayGain fixtures ready; WAV is intentionally untagged.")
