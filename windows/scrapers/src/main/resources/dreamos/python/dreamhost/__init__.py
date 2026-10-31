# Dream OS host for Kodi-style scraper modules (see Scrapers.kt / ScraperEngine.kt).
#
# Holds what the Kodi API shims (xbmc, xbmcaddon, xbmcgui, xbmcvfs, xbmcplugin) need: where
# special:// paths live in the app's private storage, add-on settings (written by Dream OS as each
# add-on's userdata settings.xml, defaults from the add-on's own resources/settings.xml), the shared
# home-window properties, logging into the app log, and the guard that keeps scraper code away from
# the JVM bridge and the shell. Scraper code is user-supplied: nothing secret is ever handed to it.

import os
import re
import threading
import xml.etree.ElementTree as ET

HOME = ''        # special://home      -> <files>/scrapers/home/
USERDATA = ''    # special://userdata  -> <home>/userdata/
TEMP = ''        # special://temp      -> <cache>/scrapers/
ADDONS = ''      # <home>/addons/
VERBOSE = False

window_props = {}            # xbmcgui.Window(10000) properties (shared by all windows)
props_lock = threading.Lock()
abort = threading.Event()    # xbmc.Monitor().abortRequested()

_log_impl = None
_local = threading.local()   # current add-on id per thread (xbmcaddon.Addon() without an id)


def configure(home, temp, verbose=False):
    global HOME, USERDATA, TEMP, ADDONS, VERBOSE
    HOME = os.path.join(home, '')
    USERDATA = os.path.join(HOME, 'userdata', '')
    TEMP = os.path.join(temp, '')
    ADDONS = os.path.join(HOME, 'addons', '')
    VERBOSE = bool(verbose)
    for d in (HOME, USERDATA, TEMP, ADDONS, os.path.join(USERDATA, 'addon_data')):
        os.makedirs(d, exist_ok=True)


def set_logger(fn):
    global _log_impl
    _log_impl = fn


def log(level, msg):
    """Kodi levels: 0 debug, 1 info, 2 warning, 3 error, 4 fatal. Debug/info only when verbose."""
    try:
        if level < 2 and not VERBOSE:
            return
        if _log_impl is not None:
            _log_impl(int(level), str(msg)[:2000])
    except Exception:
        pass


# ---- special:// paths ----

_SPECIAL = {
    'home': lambda: HOME,
    'xbmc': lambda: HOME,
    'xbmcbin': lambda: HOME,
    'userdata': lambda: USERDATA,
    'profile': lambda: USERDATA,
    'masterprofile': lambda: USERDATA,
    'database': lambda: os.path.join(USERDATA, 'Database', ''),
    'thumbnails': lambda: os.path.join(USERDATA, 'Thumbnails', ''),
    'temp': lambda: TEMP,
    'logpath': lambda: TEMP,
    'subtitles': lambda: os.path.join(TEMP, 'subtitles', ''),
    'cdrips': lambda: os.path.join(TEMP, 'rips', ''),
    'skin': lambda: os.path.join(HOME, 'skin', ''),
}


def translate(path):
    if path is None:
        return ''
    p = str(path)
    if not p.lower().startswith('special://'):
        return p
    rest = p[len('special://'):]
    head, _, tail = rest.partition('/')
    base = _SPECIAL.get(head.lower(), lambda: os.path.join(TEMP, 'other', ''))()
    out = os.path.join(base, tail) if tail else base
    return out


# ---- add-ons ----

def current_addon():
    return getattr(_local, 'addon', None)


def set_current_addon(addon_id):
    _local.addon = addon_id


def addon_dir(addon_id):
    return os.path.join(ADDONS, addon_id)


def profile_dir(addon_id):
    return os.path.join(USERDATA, 'addon_data', addon_id, '')


_meta_cache = {}


