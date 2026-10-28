# Minimal Kodi "xbmcgui" for scraper modules run by Dream OS (dreamhost). Nothing is ever shown:
# dialogs answer "cancelled", windows keep their properties (the home window's are shared, as in Kodi).
import dreamhost as _host

NOTIFICATION_INFO = 'info'
NOTIFICATION_WARNING = 'warning'
NOTIFICATION_ERROR = 'error'

INPUT_ALPHANUM = 0
INPUT_NUMERIC = 1
INPUT_DATE = 2
INPUT_TIME = 3
INPUT_IPADDRESS = 4
INPUT_PASSWORD = 5
INPUT_TYPE_TEXT = 0
INPUT_TYPE_NUMBER = 1
INPUT_TYPE_SECONDS = 2
INPUT_TYPE_TIME = 3
INPUT_TYPE_DATE = 4
INPUT_TYPE_IPADDRESS = 5
INPUT_TYPE_PASSWORD = 6
INPUT_TYPE_PASSWORD_MD5 = 7
PASSWORD_VERIFY = 1
ALPHANUM_HIDE_INPUT = 2

ACTION_PREVIOUS_MENU = 10
ACTION_NAV_BACK = 92
ACTION_SELECT_ITEM = 7
ACTION_MOVE_LEFT = 1
ACTION_MOVE_RIGHT = 2
ACTION_MOVE_UP = 3
ACTION_MOVE_DOWN = 4
ACTION_CONTEXT_MENU = 117

HORIZONTAL = 0
VERTICAL = 1

ICON_OVERLAY_NONE = 0
ICON_TYPE_NONE = 0


def _noop(*a, **k):
    return None


class Window(object):
    def __init__(self, existingWindowId=-1):
        self._id = existingWindowId
        self._own = {}

    def _props(self):
        return _host.window_props if self._id == 10000 else self._own

    def getProperty(self, key):
        with _host.props_lock:
            return self._props().get(str(key).lower(), '')

    def setProperty(self, key, value):
        with _host.props_lock:
            self._props()[str(key).lower()] = '' if value is None else str(value)

    def clearProperty(self, key):
        with _host.props_lock:
            self._props().pop(str(key).lower(), None)

    def clearProperties(self):
        with _host.props_lock:
            self._props().clear()

    def getFocusId(self):
        return 0

    def getWidth(self):
        return 1920

    def getHeight(self):
        return 1080

    def show(self):
        pass

    def close(self):
        pass

    def doModal(self):
        pass

    def __getattr__(self, name):
        if name.startswith('__'):
            raise AttributeError(name)
        return _noop


class WindowDialog(Window):
    def __init__(self, *a, **k):
        Window.__init__(self, -1)


class WindowXML(Window):
    def __init__(self, *a, **k):
        Window.__init__(self, -1)


class WindowXMLDialog(WindowXML):
    pass


class Dialog(object):
    def ok(self, heading, message='', *a, **k):
        return True

    def yesno(self, heading, message='', *a, **k):
        return False

    def yesnocustom(self, *a, **k):
        return -1

    def select(self, heading, list, *a, **k):
        return -1

    def multiselect(self, heading, options, *a, **k):
        return None

    def contextmenu(self, list):
        return -1

    def input(self, heading, defaultt='', *a, **k):
        return ''

    def numeric(self, type, heading, defaultt='', *a, **k):
        return ''

    def browse(self, type, heading, shares, *a, **k):
        return ''

    def browseSingle(self, *a, **k):
        return ''

    def browseMultiple(self, *a, **k):
        return []

    def notification(self, heading, message, icon='', time=5000, sound=True):
        _host.log(0, 'notification: %s: %s' % (heading, message))

    def textviewer(self, heading, text, usemono=False):
        pass

    def info(self, item):
        return False

    def colorpicker(self, *a, **k):
        return ''


class DialogProgress(object):
    def create(self, heading, message=''):
        pass

    def update(self, percent, message=''):
        pass

    def iscanceled(self):
        return False

    def close(self):
        pass


class DialogProgressBG(object):
    def create(self, heading, message=''):
        pass

    def update(self, percent=0, heading='', message=''):
        pass

    def isFinished(self):
        return True

    def close(self):
        pass


class ListItem(object):
    def __init__(self, label='', label2='', path='', offscreen=False):
        self._label = label
        self._label2 = label2
        self._path = path
        self._props = {}

    def getLabel(self):
        return self._label

    def getLabel2(self):
        return self._label2

    def setLabel(self, label):
        self._label = label

    def setLabel2(self, label):
        self._label2 = label

    def getPath(self):
        return self._path

    def setPath(self, path):
        self._path = path

    def setProperty(self, key, value):
        self._props[str(key).lower()] = str(value)

    def getProperty(self, key):
        return self._props.get(str(key).lower(), '')

    def getVideoInfoTag(self):
        import xbmc
        return xbmc.InfoTagVideo()

    def __getattr__(self, name):
        if name.startswith('__'):
            raise AttributeError(name)
        return _noop


class Control(object):
    def __init__(self, *a, **k):
        pass

    def getId(self):
        return 0

    def __getattr__(self, name):
        if name.startswith('__'):
            raise AttributeError(name)
        return _noop


class ControlLabel(Control):
    pass


class ControlFadeLabel(Control):
    pass


class ControlTextBox(Control):
    pass


class ControlButton(Control):
    pass


class ControlImage(Control):
    pass


class ControlProgress(Control):
    pass


class ControlList(Control):
    pass


class ControlGroup(Control):
    pass


class ControlRadioButton(Control):
    pass


class ControlSlider(Control):
    pass


class ControlEdit(Control):
    pass


class ControlSpin(Control):
    pass


class Action(object):
    def getId(self):
        return 0

    def getButtonCode(self):
        return 0


def getCurrentWindowId():
    return 10000


def getCurrentWindowDialogId():
    return 9999


def getScreenHeight():
    return 1080


def getScreenWidth():
    return 1920


def __getattr__(name):
    if name.startswith('__'):
        raise AttributeError(name)
    return Control
