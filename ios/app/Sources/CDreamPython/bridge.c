#include <Python/Python.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include "CDreamPython.h"

static dream_sink_fn g_sink = NULL;
static dream_log_fn g_log = NULL;
static atomic_int g_started = 0;

void dream_py_set_callbacks(dream_sink_fn sink, dream_log_fn log) {
    g_sink = sink;
    g_log = log;
}

int dream_py_started(void) { return atomic_load(&g_started); }

// ---- the _dreamsink built-in module: how Python hands results and log lines back ----

static PyObject *sink_results(PyObject *self, PyObject *args) {
    int job;
    const char *provider, *payload;
    if (!PyArg_ParseTuple(args, "iss", &job, &provider, &payload)) return NULL;
    if (g_sink) g_sink(job, 0, provider, payload, 0, 0, NULL);
    Py_RETURN_NONE;
}

static PyObject *sink_done(PyObject *self, PyObject *args) {
    int job, count, ms;
    const char *provider, *error = NULL;
    if (!PyArg_ParseTuple(args, "isiiz", &job, &provider, &count, &ms, &error)) return NULL;
    if (g_sink) g_sink(job, 1, provider, "", count, ms, error);
    Py_RETURN_NONE;
}

static PyObject *sink_log(PyObject *self, PyObject *args) {
    int level;
    const char *msg;
    if (!PyArg_ParseTuple(args, "is", &level, &msg)) return NULL;
    if (g_log) g_log(level, msg);
    Py_RETURN_NONE;
}

static PyMethodDef sink_methods[] = {
    {"results", sink_results, METH_VARARGS, NULL},
    {"done", sink_done, METH_VARARGS, NULL},
    {"log", sink_log, METH_VARARGS, NULL},
    {NULL, NULL, 0, NULL},
};

static struct PyModuleDef sink_module = {PyModuleDef_HEAD_INIT, "_dreamsink", NULL, -1, sink_methods};

static PyObject *init_sink(void) { return PyModule_Create(&sink_module); }

// ---- start ----

static char *dup_status(const char *what, PyStatus st) {
    const char *msg = st.err_msg ? st.err_msg : "unknown";
    size_t n = strlen(what) + strlen(msg) + 4;
    char *out = malloc(n);
    if (out) snprintf(out, n, "%s: %s", what, msg);
    return out;
}

int dream_py_start(const char *home, const char *const *paths, int npaths, const char *pycache, const char *executable,
                   char **error) {
    if (atomic_load(&g_started)) return 0;
    if (error) *error = NULL;
    if (PyImport_AppendInittab("_dreamsink", init_sink) != 0) {
        if (error) *error = strdup("couldn't register the results module");
        return 1;
    }
    PyConfig config;
    PyConfig_InitIsolatedConfig(&config);
    config.install_signal_handlers = 0;
    config.write_bytecode = 1;
    config.buffered_stdio = 0;
    PyStatus st = PyConfig_SetBytesString(&config, &config.home, home);
    if (!PyStatus_Exception(st)) st = PyConfig_SetBytesString(&config, &config.pycache_prefix, pycache);
    if (!PyStatus_Exception(st) && executable) st = PyConfig_SetBytesString(&config, &config.executable, executable);
    if (!PyStatus_Exception(st) && executable) st = PyConfig_SetBytesString(&config, &config.program_name, executable);
    if (PyStatus_Exception(st)) {
        if (error) *error = dup_status("config", st);
        PyConfig_Clear(&config);
        return 2;
    }
    config.module_search_paths_set = 1;
    for (int i = 0; i < npaths; i++) {
        wchar_t *w = Py_DecodeLocale(paths[i], NULL);
        if (!w) continue;
        st = PyWideStringList_Append(&config.module_search_paths, w);
        PyMem_RawFree(w);
        if (PyStatus_Exception(st)) {
            if (error) *error = dup_status("path", st);
            PyConfig_Clear(&config);
            return 3;
        }
    }
    st = Py_InitializeFromConfig(&config);
    PyConfig_Clear(&config);
    if (PyStatus_Exception(st)) {
        if (error) *error = dup_status("start", st);
        return 4;
    }
    // Hand the interpreter lock back: every later call takes it with PyGILState_Ensure on its own thread.
    PyEval_SaveThread();
    atomic_store(&g_started, 1);
    return 0;
}

// ---- calls ----

static char *exception_text(void) {
    PyObject *type = NULL, *value = NULL, *tb = NULL;
    PyErr_Fetch(&type, &value, &tb);
    PyErr_NormalizeException(&type, &value, &tb);
    char *out = NULL;
    PyObject *s = value ? PyObject_Str(value) : NULL;
    const char *name = type ? ((PyTypeObject *)type)->tp_name : "Error";
    const char *text = s ? PyUnicode_AsUTF8(s) : NULL;
    size_t n = strlen(name) + (text ? strlen(text) : 0) + 4;
    out = malloc(n);
    if (out) snprintf(out, n, "%s: %s", name, text ? text : "");
    Py_XDECREF(s);
    Py_XDECREF(type);
    Py_XDECREF(value);
    Py_XDECREF(tb);
    PyErr_Clear();
    return out;
}

char *dream_py_call(const char *module, const char *function, const char *const *args, int nargs, int *failed) {
    if (failed) *failed = 1;
    if (!atomic_load(&g_started)) return strdup("not started");
    PyGILState_STATE g = PyGILState_Ensure();
    char *out = NULL;
    PyObject *mod = PyImport_ImportModule(module);
    PyObject *fn = mod ? PyObject_GetAttrString(mod, function) : NULL;
    PyObject *tuple = fn ? PyTuple_New(nargs) : NULL;
    if (tuple) {
        for (int i = 0; i < nargs; i++) {
            PyObject *a = PyUnicode_DecodeUTF8(args[i], (Py_ssize_t)strlen(args[i]), "replace");
            if (!a) { Py_CLEAR(tuple); break; }
            PyTuple_SET_ITEM(tuple, i, a);
        }
    }
    PyObject *r = tuple ? PyObject_CallObject(fn, tuple) : NULL;
    if (r) {
        PyObject *s = PyObject_Str(r);
        const char *c = s ? PyUnicode_AsUTF8(s) : NULL;
        if (c) {
            out = strdup(c);
            if (failed) *failed = 0;
        } else {
            out = exception_text();
        }
        Py_XDECREF(s);
    } else {
        out = exception_text();
    }
    Py_XDECREF(r);
    Py_XDECREF(tuple);
    Py_XDECREF(fn);
    Py_XDECREF(mod);
    PyGILState_Release(g);
    return out;
}
