# Minimal Kodi "xbmcvfs" for scraper modules run by Dream OS (dreamhost): local files only, with
# special:// paths mapped into the app's private storage.
import os
import shutil

import dreamhost as _host


def translatePath(path):
    return _host.translate(path)


def _p(path):
    return _host.translate(path)


def exists(path):
    return os.path.exists(_p(path))


def mkdir(path):
    try:
        os.mkdir(_p(path))
        return True
    except OSError:
        return os.path.isdir(_p(path))


def mkdirs(path):
    try:
        os.makedirs(_p(path), exist_ok=True)
        return True
    except OSError:
        return False


def rmdir(path, force=False):
    try:
        if force:
            shutil.rmtree(_p(path))
        else:
            os.rmdir(_p(path))
        return True
    except OSError:
        return False


def delete(file):
    try:
        os.remove(_p(file))
        return True
    except OSError:
        return False


def rename(file, newFile):
    try:
        os.replace(_p(file), _p(newFile))
        return True
    except OSError:
        return False


def copy(strSource, strDestination):
    try:
        shutil.copyfile(_p(strSource), _p(strDestination))
        return True
    except OSError:
        return False


def listdir(path):
    p = _p(path)
    dirs, files = [], []
    try:
        for n in sorted(os.listdir(p)):
            (dirs if os.path.isdir(os.path.join(p, n)) else files).append(n)
    except OSError:
        pass
    return dirs, files


def makeLegalFilename(filename):
    return filename


def validatePath(path):
    return path


class Stat(object):
    def __init__(self, path):
        try:
            self._st = os.stat(_p(path))
        except OSError:
            self._st = None

    def _v(self, i):
        return self._st[i] if self._st else 0

    def st_mode(self):
        return self._v(0)

    def st_ino(self):
        return self._v(1)

    def st_dev(self):
        return self._v(2)

    def st_nlink(self):
        return self._v(3)

    def st_uid(self):
        return self._v(4)

    def st_gid(self):
        return self._v(5)

    def st_size(self):
        return self._v(6)

    def st_atime(self):
        return int(self._v(7))

    def st_mtime(self):
        return int(self._v(8))

    def st_ctime(self):
        return int(self._v(9))


class File(object):
    def __init__(self, filepath, mode=None):
        p = _p(filepath)
        m = mode or 'r'
        if m == 'w':
            d = os.path.dirname(p)
            if d:
                os.makedirs(d, exist_ok=True)
            self._f = open(p, 'wb')
        elif m == 'a':
            self._f = open(p, 'ab')
        else:
            try:
                self._f = open(p, 'rb')
            except OSError:
                self._f = None

    def read(self, numBytes=-1):
        if self._f is None:
            return ''
        return self._f.read(numBytes if numBytes and numBytes > 0 else -1).decode('utf-8', errors='replace')

    def readBytes(self, numBytes=-1):
        if self._f is None:
            return bytearray()
        return bytearray(self._f.read(numBytes if numBytes and numBytes > 0 else -1))

    def write(self, buffer):
        if self._f is None:
            return False
        if isinstance(buffer, str):
            buffer = buffer.encode('utf-8')
        self._f.write(buffer)
        return True

    def size(self):
        if self._f is None:
            return 0
        try:
            return os.fstat(self._f.fileno()).st_size
        except OSError:
            return 0

    def seek(self, seekBytes, iWhence=0):
        return self._f.seek(seekBytes, iWhence) if self._f else 0

    def tell(self):
        return self._f.tell() if self._f else 0

    def close(self):
        if self._f is not None:
            self._f.close()
            self._f = None

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()
