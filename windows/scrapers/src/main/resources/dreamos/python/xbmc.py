# Minimal Kodi "xbmc" module for scraper modules run by Dream OS (dreamhost). No UI, no player:
# what scrapers read (version, paths, settings conditions) answers sensibly; the rest is a no-op.
# Windows port: the platform answers say Windows (the Fire Stick's said Android).
import json as _json
import time as _time

import dreamhost as _host

LOGDEBUG = 0
LOGINFO = 1
LOGNOTICE = 1
LOGWARNING = 2
LOGERROR = 3
LOGSEVERE = 4
LOGFATAL = 4
LOGNONE = 5

ENGLISH_NAME = 2
ISO_639_1 = 0
ISO_639_2 = 1

PLAYLIST_MUSIC = 0
PLAYLIST_VIDEO = 1

DRIVE_NOT_READY = 1
TRAY_OPEN = 16
SERVER_WEBSERVER = 1

abortRequested = False

_VERSION = '21.1 (21.1.0) Git:20240804-0000000'


def log(msg, level=LOGDEBUG):
    _host.log(level, msg)


def sleep(ms):
    _time.sleep(max(0, int(ms)) / 1000.0)


def translatePath(path):
    return _host.translate(path)


def getInfoLabel(label):
    l = str(label or '')
    if l == 'System.BuildVersion':
        return _VERSION
    if l == 'System.BuildVersionShort':
        return '21.1'
    if l in ('System.ProfileName',):
        return 'Master user'
    if l == 'System.Language':
        return 'English'
    if l == 'System.OSVersionInfo':
        return 'Windows NT 10.0'
    if l.startswith('System.AddonVersion('):
        aid = l[len('System.AddonVersion('):-1]
        return _host.addon_meta(aid)['version'] if _host.installed(aid) else ''
    return ''


def getInfoImage(label):
    return ''


def getCondVisibility(condition):
    c = str(condition or '').strip()
    neg = c.startswith('!')
    if neg:
        c = c[1:]
    lc = c.lower()
    r = False
    if lc.startswith('system.hasaddon(') or lc.startswith('system.addonisenabled('):
        r = _host.installed(c[c.index('(') + 1:-1].strip())
    elif lc.startswith('system.platform.windows'):
        r = True
    return (not r) if neg else r


def executebuiltin(function, wait=False):
    _host.log(0, 'executebuiltin ignored: %s' % function)


def executeJSONRPC(jsonrpccommand):
    try:
        req = _json.loads(jsonrpccommand)
        rid = req.get('id', 1)
    except Exception:
        rid = 1
    return _json.dumps({'id': rid, 'jsonrpc': '2.0', 'error': {'code': -32601, 'message': 'Method not found.'}})


def executescript(script):
    pass


def getLanguage(format=ENGLISH_NAME, region=False):
    if format == ISO_639_1:
        return 'en-us' if region else 'en'
    if format == ISO_639_2:
        return 'eng'
    return 'English'


def getRegion(id):
    return {'dateshort': '%m/%d/%Y', 'datelong': '%A, %B %d, %Y', 'time': '%I:%M:%S %p', 'meridiem': 'AM/PM',
            'tempunit': 'F', 'speedunit': 'mph'}.get(id, '')


def getSkinDir():
    return 'skin.estuary'


def getUserAgent():
    return 'Kodi/21.1 (Windows NT 10.0; Win64; x64) App_Bitness/64 Version/21.1-Git'


def getIPAddress():
    return '127.0.0.1'


def getFreeMem():
    return 256


def getGlobalIdleTime():
    return 0


def getCacheThumbName(path):
    return '00000000.tbn'


def makeLegalFilename(filename):
    return filename


def validatePath(path):
    return path


def skinHasImage(image):
    return False


def getSupportedMedia(mediaType):
    return '.mkv|.mp4|.avi|.m4v|.ts|.webm|.mov'


def audioSuspend():
    pass


def audioResume():
    pass


def enableNavSounds(yesNo):
    pass


def playSFX(filename, useCached=True):
    pass


def stopSFX():
    pass


def convertLanguage(language, format):
    return language


class Monitor(object):
    def __init__(self, *a, **k):
        pass

    def abortRequested(self):
        return _host.abort.is_set()

    def waitForAbort(self, timeout=None):
        return _host.abort.wait(None if timeout is None else max(0.0, float(timeout)))

    def onSettingsChanged(self):
        pass

    def onNotification(self, sender, method, data):
        pass


class Player(object):
    def __init__(self, *a, **k):
        pass

    def isPlaying(self):
        return False

    def isPlayingVideo(self):
        return False

    def isPlayingAudio(self):
        return False

    def getTime(self):
        return 0.0

    def getTotalTime(self):
        return 0.0

    def getPlayingFile(self):
        return ''

    def __getattr__(self, name):
        return _noop


class PlayList(object):
    def __init__(self, *a, **k):
        self._items = []

    def add(self, url, listitem=None, index=-1):
        self._items.append(url)

    def clear(self):
        self._items = []

    def size(self):
        return len(self._items)

    def __len__(self):
        return len(self._items)

    def getposition(self):
        return -1

    def __getattr__(self, name):
        return _noop


class Keyboard(object):
    def __init__(self, line='', heading='', hidden=False):
        self._text = line

    def doModal(self, autoclose=0):
        pass

    def isConfirmed(self):
        return False

    def getText(self):
        return self._text

    def setHeading(self, heading):
        pass

    def setDefault(self, line=''):
        self._text = line

    def setHiddenInput(self, hidden=False):
        pass


class InfoTagVideo(object):
    def __getattr__(self, name):
        return _noop


class InfoTagMusic(InfoTagVideo):
    pass


class RenderCapture(object):
    def __getattr__(self, name):
        return _noop


def _noop(*a, **k):
    return None


def __getattr__(name):
    if name.startswith('__'):
        raise AttributeError(name)
    return _noop
