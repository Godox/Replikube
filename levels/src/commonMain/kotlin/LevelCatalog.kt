package fr.godox.replikube.levels

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.Level
import fr.godox.replikube.core.LoadedLevel
import fr.godox.replikube.core.TargetMatrix
import fr.godox.replikube.core.VoxelGrid
import fr.godox.replikube.core.log.Log
import kotlinx.serialization.json.Json

/**
 * The campaign: every level's metadata, its target matrix, and the Kotlin solution that
 * Reveal offers once it is beaten.
 *
 * ### Why the target is stored, not compiled
 *
 * This used to compile each level's reference solution on first open, so that a shape was
 * authored exactly once, in Kotlin, as both the definition and the demonstration. The cost was
 * that opening a level required a working Kotlin compiler *in the packaged app*: a jlink
 * runtime missing `jdk.compiler`, a `kotlin-compiler-embeddable` stripped from the app image, a
 * bytecode target newer than the bundled JVM — each of those took down all twenty levels at
 * once, with an error that named neither the level nor the cause.
 *
 * The target is now a matrix of palette ids in the level's own metadata (see [TargetMatrix]),
 * and the reference solution is text beside it. That does introduce a second representation,
 * and it could disagree — so `:levels` tests compile every reference solution and assert it
 * produces exactly the stored matrix. The guarantee is now enforced by a test instead of by
 * making the compiler a runtime dependency, which is the better trade: the failure mode of
 * forgetting to re-run the generator is a red build, not a game that will not start.
 *
 * ### Where the level files are read from
 *
 * From a Kotlin map compiled into the binary, filled at build time from the `.json` files
 * (see `generateLevelData` in `levels/build.gradle.kts`). They used to be looked up on the
 * classpath, which required a `ClassLoader` and a `file:`/`jar:` branch and worked only on a
 * JVM. Embedding them means both targets read identical bytes and there is no runtime lookup
 * to disagree between a development build and a packaged one.
 */
open class LevelCatalog {

    /**
     * Metadata for every level, in curriculum order.
     *
     * Open, and [load] open with it, purely so `:app` can substitute a catalogue that fails.
     * The failure it defends against is a level file whose matrix does not match its declared
     * size, and the only honest way to test that is to serve metadata built by hand — a level
     * file on disk cannot be made malformed just for one test, and dropping a real level file
     * on the classpath to break it would be worse. Everything else about the class is final
     * in practice; treat [levels] and [load] as the seam and nothing more.
     */
    open val levels: List<Level> by lazy {
        val ids = resourceIds()
        Log.i(TAG, "discovered ${ids.size} levels")
        // Metadata is parsed one level at a time rather than as a batch, so a single
        // unparseable file names itself in the log instead of failing an opaque `by lazy`
        // on the UI thread with no context.
        ids.map { id ->
            runCatching { readMetadata(id) }
                .onFailure { Log.e(TAG, "metadata for '$id' could not be read", it) }
                .getOrNull()
        }
            .filterNotNull()
            .sortedBy { it.difficulty }
            .also { Log.i(TAG, "catalog ready with ${it.size} playable levels") }
    }

    private val targets = mutableMapOf<String, LoadedLevel>()

    /** Ids in curriculum order. */
    val ids: List<String> get() = levels.map { it.id }

    /** Metadata for [id], or `null` if there is no such level. */
    open fun metadata(id: String): Level? = levels.firstOrNull { it.id == id }

    /**
     * The level and its target grid.
     *
     * A parse of data already in memory — no compiler, no temp directory, no code generation.
     * That is the whole point of storing the matrix: the failure modes this used to have were
     * all environmental, and a parse does not have them. The grid is cached anyway, so the
     * [VoxelGrid] copy is made once per level per session.
     */
    open suspend fun load(id: String): Result<LoadedLevel> {
        targets[id]?.let {
            Log.i(TAG, "load('$id') -> cache hit")
            return Result.success(it)
        }
        val level = metadata(id)
        if (level == null) {
            Log.e(TAG, "load('$id') -> no such level; known ids are $ids")
            return Result.failure(NoSuchLevelException(id))
        }

        // `targetGrid()` is guarded by `Level.init`, which has already checked the length and
        // the ids by the time this runs. A failure here means metadata was built by hand
        // without going through the deserializer, which is worth a loud log rather than a
        // silently blank level.
        val grid = runCatching { level.targetGrid() }.getOrElse { e ->
            Log.e(TAG, "load('$id') -> target matrix is malformed: ${e.message}", e)
            return Result.failure(IllegalStateException("Level $id has a malformed target", e))
        }

        val loaded = LoadedLevel(level, grid, level.reference)
        targets[id] = loaded
        Log.i(
            TAG,
            "load('$id') -> ok, ${grid.solidCount} solid voxels " +
                "from a ${grid.size.voxelCount}-cell matrix, colours ${grid.colorsUsed()}",
        )
        return Result.success(loaded)
    }

    /** Drops a cached grid, so the next [load] rebuilds it. Used by tests. */
    fun invalidate(id: String) {
        targets.remove(id)
    }

    companion object {
        private const val RESOURCE_DIR = "levels"
        private const val METADATA_SUFFIX = ".json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Reads one level's metadata. */
        fun readMetadata(id: String): Level {
            val text = readResource("$id$METADATA_SUFFIX")
                ?: error("No metadata for level $id")
            return json.decodeFromString(Level.serializer(), text)
        }

        /**
         * [name] is a *filename*, with its suffix.
         *
         * The embedded map is keyed by level **id**, so the suffix has to come back off
         * before the lookup. Passing the id straight through -- which is what this did at
         * first -- misses every level, and the symptom is a campaign of zero rather than an
         * error: `levels` comes back empty, `metadata(id)` returns null for everything, and
         * the first assertion to fail is a count of 20 against 0 with nothing in between to
         * say why. Hence the explicit suffix handling and the comment.
         */
        private fun readResource(name: String): String? =
            EMBEDDED_LEVEL_JSON[name.removeSuffix(METADATA_SUFFIX)]

        /**
         * Every level id in the build, discovered rather than listed by hand.
         *
         * The key set of [EMBEDDED_LEVEL_JSON], which the `generateLevelData` task filled
         * from the files in `src/commonMain/resources/levels`. A hand-written index would be
         * a second source of truth that can drift from the files it names, and a missing
         * level would surface as a crash instead of a failed build.
         *
         * ### This is discovery order, not curriculum order
         *
         * The result is sorted by filename, so this is `arch, carpet, cathedral, ...` —
         * *not* the order the game plays. For "the first level" use [LevelCatalog.ids] on an
         * instance. The two differ enough to matter: a test that asks for "the first level"
         * here silently gets `arch` (7x7x7) while the screen it is driving opens
         * `hello-layers` (3x3x3), and it fails on an assertion with nothing in the message
         * pointing at the cause.
         *
         * Untyped on purpose: this is what was *built in*, with no metadata read and therefore
         * no level-shaped knowledge in it. [LevelCatalog.levels] is the ordered, parsed,
         * playable list.
         */
        fun resourceIds(): List<String> {
            val ids = EMBEDDED_LEVEL_JSON.keys.sorted()
            Log.i(TAG, "${ids.size} level(s) embedded in the build: $ids")
            check(ids.isNotEmpty()) {
                "No levels found in /$RESOURCE_DIR/ -- was the generateLevelData task run?"
            }
            return ids
        }
    }
}

/** Log tag for everything in this file, so one `grep` isolates the catalog. */
private const val TAG = "LevelCatalog"

/** No level with the requested id. */
class NoSuchLevelException(id: String) : NoSuchElementException("No level with id '$id'")