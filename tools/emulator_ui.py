"""UI-only checks on a disposable emulator; no debug hooks or private-data reads."""
import re
import subprocess
import time
import xml.etree.ElementTree as ET

class Emulator:
    def __init__(self, adb, serial):
        if not re.fullmatch(r'emulator-\d+', serial): raise ValueError('Disposable emulator required')
        self.adb, self.serial = adb, serial
    def run(self, *args, binary=False, check=True):
        result = subprocess.run([self.adb, '-s', self.serial, *args], capture_output=True, text=not binary)
        if check and result.returncode: raise RuntimeError(str(args) + ': ' + str(result.stderr))
        return result.stdout
    def screen(self):
        self.run('shell','uiautomator','dump','/sdcard/mz-review-ui.xml')
        return ET.fromstring(self.run('shell','cat','/sdcard/mz-review-ui.xml'))
    def find(self, label, tree=None):
        items=list((tree if tree is not None else self.screen()).iter('node'))
        for attribute in ['content-desc','text']:
            for item in items:
                if item.get(attribute)==label:
                    x,y,r,b=map(int,re.findall(r'\d+',item.get('bounds')))
                    if r>x and b>y: return item
        raise AssertionError('UI element absent: '+label)
    def click(self,item,ratio=.5):
        assert item.get('enabled')=='true'
        x,y,r,b=map(int,re.findall(r'\d+',item.get('bounds')))
        self.run('shell','input','tap',str(int(x+(r-x)*ratio)),str((y+b)//2))
    def tap(self,label): self.click(self.find(label))
    def checked(self,label):
        tree=self.screen()
        item=self.find(label,tree)
        parents={child: parent for parent in tree.iter() for child in parent}
        while item.get('checkable')!='true':
            item=parents[item]
        return item.get('checked')=='true'
    def scroll(self,direction='down'):
        y1,y2=(2100,700) if direction=='down' else (700,2100)
        self.run('shell','input','swipe','950',str(y1),'950',str(y2),'250')
    def reach(self,label):
        for direction in ['down','up']:
            for _ in range(9):
                try: return self.find(label)
                except AssertionError: self.scroll(direction)
        raise AssertionError('Cannot scroll to '+label)
    def contains(self,text):
        for _ in range(6):
            if any(text in n.get('text','') for n in self.screen().iter('node')): return
            time.sleep(.2)
        raise AssertionError('Missing text: '+text)
    def media(self,command): self.run('shell','cmd','media_session','dispatch',command)
    def session(self,states=None,title=None):
        for _ in range(40):
            text=self.run('shell','dumpsys','media_session')
            if 'package=local.music' in text:
                text=text[text.index('package=local.music'):]
                match=re.search(r'state=PlaybackState \{state=(?:[A-Z_]+\()?(\d+)\)?',text)
                if match and (states is None or int(match[1]) in states) and (title is None or title in text): return text
            time.sleep(.3)
        raise AssertionError(text)
    def restart(self):
        self.run('shell','am','force-stop','local.music')
        self.run('shell','am','start','-n','local.music/.MainActivity')
        time.sleep(1)
    def name(self,name,previous=''):
        item=next(n for n in self.screen().iter('node') if n.get('class')=='android.widget.EditText')
        self.click(item)
        if previous:
            self.run('shell','input','keyevent','123')
            self.run('shell','input','keyevent',*(['67']*len(previous)))
        self.run('shell','input','text',name)
        self.run('shell','input','keyevent','KEYCODE_BACK')
        self.tap('Save')
