# Dream OS for Windows: the scraper helper process (ScraperHost.kt starts it with pythonw -I).
#
# Speaks JSON lines with the app on its own copies of stdin / stdout; the real stdin / stdout then point
# at nothing, so a scraper's print() or input() can never corrupt the channel (print goes to stderr,
# which the app reads into its log).
#
# App -> host, one object per line:   {"id": 7, "op": "scrape", ...}
# Host -> app, one object per line:
#   {"re": 7, "ok": <value>}             the answer to request 7 (or {"re": 7, "err": "..."})
#   {"ev": "r", "job": 3, "p": "...", "items": [...]}   a provider's sources (runner's normalized JSON)
#   {"ev": "d", "job": 3, "p": "...", "n": 12, "ms": 840, "err": null}   a provider finished
#   {"ev": "log", "lvl": 2, "msg": "..."}
# Every request but ping / cancel / exit runs on its own thread, so a cancel is never stuck behind a scrape.
# The app closing stdin (or going away) ends the process.

import json
import os
import sys
import threading
import time

_HERE = os.path.dirname(os.path.abspath(__file__))
_out = None
_out_lock = threading.Lock()


def _send_raw(line):
    data = line.encode('utf-8', 'replace') + b'\n'
    with _out_lock:
        try:
            _out.write(data)
            _out.flush()
        except Exception:
            os._exit(3)   # the app is gone


def _send(obj):
    _send_raw(json.dumps(obj, separators=(',', ':'), ensure_ascii=False))


def _log(level, msg):
    _send({'ev': 'log', 'lvl': int(level), 'msg': str(msg)})


class _Sink(object):
    def __init__(self, job):
        self.job = int(job)

    def results(self, provider, payload):
        # payload is already the runner's compact JSON array: passed through as is.
        _send_raw('{"ev":"r","job":%d,"p":%s,"items":%s}' % (self.job, json.dumps(str(provider)), payload))

    def done(self, provider, count, ms, error):
        _send({'ev': 'd', 'job': self.job, 'p': str(provider), 'n': int(count), 'ms': int(ms),
               'err': None if error is None else str(error)})


def _handle(req, runner):
    rid = req.get('id')
    op = req.get('op')
    try:
        if op == 'init':
            ok = runner.init(req['home'], req['temp'], bool(req.get('verbose')), _log)
        elif op == 'set_modules':
            ok = runner.set_modules(json.dumps(req.get('libs') or []))
        elif op == 'warm':
            ok = runner.warm(json.dumps(req.get('providers') or []))
        elif op == 'reload_settings':
            runner.reload_settings()
            ok = 0
        elif op == 'scrape':
            job = int(req['job'])
            ok = runner.scrape(job, json.dumps(req.get('info') or {}), json.dumps(req.get('providers') or []),
                               json.dumps(req.get('opts') or {}), _Sink(job))
        elif op == 'trim':
            ok = runner.trim()
        else:
            raise ValueError('unknown op %r' % op)
        _send({'re': rid, 'ok': ok})
    except BaseException as e:   # noqa: B902  (a provider's SystemExit must not end the host)
        _send({'re': rid, 'err': '%s: %s' % (type(e).__name__, str(e)[:300])})


def main():
    global _out
    site = None
    argv = sys.argv[1:]
    for i, a in enumerate(argv):
        if a == '--site' and i + 1 < len(argv):
            site = argv[i + 1]
    # Python runs isolated (-I and our own ._pth): our folder and the bundled requests stack are added here.
    sys.path.insert(0, _HERE)
    if site:
        sys.path.append(os.path.abspath(site))

    # The channel gets private copies of fd 0 / 1; the originals point at nothing from here on.
    proto_in = os.fdopen(os.dup(0), 'rb')
    _out = os.fdopen(os.dup(1), 'wb')
    null = os.open(os.devnull, os.O_RDWR)
    os.dup2(null, 0)
    os.dup2(null, 1)
    sys.stdin = open(os.devnull, 'r')
    err = sys.stderr
    sys.stdout = err if err is not None else open(os.devnull, 'w')

    t0 = time.time()
    from dreamhost import runner
    _send({'ev': 'hello', 'pid': os.getpid(), 'py': sys.version.split()[0],
           'ms': int((time.time() - t0) * 1000)})

    for raw in proto_in:
        line = raw.strip()
        if not line:
            continue
        try:
            req = json.loads(line.decode('utf-8', 'replace'))
        except ValueError:
            _log(3, 'unreadable request skipped')
            continue
        op = req.get('op')
        if op == 'ping':
            _send({'re': req.get('id'), 'ok': os.getpid()})
        elif op == 'cancel':
            runner.cancel(int(req.get('job', 0)))
            _send({'re': req.get('id'), 'ok': 0})
        elif op == 'exit':
            break
        else:
            threading.Thread(target=_handle, args=(req, runner), name='dream-' + str(op), daemon=True).start()
    # stdin closed: the app is gone or asked us to stop. Running provider threads end with the process.
    try:
        _out.flush()
    except Exception:
        pass
    os._exit(0)


if __name__ == '__main__':
    main()
