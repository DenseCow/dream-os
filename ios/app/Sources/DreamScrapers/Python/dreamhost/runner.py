# Runs the providers of the Kodi-style scraper modules the user installed (ScraperEngine.kt).
#
# The app hands a job (the title as scraper modules expect it, which providers, time limits) and a
# sink; every provider runs on a small shared thread pool and its sources stream back to the sink
# as soon as it returns, normalized to plain JSON (info hash, name, size in bytes, quality, seeders).
# Debrid checks are the app's: no account or token ever reaches this side.
# (iPhone port: same file as the Fire TV app; ScraperEngine.swift calls it through dreambridge.py.)

import base64
import binascii
import builtins
import importlib.util
import json
import os
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import dreamhost as host

_lock = threading.Lock()
_pools = {}            # lane -> [executor, size, busy count, oldest busy start]
_jobs = {}             # job id -> threading.Event (cancelled)
_classes = {}          # provider id -> source class
_libs = []             # module lib dirs on sys.path
_guarded = False
_HEX = re.compile(r'^[0-9a-f]{40}$')
_BTIH = re.compile(r'btih:([0-9A-Za-z]{32,40})')


# ---- start-up ----

def init(home, temp, verbose):
    """Paths, logging into the app log, the guard. Called once, off the main thread."""
    t0 = time.time()
    host.configure(home, temp, verbose)
    try:
        import _dreamsink   # iPhone: the app's built-in results/log module (CDreamPython)
        host.set_logger(_dreamsink.log)
    except Exception:
        pass
    _guard()
    import requests  # noqa: F401  (warm: the providers' HTTP stack)
    return int((time.time() - t0) * 1000)


def set_modules(libs_json):
    """The installed modules' lib dirs. Code from a module that changed is dropped so it reloads."""
    global _libs
    libs = [os.path.abspath(p) for p in json.loads(libs_json)]
    with _lock:
        for p in _libs:
            while p in sys.path:
                sys.path.remove(p)
        _libs = libs
        for p in libs:
            if p not in sys.path:
                sys.path.append(p)
        _classes.clear()
        addons = os.path.abspath(host.ADDONS)
        for name, mod in list(sys.modules.items()):
            f = getattr(mod, '__file__', None) or ''
            if f and os.path.abspath(f).startswith(addons):
                sys.modules.pop(name, None)
    host.reload_settings()
    importlib.invalidate_caches()
    return len(libs)


def reload_settings():
    host.reload_settings()


def warm(providers_json):
    """Imports the given providers ahead (compiles them once to __pycache__). Returns how many loaded."""
    n = 0
    for p in json.loads(providers_json):
        if _load(p) is not None:
            n += 1
    return n


# ---- the guard ----
# Best effort inside one process: scraper code can't import the JVM bridge, ctypes or anything that
# starts a process, and the shell entry points of os / subprocess refuse to run. (Same trust as a
# Kodi add-on; Dream OS gives it no secrets.)

_BLOCKED = ('java', 'android', 'com', 'jnius', 'chaquopy', '_chaquopy', 'ctypes', '_ctypes', 'dreamhost.runner',
            '_dreamsink', 'dreambridge')


def _guard():
    global _guarded
    if _guarded:
        return
    _guarded = True
    try:
        import java
        java.set_import_enabled(False)  # `from android.x import Y` style Java imports off; jclass stays ours
    except Exception:
        pass
    addons = os.path.abspath(host.ADDONS)
    real_import = builtins.__import__

    def guarded_import(name, globals=None, locals=None, fromlist=(), level=0):
        if level == 0 and globals is not None:
            f = globals.get('__file__') or ''
            if f and f.startswith(addons):
                root = name.split('.')[0]
                if root in _BLOCKED or name in _BLOCKED:
                    raise ImportError('%s is not available to scrapers' % name)
        return real_import(name, globals, locals, fromlist, level)

    builtins.__import__ = guarded_import

    def refuse(*a, **k):
        raise PermissionError('starting programs is not allowed')

    for n in ('system', 'popen', 'execv', 'execve', 'execl', 'execle', 'execlp', 'execlpe', 'execvp', 'execvpe',
              'spawnl', 'spawnle', 'spawnlp', 'spawnlpe', 'spawnv', 'spawnve', 'spawnvp', 'spawnvpe',
              'fork', 'forkpty', 'posix_spawn', 'posix_spawnp', 'kill', 'killpg'):
        if hasattr(os, n):
            setattr(os, n, refuse)
    try:
        import subprocess

        class _NoPopen(object):
            def __init__(self, *a, **k):
                raise PermissionError('starting programs is not allowed')
        subprocess.Popen = _NoPopen
    except Exception:
        pass


