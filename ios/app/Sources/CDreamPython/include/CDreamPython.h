#pragma once
// A small C bridge to the embedded CPython (Vendor/Python/Python.xcframework) for DreamScrapers.
// Swift only ever passes and receives UTF-8 strings; everything else lives in Python (dreambridge.py).

#ifdef __cplusplus
extern "C" {
#endif

/// Called from Python worker threads (with the interpreter lock held) for every provider answer:
/// kind 0 = results (payload: JSON array), kind 1 = done (count, ms, error or NULL).
typedef void (*dream_sink_fn)(int job, int kind, const char *provider, const char *payload, int count, int ms,
                              const char *error);
/// Python log lines (levels as the scraper modules use them: 0 debug ... 4 fatal).
typedef void (*dream_log_fn)(int level, const char *message);

void dream_py_set_callbacks(dream_sink_fn sink, dream_log_fn log);

/// Starts the interpreter once. home: the stdlib root (holds lib/python3.13); paths: sys.path; pycache: a writable
/// folder for compiled bytecode; executable: the app binary. Returns 0, or non-zero with *error set (malloc'd).
int dream_py_start(const char *home, const char *const *paths, int npaths, const char *pycache, const char *executable,
                   char **error);

/// 1 once dream_py_start succeeded.
int dream_py_started(void);

/// Calls module.function(*args) with string arguments. Returns str(result) (malloc'd; caller frees) and sets
/// *failed = 0, or the exception text with *failed = 1. Blocks until the call returns; any thread.
char *dream_py_call(const char *module, const char *function, const char *const *args, int nargs, int *failed);

#ifdef __cplusplus
}
#endif
