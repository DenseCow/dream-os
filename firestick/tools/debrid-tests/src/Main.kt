package debridtest

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    Mock.start()
    println("Debrid contract tests: real DebridApi.kt + DebridOthers.kt against a local mock on 127.0.0.1:${Mock.port} (offline; not a live test)")
    val only = args.toSet()
    if (only.isEmpty() || "pm" in only) PremiumizeTests.run()
    if (only.isEmpty() || "tb" in only) TorBoxTests.run()
    if (only.isEmpty() || "rd" in only) RealDebridTests.run()
    if (only.isEmpty() || "ad" in only) AllDebridTests.run()
    exitProcess(if (T.summary()) 0 else 1)
}
