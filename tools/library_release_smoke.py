"""0.3 collection/queue release checks. Destructive only on a disposable emulator."""
import argparse,json,re,time,wave,shlex
from pathlib import Path
from emulator_ui import Emulator
parser=argparse.ArgumentParser();parser.add_argument('--adb',required=True);parser.add_argument('--serial',required=True)
parser.add_argument('--start',type=int,default=0,help='Resume at a documented stage after a harness correction')
a=parser.parse_args();e=Emulator(a.adb,a.serial)
root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([^"]+)"',(root/'app/build.gradle.kts').read_text())[1]
out=root/'validation'/version/'library';out.mkdir(parents=True,exist_ok=True)
results=json.loads((out/'results.json').read_text()) if a.start else []
def record(name):
    if not any(item['check']==name for item in results):results.append({'check':name,'result':'PASS'})
    (out/'results.json').write_text(json.dumps(results,indent=2)+'\n');print('PASS',name,flush=True)
def shot(name): (out/name).write_bytes(e.run('exec-out','screencap','-p',binary=True))
def dismiss():
    try:e.tap('Dismiss')
    except AssertionError:pass
def library(tab='Songs'):
    for _ in range(5):
        try:e.find('Your library');break
        except AssertionError:e.run('shell','input','keyevent','KEYCODE_BACK')
    e.tap(tab);dismiss()
def open_playlist(name):
    library('Playlists')
    try:e.tap('Back to playlists')
    except AssertionError:pass
    e.tap(name)
def menu(title,action):e.tap('Options for '+title);e.tap(action)
def add(title):
    menu(title,'Add to playlist');e.tap('Morning Mix');e.contains('Added to playlist.');dismiss()
def order(titles):
    t=e.screen();nodes=list(t.iter('node'))
    menus=[n for n in nodes if n.get('content-desc','').startswith(('Queue options ', 'Options for '))]
    bottom=max(int(re.findall(r'\d+',n.get('bounds'))[-1]) for n in menus)
    items=[n.get('text') for n in nodes if n.get('text') in titles and int(re.findall(r'\d+',n.get('bounds'))[1])<bottom]
    assert items==titles,(items,titles)
def search(value):
    tree=e.screen();field=next(n for n in tree.iter('node') if n.get('class')=='android.widget.EditText')
    old=field.get('text','');e.click(field);e.run('shell','input','keyevent','123')
    if old:e.run('shell','input','keyevent',*(['67']*len(old)))
    if value:e.run('shell','input','text',shlex.quote(value.replace(' ','%s')))
    e.run('shell','input','keyevent','KEYCODE_BACK');time.sleep(.5)

def setup():
    e.run('install','-r',str(root/f'dist/mz-music-{version}-release.apk'))
    e.run('shell','pm','clear','local.music');e.run('shell','pm','grant','local.music','android.permission.READ_MEDIA_AUDIO')
    e.run('shell','logcat','-c')
    e.run('shell',"content delete --uri content://media/external/audio/media --where \"_data LIKE '/storage/emulated/0/Music/LocalMusicQA/%'\"")
    e.run('shell','rm','-rf','/sdcard/Music/LocalMusicQA')
    e.run('shell','mkdir','-p','/sdcard/Music/LocalMusicQA')
    for f in (root/'.tooling/fixtures').iterdir():
        if f.suffix not in ('.mp3','.m4a','.wav','.flac'):continue
        target='/sdcard/Music/LocalMusicQA/'+f.name;e.run('push',str(f),target)
        e.run('shell','am','broadcast','-a','android.intent.action.MEDIA_SCANNER_SCAN_FILE','-d',"'file://"+target+"'")
    e.run('shell','input','keyevent','KEYCODE_WAKEUP');e.run('shell','wm','dismiss-keyguard')
    e.run('shell','am','start','-n','local.music/.MainActivity');e.find('01 - WAV test')
    record('Release library and Room collections initialize without a crash')

def create():
    library('Playlists');e.tap('New playlist');e.name('Morning');e.contains('Playlist saved.');dismiss()
    e.find('Morning');e.find('Ready for your favorites')
    record('Create an empty named playlist')
    e.tap('Playlist options');e.tap('Rename playlist');e.name('Morning%sMix','Morning')
    e.contains('Playlist renamed.');dismiss();e.find('Morning Mix')
    record('Rename a playlist')
    e.tap('Back to playlists');e.tap('New playlist');e.name('morning%smix')
    e.contains('already exists');dismiss();e.find('Morning Mix')
    record('Case-insensitive duplicate names are rejected without replacing the playlist')

def populate():
    library();add('01 - WAV test');add('02 - MP3 test');add('04 - FLAC test')
    menu('02 - MP3 test','Add to playlist');e.tap('Morning Mix');e.contains('already in that playlist');dismiss()
    record('Add songs and reject duplicate playlist entries')
    menu('02 - MP3 test','Add to favorites');library('Favorites');e.find('02 - MP3 test')
    menu('02 - MP3 test','Remove from favorites');e.find('Keep the ones you love')
    library();menu('02 - MP3 test','Add to favorites')
    record('Favorites can be added, browsed and removed')

def edit():
    open_playlist('Morning Mix');order(['01 - WAV test','02 - MP3 test','04 - FLAC test'])
    menu('02 - MP3 test','Move up');order(['02 - MP3 test','01 - WAV test','04 - FLAC test'])
    menu('01 - WAV test','Remove from playlist');dismiss();order(['02 - MP3 test','04 - FLAC test'])
    assert 'exists' in e.run('shell',"test -f '/sdcard/Music/LocalMusicQA/01 - WAV test.wav' && echo exists")
    record('Reordering and removing playlist entries preserve music files')
    e.restart();open_playlist('Morning Mix');order(['02 - MP3 test','04 - FLAC test'])
    library('Favorites');e.find('02 - MP3 test')
    record('Playlist name, order and favorites survive process recreation')
    open_playlist('Morning Mix');e.tap('Play playlist');e.session({3},'02 - MP3 test');e.media('pause');e.session({2})
    e.tap('Queue');order(['02 - MP3 test','04 - FLAC test']);shot('01-queue.png')
    record('Playing a playlist creates its ordered playback queue')

def queue():
    # Start from the playlist queue screen, paused on MP3.
    e.tap('Toggle shuffle');e.find('Shuffle on')
    e.tap('Change repeat mode');e.find('Repeat all');e.tap('Change repeat mode');e.find('Repeat one')
    e.tap('Back from queue');library();menu('01 - WAV test','Play next');dismiss()
    e.tap('Queue');e.find('Repeat off');e.find('Shuffle on')
    e.media('next');e.session({2},'01 - WAV test')
    record('Play next honors shuffle order, exits Repeat one, and keeps paused playback paused')
    e.tap('Back from queue');library();menu('02 - MP3 test','Add to queue');dismiss()
    e.tap('Queue');assert 'size=4' in e.session({2})
    record('Add to queue appends without autoplay and allows repeated queue entries')
    e.tap('Queue options 4');e.tap('Move up')
    order(['02 - MP3 test','01 - WAV test','02 - MP3 test','04 - FLAC test'])
    e.session({2},'01 - WAV test')
    e.tap('Queue options 3');e.tap('Remove from queue');order(['02 - MP3 test','01 - WAV test','04 - FLAC test'])
    record('Queue reorder/removal preserves the current paused track')
    e.tap('Save as playlist');e.name('Road');e.contains('Playlist saved.');dismiss()
    e.tap('Change repeat mode');e.find('Repeat all')
    e.restart();e.tap('Queue');e.find('Shuffle on');e.find('Repeat all')
    assert 'size=3' in e.session({0,2},'01 - WAV test')
    order(['02 - MP3 test','01 - WAV test','04 - FLAC test'])
    record('Queue order, shuffle and repeat survive restart without autoplay')
    e.tap('Toggle shuffle');e.find('Shuffle off')
    e.tap('04 - FLAC test');e.session({3},'04 - FLAC test');e.media('pause');e.session({2})
    e.media('next');e.session({2},'02 - MP3 test')
    record('Repeat all wraps from the last track to the first through system controls')
    e.tap('Back from queue');open_playlist('Road');order(['02 - MP3 test','01 - WAV test','04 - FLAC test'])
    record('Save queue as playlist persists an independent named collection')
    shot('02-playlist.png')

def searching():
    library();search('FLAC');e.find('04 - FLAC test')
    t=e.screen();assert not any(n.get('content-desc')=='Options for 02 - MP3 test' for n in t.iter('node'))
    record('Search filters by title before queue selection')
    search('Local Music');e.find('02 - MP3 test');e.find('03 - M4A test')
    record('Search matches shared artist or album metadata')
    search('%');e.find('No matching songs')
    search("'");e.find('No matching songs')
    record('Literal wildcard and quote searches do not expose all results or break the query')
    search('')
    fixture=root/'.tooling/page-fixture.wav'
    with wave.open(str(fixture),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(8000);w.writeframes(b'\0\0'*800)
    e.run('push',str(fixture),'/sdcard/Music/LocalMusicQA/page-source.tmp')
    commands=[]
    for index in range(1,221):
        path=f'/sdcard/Music/LocalMusicQA/Page test {index:03d}.wav'
        commands.append(f"cp /sdcard/Music/LocalMusicQA/page-source.tmp '{path}'")
        commands.append(f"am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d 'file://{path}' >/dev/null")
    e.run('shell','\n'.join(commands));e.tap('Refresh music library');time.sleep(1)
    search('Page test 220');e.find('Page test 220')
    record('Search finds a device song beyond the initial 200-song page')
    search('');shot('03-library.png')

def deleting():
    open_playlist('Road');e.tap('Playlist options');e.tap('Delete playlist');e.tap('Cancel');e.find('Road')
    e.tap('Playlist options');e.tap('Delete playlist');e.tap('Delete');e.contains('Playlist deleted.');dismiss()
    e.find('Morning Mix');assert 'size=3' in e.session({0,2})
    e.restart();library('Playlists');e.find('Morning Mix')
    assert not any(n.get('text')=='Road' for n in e.screen().iter('node'))
    record('Confirmed playlist deletion persists without clearing the active queue')
    library();e.tap('Sound');e.click(e.reach('Private listening'));e.session({0,2},'Private listening')
    e.tap('Back from Sound');e.tap('Queue');e.find('02 - MP3 test')
    assert '02 - MP3 test' not in e.session({0,2},'Private listening')
    record('Private listening hides system metadata while the authorized app can edit its queue')
    e.tap('Back from queue');e.tap('Sound');e.click(e.reach('Clear listening data'));e.tap('Clear queue')
    e.reach('Queue and saved position cleared. Music files are unchanged.')
    e.tap('Back from Sound');library('Playlists');e.find('Morning Mix');library('Favorites');e.find('02 - MP3 test')
    record('Clearing listening data preserves explicitly saved playlists and favorites')
    e.restart();e.tap('Queue');assert 'size=0' in e.session({0})
    e.tap('Back from queue');e.tap('Sound');e.click(e.reach('Private listening'));e.tap('Back from Sound')
    record('Cleared queue remains empty after restart with the new collection database present')
    crash=e.run('logcat','-d','-s','AndroidRuntime:E');(out/'runtime-crashes.txt').write_text(crash)
    assert 'Process: local.music' not in crash
    record('No app runtime crashes during the library and queue checks')

stages=[setup,create,populate,edit,queue,searching,deleting]
for index,stage in enumerate(stages):
    if index>=a.start:
        print('STAGE',index,stage.__name__,flush=True);stage()
print('All collection/queue checks passed.',flush=True)
