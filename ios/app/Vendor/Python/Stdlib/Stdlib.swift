import Foundation

// Resource-only target: the Python 3.13 standard library (BeeWare Python-Apple-support 3.13-b18, trimmed) and the
// pure-Python requests stack, copied into the app as-is. See docs/port/scrapers.md.
public enum DreamPythonStdlib {
    /// The folder that holds lib/python3.13 and site-packages.
    public static var home: URL? { Bundle.module.url(forResource: "python", withExtension: nil) }
}
