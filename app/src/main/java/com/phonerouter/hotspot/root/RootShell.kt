package com.phonerouter.hotspot.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/** Runs shell commands through `su` (optionally as a specific uid, e.g. 1000 = system). */
object RootShell {

    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    /** Quick check that we can obtain root. */
    fun hasRoot(): Boolean = try {
        run(listOf("id")).out.contains("uid=0")
    } catch (t: Throwable) {
        false
    }

    /**
     * Run [commands] inside a single su session. [uid] null = root (0); 1000 = system, etc.
     * stderr is merged into stdout. Each command is written on its own line.
     */
    fun run(commands: List<String>, uid: Int? = null, timeoutMs: Long = 30_000): Result {
        val argv = if (uid == null) arrayOf("su") else arrayOf("su", uid.toString())
        val proc = ProcessBuilder(*argv).redirectErrorStream(true).start()

        val sb = StringBuilder()
        val reader = Thread {
            BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null) {
                    sb.append(line).append('\n')
                    line = r.readLine()
                }
            }
        }
        reader.start()

        OutputStreamWriter(proc.outputStream).use { w ->
            for (c in commands) {
                w.write(c); w.write("\n")
            }
            w.write("exit\n")
            w.flush()
        }

        val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!finished) {
            proc.destroyForcibly()
            reader.join(1000)
            return Result(-1, sb.toString())
        }
        reader.join(2000)
        return Result(proc.exitValue(), sb.toString())
    }

    fun run(command: String, uid: Int? = null, timeoutMs: Long = 30_000): Result =
        run(listOf(command), uid, timeoutMs)
}
