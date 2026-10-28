# The iPhone app's entry points into the scraper runner (ScraperEngine.swift calls these through CDreamPython).
# Every argument arrives as a string; results go back through the built-in _dreamsink module.

import _dreamsink

from dreamhost import runner


class _Sink(object):
    def __init__(self, job):
        self.job = job

    def results(self, provider, payload):
        _dreamsink.results(self.job, str(provider), str(payload))

    def done(self, provider, count, ms, error):
        _dreamsink.done(self.job, str(provider), int(count), int(ms), None if error is None else str(error))


def init(home, temp, verbose):
    return runner.init(home, temp, verbose == '1')


def set_modules(libs_json):
    return runner.set_modules(libs_json)


def warm(providers_json):
    return runner.warm(providers_json)


def reload_settings():
    runner.reload_settings()
    return 0


def trim():
    return runner.trim()


def cancel(job_id):
    runner.cancel(int(job_id))
    return 0


def scrape(job_id, info_json, providers_json, opts_json):
    return runner.scrape(int(job_id), info_json, providers_json, opts_json, _Sink(int(job_id)))


def version():
    import sys
    import ssl
    return '%s; %s' % (sys.version.split()[0], ssl.OPENSSL_VERSION)
