package kotlinx.coroutines.debug.manager

import java.io.PrintWriter
import java.io.StringWriter

fun Any?.toStringSafe() = try {
    toString()
} catch (e: Throwable) {
    "<can't be printed ($e)>"
}

fun Throwable.stackTraceToString(): String {
    val writer = StringWriter()
    printStackTrace(PrintWriter(writer))
    return writer.toString()
}