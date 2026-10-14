package com.phonerouter.hotspot.feature

import android.content.Context
import com.phonerouter.hotspot.root.RootShell
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Native file manager rooted at /storage/emulated/0. Uses java.io.File when the app has
 * access (All-files-access granted), and falls back to root for listing / staging so it
 * works even without that permission. All paths are confined under ROOT.
 */
class FileRepo(private val context: Context) {

    private val rootDir = File(ROOT)

    private fun resolve(path: String?): File {
        val p = (path ?: "/").ifBlank { "/" }
        val f = if (p.startsWith(ROOT)) File(p) else File(rootDir, p.removePrefix("/"))
        val canon = f.canonicalFile
        if (canon.path != ROOT && !canon.path.startsWith("$ROOT/")) {
            throw SecurityException("path out of bounds")
        }
        return canon
    }

    fun rel(f: File): String = when {
        f.path == ROOT -> "/"
        f.path.startsWith("$ROOT/") -> f.path.removePrefix(ROOT)
        else -> f.path
    }

    fun list(path: String?): JSONObject {
        val dir = resolve(path)
        val arr = JSONArray()
        val native = dir.listFiles()
        if (native != null) {
            for (f in native.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))) {
                arr.put(entry(f.name, f.isDirectory, f.length(), f.lastModified()))
            }
        } else {
            // root fallback: parse `ls` with a stat-based format
            val res = RootShell.run(
                "for e in '${dir.path}'/* '${dir.path}'/.*; do " +
                    "[ -e \"\$e\" ] || continue; " +
                    "n=\$(basename \"\$e\"); [ \"\$n\" = '.' -o \"\$n\" = '..' ] && continue; " +
                    "if [ -d \"\$e\" ]; then t=d; else t=f; fi; " +
                    "s=\$(stat -c %s \"\$e\" 2>/dev/null); m=\$(stat -c %Y \"\$e\" 2>/dev/null); " +
                    "echo \"\$t|\$s|\$m|\$n\"; done"
            )
            for (line in res.out.lines()) {
                val parts = line.split("|", limit = 4)
                if (parts.size < 4) continue
                arr.put(
                    entry(
                        parts[3], parts[0] == "d",
                        parts[1].toLongOrNull() ?: 0L,
                        (parts[2].toLongOrNull() ?: 0L) * 1000
                    )
                )
            }
        }
        return JSONObject().put("ok", true).put("path", rel(dir)).put("entries", arr)
    }

    private fun entry(name: String, dir: Boolean, size: Long, mtime: Long) = JSONObject().apply {
        put("name", name); put("dir", dir); put("size", size); put("mtime", mtime)
    }

    fun mkdir(path: String?, name: String): JSONObject {
        val dir = resolve(path)
        val target = File(dir, name)
        resolve(target.path) // bounds check
        var ok = target.mkdirs()
        if (!ok && !target.exists()) {
            ok = RootShell.run("mkdir -p '${target.path}' && echo OK").out.contains("OK")
        }
        return JSONObject().put("ok", ok || target.exists())
    }

    fun delete(path: String?): JSONObject {
        val f = resolve(path)
        if (f.path == ROOT) return JSONObject().put("ok", false).put("error", "拒绝删除根目录")
        var ok = f.deleteRecursively()
        if (!ok && f.exists()) {
            ok = RootShell.run("rm -rf '${f.path}' && echo OK").out.contains("OK")
        }
        return JSONObject().put("ok", ok || !f.exists())
    }

    fun rename(path: String?, newName: String): JSONObject {
        val f = resolve(path)
        val dest = File(f.parentFile, newName)
        resolve(dest.path)
        var ok = f.renameTo(dest)
        if (!ok) ok = RootShell.run("mv '${f.path}' '${dest.path}' && echo OK").out.contains("OK")
        return JSONObject().put("ok", ok)
    }

    /**
     * Returns a File that is readable by this app for streaming to the client. If the
     * target isn't directly readable, it is staged into cache via root.
     */
    fun prepareDownload(path: String?): File? {
        val f = resolve(path)
        if (f.isDirectory) return null
        if (f.canRead()) return f
        val tmp = File(context.cacheDir, "dl_${System.currentTimeMillis()}_${f.name}")
        val ok = RootShell.run(
            listOf("cp '${f.path}' '${tmp.path}'", "chmod 644 '${tmp.path}'", "echo OK")
        ).out.contains("OK")
        return if (ok && tmp.exists()) tmp else null
    }

    /** Save uploaded bytes (already staged at [tempFile]) into directory [path] as [name]. */
    fun saveUpload(path: String?, name: String, tempFile: File): JSONObject {
        val dir = resolve(path)
        val dest = File(dir, name)
        resolve(dest.path)
        return try {
            if (dir.canWrite()) {
                tempFile.copyTo(dest, overwrite = true)
            } else {
                val ok = RootShell.run(
                    listOf("cp '${tempFile.path}' '${dest.path}'", "echo OK")
                ).out.contains("OK")
                if (!ok) throw IllegalStateException("写入失败")
            }
            JSONObject().put("ok", true).put("name", name)
        } catch (t: Throwable) {
            JSONObject().put("ok", false).put("error", t.message ?: "上传失败")
        } finally {
            tempFile.delete()
        }
    }

    companion object {
        const val ROOT = "/storage/emulated/0"
    }
}
