# Minimal Kodi "xbmcplugin" for scraper modules run by Dream OS (dreamhost): directory calls are no-ops.

SORT_METHOD_NONE = 0
SORT_METHOD_LABEL = 1
SORT_METHOD_LABEL_IGNORE_THE = 2
SORT_METHOD_DATE = 3
SORT_METHOD_SIZE = 4
SORT_METHOD_FILE = 5
SORT_METHOD_TITLE = 10
SORT_METHOD_VIDEO_YEAR = 18
SORT_METHOD_EPISODE = 24


def addDirectoryItem(handle, url, listitem, isFolder=False, totalItems=0):
    return True


def addDirectoryItems(handle, items, totalItems=0):
    return True


def endOfDirectory(handle, succeeded=True, updateListing=False, cacheToDisc=True):
    pass


def setResolvedUrl(handle, succeeded, listitem):
    pass


def setContent(handle, content):
    pass


def setPluginCategory(handle, category):
    pass


def addSortMethod(handle, sortMethod, labelMask='', label2Mask=''):
    pass


def setProperty(handle, key, value):
    pass


def getSetting(handle, id):
    return ''


def setSetting(handle, id, value):
    pass


def _noop(*a, **k):
    return None


def __getattr__(name):
    if name.startswith('__'):
        raise AttributeError(name)
    return _noop