def addon_meta(addon_id):
    """name / version / author etc. from the add-on's addon.xml."""
    m = _meta_cache.get(addon_id)
    if m is not None:
        return m
    m = {'id': addon_id, 'name': addon_id, 'version': '0.0.0', 'author': '', 'summary': '', 'description': '',
         'disclaimer': '', 'changelog': '', 'type': 'xbmc.python.module', 'icon': '', 'fanart': ''}
    try:
        root = ET.parse(os.path.join(addon_dir(addon_id), 'addon.xml')).getroot()
        m['name'] = root.get('name', addon_id)
        m['version'] = root.get('version', '0.0.0')
        m['author'] = root.get('provider-name', '')
        for ext in root.findall('extension'):
            if ext.get('point') == 'xbmc.addon.metadata':
                for k in ('summary', 'description', 'disclaimer'):
                    e = ext.find(k)
                    if e is not None and e.text:
                        m[k] = e.text
            elif ext.get('point', '').startswith('xbmc.python'):
                m['type'] = ext.get('point')
    except Exception:
        pass
    d = addon_dir(addon_id)
    for k, f in (('icon', 'icon.png'), ('fanart', 'fanart.jpg'), ('fanart', 'fanart.png'), ('changelog', 'changelog.txt')):
        if not m.get(k) and os.path.exists(os.path.join(d, f)):
            m[k] = os.path.join(d, f)
    _meta_cache[addon_id] = m
    return m


def installed(addon_id):
    return os.path.isfile(os.path.join(addon_dir(addon_id), 'addon.xml'))


# ---- settings ----
# Dream OS writes each add-on's userdata settings.xml (every setting, defaults included, with the
# user's choices on top) before it runs anything; reload() drops what was read so a change applies.

_settings = {}
_settings_lock = threading.Lock()


def _read_settings_file(path):
    out = {}
    try:
        root = ET.parse(path).getroot()
        for s in root.iter('setting'):
            sid = s.get('id')
            if not sid:
                continue
            if s.get('value') is not None and s.text is None:
                out[sid] = s.get('value')   # Kodi 18 style
            else:
                out[sid] = s.text or ''
    except Exception:
        pass
    return out


def _defaults(addon_id):
    out = {}
    try:
        root = ET.parse(os.path.join(addon_dir(addon_id), 'resources', 'settings.xml')).getroot()
        for s in root.iter('setting'):
            sid = s.get('id')
            if not sid:
                continue
            d = s.find('default')
            if d is not None:
                out[sid] = d.text or ''
            elif s.get('default') is not None:
                out[sid] = s.get('default')
    except Exception:
        pass
    return out


def settings(addon_id):
    with _settings_lock:
        s = _settings.get(addon_id)
        if s is None:
            s = _defaults(addon_id)
            s.update(_read_settings_file(os.path.join(profile_dir(addon_id), 'settings.xml')))
            _settings[addon_id] = s
        return s


def get_setting(addon_id, sid):
    v = settings(addon_id).get(sid, '')
    return '' if v is None else str(v)


def set_setting(addon_id, sid, value):
    """Kept for this run; written back so the add-on sees it next time (Dream OS rewrites provider.* itself)."""
    s = settings(addon_id)
    with _settings_lock:
        s[sid] = '' if value is None else str(value)
        try:
            d = profile_dir(addon_id)
            os.makedirs(d, exist_ok=True)
            root = ET.Element('settings', {'version': '2'})
            for k in sorted(s):
                e = ET.SubElement(root, 'setting', {'id': k})
                e.text = s[k]
            tmp = os.path.join(d, 'settings.xml.tmp')
            ET.ElementTree(root).write(tmp, encoding='utf-8', xml_declaration=True)
            os.replace(tmp, os.path.join(d, 'settings.xml'))
        except Exception as e:
            log(2, 'Scrapers: couldn\'t save a setting for %s (%s)' % (addon_id, e))


def reload_settings():
    with _settings_lock:
        _settings.clear()
    with props_lock:
        window_props.clear()   # modules cache their settings in home-window properties
    _meta_cache.clear()


_strings = {}


def localized(addon_id, sid):
    table = _strings.get(addon_id)
    if table is None:
        table = {}
        base = os.path.join(addon_dir(addon_id), 'resources', 'language')
        for name in ('resource.language.en_gb', 'resource.language.en_us', 'English'):
            po = os.path.join(base, name, 'strings.po')
            if not os.path.isfile(po):
                continue
            try:
                with open(po, encoding='utf-8', errors='replace') as f:
                    text = f.read()
                for m in re.finditer(r'msgctxt\s+"#(\d+)"\s*\nmsgid\s+"((?:[^"\\]|\\.)*)"(?:\s*\nmsgstr\s+"((?:[^"\\]|\\.)*)")?', text):
                    table[int(m.group(1))] = (m.group(3) or m.group(2) or '').replace('\\"', '"')
            except Exception:
                pass
            break
        _strings[addon_id] = table
    try:
        return table.get(int(sid), '')
    except Exception:
        return ''