# ---- providers ----

def _load(p):
    pid = p['id']
    cls = _classes.get(pid)
    if cls is not None:
        return cls
    try:
        name = 'dreamprov_' + re.sub(r'[^0-9A-Za-z_]', '_', pid)
        spec = importlib.util.spec_from_file_location(name, p['file'])
        mod = importlib.util.module_from_spec(spec)
        host.set_current_addon(p['addon'])
        sys.modules[name] = mod
        spec.loader.exec_module(mod)
        cls = getattr(mod, 'source', None)
        if cls is None:
            return None
        with _lock:
            _classes[pid] = cls
        return cls
    except Exception as e:
        host.log(3, 'provider %s didn\'t load (%s: %s)' % (pid, type(e).__name__, e))
        return None


def _low_priority():
    """Background-lane threads (pill checks) run at Android's background priority (nice 10), so they
    never compete with drawing; a thread does not inherit this from the low-priority job that started it."""
    try:
        os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), 10)
    except Exception:
        pass


def _pool(lane, size):
    """A small shared pool per lane ("play" / "background"). A pool stuck on hung providers is replaced."""
    with _lock:
        ent = _pools.get(lane)
        now = time.time()
        if ent is not None and ent[2] >= ent[1] and ent[3] and now - ent[3] > 45:
            host.log(2, '%s pool busy for %d s; starting a fresh one' % (lane, now - ent[3]))
            try:
                ent[0].shutdown(wait=False)
            except Exception:
                pass
            ent = None
        if ent is None:
            init = _low_priority if lane == 'background' else None
            ent = [ThreadPoolExecutor(max_workers=size, thread_name_prefix='scraper-' + lane, initializer=init),
                   size, 0, 0.0]
            _pools[lane] = ent
        return ent


def _hash(item):
    h = str(item.get('hash') or '').strip()
    if not h:
        m = _BTIH.search(str(item.get('url') or ''))
        h = m.group(1) if m else ''
    if len(h) == 32:
        try:
            h = binascii.hexlify(base64.b32decode(h.upper())).decode('ascii')
        except Exception:
            return ''
    h = h.lower()
    return h if _HEX.match(h) else ''


def _bytes(size):
    try:
        gb = float(size or 0)
    except (TypeError, ValueError):
        return 0
    return int(gb * 1024 * 1024 * 1024) if gb > 0 else 0


def _norm(items, provider, kind, pack):
    out = []
    for it in items or []:
        try:
            if not isinstance(it, dict):
                continue
            src = str(it.get('source') or '').lower()
            torrent = src == 'torrent' or bool(it.get('hash')) or str(it.get('url') or '').startswith('magnet:')
            o = {
                'n': str(it.get('name') or it.get('release_title') or ''),
                'q': str(it.get('quality') or 'SD'),
                's': int(it.get('seeders') or 0) if str(it.get('seeders') or '0').isdigit() else 0,
                'b': _bytes(it.get('size')),
                'p': str(it.get('provider') or provider),
                'l': str(it.get('language') or 'en'),
                'i': str(it.get('info') or ''),
            }
            if torrent:
                h = _hash(it)
                if not h:
                    continue
                o['h'] = h
                o['k'] = 'torrent'
            else:
                o['k'] = 'hoster'
                o['host'] = src
                o['u'] = str(it.get('url') or '')
                o['d'] = bool(it.get('direct'))
            pk = it.get('package') or (pack if pack else '')
            if pk:
                o['pk'] = str(pk)
                if it.get('episode_start'):
                    o['es'] = int(it.get('episode_start') or 0)
                    o['ee'] = int(it.get('episode_end') or 0)
            out.append(o)
        except Exception:
            continue
    return out


def cancel(job_id):
    ev = _jobs.get(job_id)
    if ev is not None:
        ev.set()


