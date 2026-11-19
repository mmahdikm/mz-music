"""Create quiet synthetic audio; requires ffmpeg or imageio-ffmpeg. No music downloads."""
from pathlib import Path
import shutil
import subprocess

ffmpeg = shutil.which("ffmpeg")
if not ffmpeg:
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()

out = Path(__file__).resolve().parents[1] / ".tooling" / "fixtures"
out.mkdir(parents=True, exist_ok=True)
cover = out / "cover.jpg"
subprocess.run([ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i",
                "color=c=0x315b47:s=600x600", "-frames:v", "1", str(cover)], check=True)

for index, (ext, codec) in enumerate([("wav", "pcm_s16le"), ("mp3", "libmp3lame"),
                                       ("m4a", "aac"), ("flac", "flac")], 1):
    title = f"0{index} - {ext.upper()} test"
    cmd = [ffmpeg, "-y", "-v", "error", "-f", "lavfi", "-i",
           f"sine=frequency={220 + index * 110}:sample_rate=44100:duration=80"]
    if ext == "mp3":
        cmd += ["-i", str(cover), "-map", "0:a", "-map", "1:v", "-c:v", "copy", "-disposition:v", "attached_pic"]
    cmd += ["-af", "volume=0.1", "-c:a", codec,
            "-metadata", f"title={title}", "-metadata", "artist=Local Music QA",
            "-metadata", "album=Synthetic test tones", str(out / f"{title}.{ext}")]
    subprocess.run(cmd, check=True)
print(out)
