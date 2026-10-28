# Minimal Kodi "xbmcaddon" for scraper modules run by Dream OS (dreamhost): add-on info from its
# addon.xml, settings from its userdata settings.xml (Dream OS writes it; defaults from resources/settings.xml).
import dreamhost as _host


class Settings(object):
    """Kodi 20+ Addon.getSettings() object."""

    def __init__(self, addon_id):
        self._id = addon_id

    def getBool(self, sid):
        return _host.get_setting(self._id, sid).lower() == 'true'

    def getInt(self, sid):
        try:
            return int(float(_host.get_setting(self._id, sid) or 0))
        except ValueError:
            return 0

    def getNumber(self, sid):
        try:
            return float(_host.get_setting(self._id, sid) or 0)
        except ValueError:
            return 0.0

    def getString(self, sid):
        return _host.get_setting(self._id, sid)

    def getStringList(self, sid):
        v = _host.get_setting(self._id, sid)
        return [x for x in v.split('|') if x] if v else []

    def setBool(self, sid, value):
        _host.set_setting(self._id, sid, 'true' if value else 'false')

    def setInt(self, sid, value):
        _host.set_setting(self._id, sid, str(int(value)))

    def setNumber(self, sid, value):
        _host.set_setting(self._id, sid, str(value))

    def setString(self, sid, value):
        _host.set_setting(self._id, sid, value)

    def setStringList(self, sid, values):
        _host.set_setting(self._id, sid, '|'.join(values))


class Addon(object):
    def __init__(self, id=None):
        aid = id or _host.current_addon() or 'script.module.dreamos'
        self._id = aid

    def getAddonInfo(self, key):
        k = str(key).lower()
        if k == 'id':
            return self._id
        if k == 'path':
            return _host.addon_dir(self._id)
        if k == 'profile':
            return 'special://profile/addon_data/%s/' % self._id
        m = _host.addon_meta(self._id)
        if k == 'stars':
            return '0'
        return m.get(k, '')

    def getSetting(self, id):
        return _host.get_setting(self._id, id)

    def getSettingBool(self, id):
        return self.getSettings().getBool(id)

    def getSettingInt(self, id):
        return self.getSettings().getInt(id)

    def getSettingNumber(self, id):
        return self.getSettings().getNumber(id)

    def getSettingString(self, id):
        return self.getSettings().getString(id)

    def setSetting(self, id, value):
        _host.set_setting(self._id, id, value)
        return True

    def setSettingBool(self, id, value):
        _host.set_setting(self._id, id, 'true' if value else 'false')
        return True

    def setSettingInt(self, id, value):
        _host.set_setting(self._id, id, str(int(value)))
        return True

    def setSettingNumber(self, id, value):
        _host.set_setting(self._id, id, str(value))
        return True

    def setSettingString(self, id, value):
        _host.set_setting(self._id, id, value)
        return True

    def getSettings(self):
        return Settings(self._id)

    def getLocalizedString(self, id):
        return _host.localized(self._id, id)

    def openSettings(self):
        pass
