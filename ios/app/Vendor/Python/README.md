# Vendor/Python

Third-party, downloaded, not edited (except trimming). Rebuilt by the Scrapers worker's vendor script
(see docs/port/scrapers.md).

- `Python.xcframework`: BeeWare Python-Apple-support 3.13-b18 (CPython 3.13.18), device slice only
  (Info.plist trimmed to `ios-arm64`). PSF license (Stdlib/python/lib/python3.13/LICENSE.txt).
- `Stdlib/python/lib`: the matching standard library and `lib-dynload` extension modules, without test/, idlelib,
  tkinter, turtledemo, ensurepip, pydoc_data, venv, config-3.13 and the _test* modules.
- `Stdlib/python/site-packages`: requests 2.32.3, urllib3 2.2.3, idna 3.10, charset-normalizer 3.4.0 (pure-Python
  wheel), certifi 2024.8.30 (py3-none-any wheels from PyPI).
- `Stdlib/Stdlib.swift`: ours; the resource target's accessor.
