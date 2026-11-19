"""Boundary checks against the signed release APK. Disposable emulator only."""
import argparse,json,re,time
from pathlib import Path
from emulator_ui import Emulator
parser=argparse.ArgumentParser(); parser.add_argument('--adb',required=True); parser.add_argument('--serial',required=True)
args=parser.parse_args(); e=Emulator(args.adb,args.serial)
root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([^"]+)"',(root/'app/build.gradle.kts').read_text())[1]
out=root/'validation'/version/'security'; out.mkdir(parents=True,exist_ok=True)
results=[]
def record(name):
    results.append({'check':name,'result':'PASS'})
    (out/'results.json').write_text(json.dumps(results,indent=2)+'\n'); print('PASS',name,flush=True)
e.run('install','-r',str(root/f'dist/mz-music-{version}-release.apk'))
e.run('shell','input','keyevent','KEYCODE_WAKEUP'); e.run('shell','wm','dismiss-keyguard')
e.run('shell','am','start','-n','local.music/.MainActivity')
session=e.session({0,2}); (out/'upgrade-session.txt').write_text(session)
assert 'size=4' in session
record('Release installs over the prior same-certificate build and restores the saved queue paused')
access=e.run('shell','run-as','local.music','id',check=False)
# run-as prints rejection on stderr, so also capture the explicit command result below.
import subprocess
probe=subprocess.run([args.adb,'-s',args.serial,'shell','run-as','local.music','id'],capture_output=True,text=True)
(out/'run-as.txt').write_text(probe.stdout+probe.stderr)
assert probe.returncode!=0 and 'not debuggable' in (probe.stdout+probe.stderr)
record('run-as rejects private-data access to the release app')
e.run('install','-r',str(root/'.tooling/security-probe/app/build/outputs/apk/debug/app-debug.apk'))
e.run('shell','pm','clear','local.securityprobe')
e.run('shell','cmd','notification','disallow_listener','local.securityprobe/.ProbeListener')
e.run('shell','am','start','-n','local.securityprobe/.ProbeActivity','--es','mode','untrusted'); time.sleep(2)
log=e.run('shell','run-as','local.securityprobe','cat','files/results.txt')
assert 'Session rejected' in log
record('Untrusted separate-UID controller is rejected')
e.run('shell','am','force-stop','local.securityprobe')
e.run('shell','cmd','notification','allow_listener','local.securityprobe/.ProbeListener')
time.sleep(2)
for attempt in range(5):
    e.run('shell','am','start','-n','local.securityprobe/.ProbeActivity','--es','mode','trusted','--es','uri','file:///sdcard/no.mp3'); time.sleep(3)
    probe_log=e.run('shell','run-as','local.securityprobe','cat','files/results.txt')
    if 'CONNECTED queue=' in probe_log: break
    e.run('shell','am','force-stop','local.securityprobe'); time.sleep(2)
log=e.run('shell','run-as','local.securityprobe','cat','files/results.txt'); (out/'controller-probe.txt').write_text(log)
for name in ['COMMAND_CHANGE_MEDIA_ITEMS','COMMAND_SET_MEDIA_ITEM','COMMAND_SET_SPEED_AND_PITCH','COMMAND_SET_AUDIO_ATTRIBUTES','COMMAND_SET_VOLUME','COMMAND_GET_TIMELINE','COMMAND_SET_SHUFFLE_MODE','COMMAND_SET_REPEAT_MODE']:
    assert name+'=false' in log,name
assert 'COMMAND_PLAY_PAUSE=true' in log and 'Speed observed=1.0' in log
record('Trusted external controller retains playback but cannot edit queue, speed, volume or audio attributes')
assert 'soundExtras=false' in log and 'SOUND advertised=false' in log and 'SOUND result=-4' in log
record('Sound settings and private session extras remain app-only')
assert 'QUEUE advertised=false' in log and 'QUEUE result=-4' in log
record('App-only queue pages are denied to trusted external controllers')
assert 'CONNECTED queue=1' in log
legacy=[line.split('=',1)[1] for line in log.splitlines() if line.startswith('LEGACY queue title=')]
assert legacy and len([name for name in legacy if name!='Queued track'])<=1,legacy
record('Modern controllers cannot read the full timeline and legacy queues hide unplayed titles')
e.run('shell','cmd','notification','disallow_listener','local.securityprobe/.ProbeListener')
e.run('shell','am','force-stop','local.securityprobe')
before=e.run('shell','pidof','local.music').strip(); e.run('logcat','-c')
e.run('shell','am','start','-n','local.securityprobe/.ProbeActivity','--es','mode','preview'); time.sleep(2)
log=e.run('shell','run-as','local.securityprobe','cat','files/results.txt')
(out/'preview-probe.txt').write_text(log)
assert 'Preview rejected:' in log
assert e.run('shell','pidof','local.music').strip()==before
crashes=e.run('logcat','-d','-s','AndroidRuntime:E'); (out/'runtime-crashes.txt').write_text(crashes)
assert 'Process: local.music' not in crashes
record('The previous unprivileged preview crash is blocked and the player process stays alive')
e.run('shell','am','force-stop','local.securityprobe')
e.run('shell','am','start','-n','local.music/.MainActivity')
print('All release security boundary checks passed.',flush=True)
