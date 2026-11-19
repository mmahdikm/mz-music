"""Final-candidate pagination/repeat/upgrade checks after library_release_smoke.py."""
import argparse,json,re,time
from pathlib import Path
from emulator_ui import Emulator
p=argparse.ArgumentParser();p.add_argument('--adb',required=True);p.add_argument('--serial',required=True)
a=p.parse_args();e=Emulator(a.adb,a.serial)
root=Path(__file__).resolve().parents[1];version=re.search(r'versionName = "([^"]+)"',(root/'app/build.gradle.kts').read_text())[1]
out=root/'validation'/version/'library';results=[]
def record(name):
 results.append({'check':name,'result':'PASS'});(out/'edge-results.json').write_text(json.dumps(results,indent=2)+'\n');print('PASS',name,flush=True)
def dismiss():
 try:e.tap('Dismiss')
 except AssertionError:pass
def library():
 for _ in range(5):
  try:e.find('Your library');return
  except AssertionError:e.run('shell','input','keyevent','KEYCODE_BACK')
 raise AssertionError('Library absent')
e.run('install','-r',str(root/f'dist/mz-music-{version}-release.apk'))
e.run('shell','am','start','-n','local.music/.MainActivity');e.tap('Playlists');e.tap('Morning Mix')
e.find('02 - MP3 test');e.find('04 - FLAC test');e.tap('Favorites');e.find('02 - MP3 test')
record('Final APK update preserves existing Room playlists and favorites')
e.tap('Options for 02 - MP3 test');e.tap('Add to queue');e.contains('Added to queue.');dismiss()
e.session({0,2});e.tap('Queue');e.contains('1 songs')
record('Adding to an empty queue does not autoplay')
e.tap('Back from queue');e.tap('Favorites');e.tap('02 - MP3 test');e.session({3},'02 - MP3 test');e.media('pause')
for _ in range(3):
 try:e.find('Repeat one');break
 except AssertionError:e.click(e.reach('Change repeat mode'))
e.find('Repeat one');e.click(e.reach('Playback position'),.98);e.media('play');time.sleep(5)
session=e.session({3},'02 - MP3 test');position=int(re.search(r'position=(\d+)',session)[1])
assert position<10000,position
e.media('pause');e.session({2});(out/'repeat-one-session.txt').write_text(session)
record('Repeat one loops at the actual end of the audio without advancing')
library();e.tap('Songs');e.tap('02 - MP3 test');e.session({3},'02 - MP3 test');e.media('pause');e.session({2})
e.tap('Queue');e.contains('200 songs');e.tap('Save as playlist');e.name('Large');e.contains('Playlist saved.');dismiss()
(out/'04-large-queue.png').write_bytes(e.run('exec-out','screencap','-p',binary=True))
record('Eight bounded private queue pages load all 200 entries and save a large playlist')
e.restart();e.tap('Queue');e.contains('200 songs');e.session({0,2},'02 - MP3 test');e.tap('Back from queue')
e.tap('Playlists');e.tap('Large');e.contains('200 songs')
record('Large Room playlist and full queue survive process recreation')
e.tap('Playlist options');e.tap('Delete playlist');e.tap('Delete');dismiss();e.tap('Morning Mix')
e.run('shell',"rm '/sdcard/Music/LocalMusicQA/04 - FLAC test.flac'")
e.tap('04 - FLAC test');e.session({7});e.contains('missing');e.media('previous');e.session({3},'02 - MP3 test')
e.media('pause');e.session({2})
record('A deleted playlist file reports an error and system Previous recovers to a valid song')
crashes=e.run('logcat','-d','-s','AndroidRuntime:E');(out/'edge-runtime-crashes.txt').write_text(crashes)
assert 'Process: local.music' not in crashes
record('No app crashes in final-candidate upgrade, pagination and repeat checks')
print('All edge checks passed.',flush=True)
