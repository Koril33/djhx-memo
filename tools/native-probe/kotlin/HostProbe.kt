import java.nio.file.Files
import uniffi.djhx_native_probe.CommitListener
import uniffi.djhx_native_probe.ProbeException
import uniffi.djhx_native_probe.probe

fun main() {
    val directory = Files.createTempDirectory("djhx-kotlin-probe-")
    try {
        val database = directory.resolve("probe.sqlite")
        var commits = 0
        val listener = object : CommitListener {
            override fun committed() {
                commits += 1
            }
        }

        check(probe(database.toString(), listener) == "中文持久化探针")
        check(commits == 1)

        try {
            probe(directory.toString(), listener)
            error("Opening a directory as a database unexpectedly succeeded")
        } catch (_: ProbeException.Database) {
            check(commits == 1)
        }
        println("Kotlin callback, SQLite readback and error mapping passed")
    } finally {
        directory.toFile().deleteRecursively()
    }
}