def scrape(job_id, info_json, providers_json, opts_json, sink):
    """
    Blocking. Runs the providers for one title; each one's sources go to sink.results(provider, json)
    as it returns, then sink.done(provider, count, ms, error). Ends when all are done, the time is up
    or the job is cancelled. Returns the number of sources delivered.
    """
    info = json.loads(info_json)
    providers = json.loads(providers_json)
    opts = json.loads(opts_json)
    total_s = float(opts.get('timeout', 20))
    per_s = float(opts.get('per', 12))
    lane = opts.get('lane', 'play')
    size = int(opts.get('threads', 8))
    packs = bool(opts.get('packs', False))
    host_dict = list(opts.get('hosts', []))
    ev = threading.Event()
    _jobs[job_id] = ev
    t0 = time.time()
    episode = 'tvshowtitle' in info
    data = dict(info)
    data['timeout'] = max(3, int(per_s) - 1)
    state = {}       # task key -> [started, finished, count]
    st_lock = threading.Lock()
    delivered = [0]
    ent = _pool(lane, size)
    if len(_jobs) == 1:
        # Nothing else running: per-provider caches of earlier titles can go (CocoScrapers' own sources() does this).
        for c in list(_classes.values()):
            if hasattr(c, 'clear_all'):
                try:
                    c.clear_all()
                except Exception:
                    pass

    def run(p, pack):
        key = p['id'] + ('#pack' if pack else '')
        if ev.is_set():
            with st_lock:
                state[key] = [time.time(), time.time(), -2]
            return
        started = time.time()
        with st_lock:
            state[key][0] = started
        with _lock:
            ent[2] += 1
            if not ent[3]:
                ent[3] = started
        err = None
        items = []
        try:
            host.set_current_addon(p['addon'])
            cls = _load(p)
            if cls is None:
                err = 'not loaded'
            elif episode and not getattr(cls, 'hasEpisodes', True):
                pass
            elif not episode and not getattr(cls, 'hasMovies', True):
                pass
            else:
                obj = cls()
                d = dict(data)
                if pack:
                    if getattr(cls, 'pack_capable', False) and hasattr(obj, 'sources_packs'):
                        items = obj.sources_packs(d, host_dict, search_series=False,
                                                  total_seasons=info.get('total_seasons'), bypass_filter=False)
                else:
                    items = obj.sources(d, host_dict)
        except Exception as e:
            err = '%s: %s' % (type(e).__name__, str(e)[:120])
        finally:
            with _lock:
                ent[2] -= 1
                if ent[2] <= 0:
                    ent[2] = 0
                    ent[3] = 0.0
        ms = int((time.time() - started) * 1000)
        late = ev.is_set() or (time.time() - started) > per_s
        out = [] if late else _norm(items, p['name'], p['kind'], 'season' if pack else '')
        with st_lock:
            done = state.get(key)
            if done is None or done[1]:
                return  # already reported as timed out
            done[1] = time.time()
            done[2] = len(out)
        if out:
            delivered[0] += len(out)
            try:
                sink.results(key, json.dumps(out, separators=(',', ':')))
            except Exception as e:
                host.log(3, 'result delivery failed (%s)' % e)
        try:
            sink.done(key, len(out), ms, err if err else ('late' if late else None))
        except Exception:
            pass

    tasks = []
    for p in providers:
        tasks.append((p, False))
        if packs and episode and p.get('kind') == 'torrent':
            tasks.append((p, True))
    for p, pack in tasks:
        key = p['id'] + ('#pack' if pack else '')
        state[key] = [0.0, 0.0, 0]
        ent[0].submit(run, p, pack)
    try:
        while True:
            if ev.wait(0.1):
                break
            now = time.time()
            if now - t0 > total_s:
                break
            pending = 0
            with st_lock:
                expired = []
                for key, s in state.items():
                    if s[1]:
                        continue
                    if s[0] and now - s[0] > per_s:
                        s[1] = now
                        s[2] = -1
                        expired.append((key, int((now - s[0]) * 1000)))
                    else:
                        pending += 1
            for key, ms in expired:
                try:
                    sink.done(key, -1, ms, 'timeout')
                except Exception:
                    pass
            if pending == 0:
                break
        # Whatever never answered by the end counts as timed out.
        now = time.time()
        with st_lock:
            left = [(k, s) for k, s in state.items() if not s[1]]
            for k, s in left:
                s[1] = now
                s[2] = -1
        for k, s in left:
            try:
                sink.done(k, -1, int((now - (s[0] or t0)) * 1000), 'cancelled' if ev.is_set() else 'timeout')
            except Exception:
                pass
    finally:
        _jobs.pop(job_id, None)
    return delivered[0]
