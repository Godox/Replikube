package fr.godox.replikube.app.progress

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Progress in `~/.local/share/replikube/progress.json`.
 *
 * ### Why the temp file and the move
 *
 * A save is read at startup and written on every solve. Writing it in place means a crash, a
 * full disk or a pulled power cord can leave truncated JSON exactly where a valid save used
 * to be -- and `ProgressStore.load` treats unreadable JSON as *empty progress*. So the worst
 * possible moment to lose the file is the one where the game would have quietly reset itself.
 *
 * Writing a sibling temp file and moving it over the original means a reader sees either the
 * old save or the new one. `Files.move` on the same filesystem is a rename, which is atomic
 * on every platform this ships to.
 *
 * ### Why `REPLACE_EXISTING` and not `ATOMIC_MOVE`
 *
 * `ATOMIC_MOVE` would be the more direct expression of the same intent, and it is what the
 * KDoc claimed this class did. It is not passed because it throws
 * `AtomicMoveNotSupportedException` on some filesystems, and a save that fails to write
 * because the filesystem declined to be atomic is worse than one that writes. The temp-file
 * construction already makes the operation atomic in practice: there is no window in which
 * the real file is partially written, only a window in which a rename is refused, and
 * [write] reports that as `false`.
 */
class FileProgressStorage(private val file: Path) : ProgressStorage {

    override fun read(): String? =
        if (file.exists()) runCatching { file.readText() }.getOrNull() else null

    override fun write(text: String): Boolean = runCatching {
        file.parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling("${file.name}.tmp")
        Files.writeString(tmp, text)
        // A leftover temp file from a killed process would otherwise make every later write
        // fail on a read-only directory that the stale file is not in.
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    override fun quarantine(): Boolean = runCatching {
        Files.move(file, file.resolveSibling("${file.name}.corrupt"), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    companion object {

        /**
         * `~/.local/share/replikube/progress.json`, following the XDG base directory spec.
         *
         * `XDG_DATA_HOME` is honoured when set, which is what makes it easy to throw away a
         * save while testing without touching the real one.
         *
         * Not `user.home`-only on purpose: a packaged AppImage can run with a home directory
         * it cannot write, and the XDG variable is the documented way to redirect it.
         */
        fun defaultPath(): Path {
            val xdg = System.getenv("XDG_DATA_HOME")
            val base = if (xdg.isNullOrBlank()) {
                val home = System.getProperty("user.home") ?: "."
                File(home, ".local/share")
            } else {
                File(xdg)
            }
            return base.toPath().resolve("replikube").resolve("progress.json")
        }
    }
}

