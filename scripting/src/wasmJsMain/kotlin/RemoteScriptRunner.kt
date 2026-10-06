package fr.godox.replikube.scripting

import fr.godox.replikube.core.GridSize
import fr.godox.replikube.core.log.Log
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.asJsException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Runs player code by posting it to a compiler service, for the browser build.
 *
 * ### Why this exists rather than a port of the local runner
 *
 * Not a port: there is nothing to port. The local runner embeds `kotlin-compiler-embeddable` and
 * drives it on a thread it owns, and a browser has no JVM to hold that jar, no filesystem to
 * unpack it into, no threads to drive it on, and will not let a module fetch forty megabytes at
 * startup. The question for wasm is therefore not *how* to run a compiler but *where* one runs,
 * and the answer is: somewhere else. This class is the client half of that answer.
 *
 * What is preserved is the **contract**, which is the part the game can see: the same
 * `ScriptRunner.run`, the same `RunResult`, the same `Diagnostic.Kind` for the same cause. A
 * compile error points at the same line of the player's source on both targets, because both
 * build the same wrapper and share `SolutionCompiler.generate`'s line mapping. A player who learns
 * the game on one target is not learning a second game on the other.
 *
 * ### What is *not* preserved
 *
 * - **The sandbox is thinner.** Locally, `:dsl` enters as a jar on a classpath holding nothing
 *   else. Here it enters as source text, because the service has no classpath of ours to extend.
 *   The player's reach is therefore `:dsl`'s dependency list rather than the empty list
 *   `playerClasspath()` enforces on the JVM. `dsl/build.gradle.kts` says so next to its
 *   (empty) dependency block.
 * - **The timeout belongs to someone else.** The local budget covers compiling *and* running in
 *   one process. Here the service enforces its own limit server-side and reports it as an ordinary
 *   HTTP 200, so the deadline below is a backstop against a service that hangs rather than a bound
 *   on the player's program.
 * - **It needs a network.** A packaged desktop build runs offline forever. This one does not.
 *
 * ### Threading
 *
 * Stateless apart from its endpoint, so it satisfies the same `ScriptRunner` contract the local
 * runner does. The browser has one thread; `run` suspends rather than blocking it, which is the
 * whole reason the local runner's `Dispatchers.IO` has no counterpart here.
 *
 * @param endpoint where the sources go. Overridable so a self-hosted compiler service can be
 *   substituted without touching this class.
 */
class RemoteScriptRunner(
    private val endpoint: String = DEFAULT_ENDPOINT,
) : ScriptRunner {

    override suspend fun run(source: String, size: GridSize, timeoutMillis: Long): RunResult {
        if (source.isBlank()) {
            // Checked before the request, and with the same wording as the local runner. An empty
            // editor sent to the service would come back as "a return expression required", which
            // is true and tells the player nothing.
            return RunResult.Failure(
                Diagnostic(
                    Diagnostic.Kind.CONTRACT_ERROR,
                    "There is nothing to run yet.",
                    detail = "Write the body of the solution: an expression of a colour, " +
                        "`null` to leave a voxel empty, or a `when` over the coordinates.",
                ),
            )
        }

        val program = RemoteProgram.build(source, size)
        Log.i(TAG, "run() start: ${source.length} chars, grid $size, ${program.files.size} files")

        val body = try {
            RemoteJson.encodeToString(CompilerRunRequest.serializer(), CompilerRunRequest(files = program.files))
        } catch (t: Throwable) {
            // Encoding cannot fail on well-formed data, which is what `program` holds, so this is
            // the seam catching a future change rather than a condition to explain to a player.
            Log.e(TAG, "could not encode the request", t)
            return RunResult.Failure(
                Diagnostic(
                    Diagnostic.Kind.INTERNAL_ERROR,
                    "replikube could not prepare your solution to be sent.",
                    detail = "${t::class.simpleName}: ${t.message}",
                ),
            )
        }

        val response = try {
            withTimeout(timeoutMillis.milliseconds) { post(body) }
        } catch (_: TimeoutCancellationException) {
            // Our deadline, which fires only if the service itself hangs. A program that merely
            // overruns the *service's* watchdog comes back as a normal response and is reported
            // as a timeout in `toRunResult`, quoting the service's own words.
            Log.w(TAG, "the compiler service did not answer within ${timeoutMillis}ms")
            return RunResult.Failure(
                Diagnostic(
                    Diagnostic.Kind.TIMEOUT,
                    "The compiler service did not answer.",
                    detail = "It had ${timeoutMillis}ms. This is usually the network rather " +
                        "than your code. The service also enforces its own, separate limit on " +
                        "how long a program may run, and reports that as a timeout.",
                ),
            )
        } catch (t: Throwable) {
            // A request can fail for every reason a network has, most of them invisible from
            // here: offline, no route, DNS, a CORS refusal. The player cannot act on any of them,
            // so the message says the one true thing and the detail carries the rest.
            Log.e(TAG, "the request to the compiler service failed", t)
            return RunResult.Failure(
                Diagnostic(
                    Diagnostic.Kind.INTERNAL_ERROR,
                    "replikube could not reach the compiler service.",
                    detail = "${t::class.simpleName}: ${t.message}",
                ),
            )
        }

        return response.toRunResult(size, program)
    }

    /**
     * POST [body] and read the response.
     *
     * `ok` is checked rather than assumed: `fetch` fails in two unrelated ways and neither is an
     * exception -- it *rejects* on a network error and *resolves* with `ok == false` on an HTTP
     * error. Reading `ok` explicitly is what turns a 503 into a sentence, instead of handing a
     * page of HTML to a JSON parser and reporting a parse error as a compiler problem.
     */
    @OptIn(ExperimentalWasmJsInterop::class)
    private suspend fun post(body: String): CompilerRunResponse {
        val response = fetchResponse(endpoint, body).await()
        if (!response.ok) {
            throw HttpFailure(response.status, response.statusText)
        }
        // `toString()` on a JsString: a Promise cannot carry a Kotlin String, so the boundary
        // between the two is exactly here and nowhere else.
        val raw = response.text().await().toString()
        Log.i(TAG, "response: ${raw.length} chars, HTTP ${response.status}")
        return RemoteJson.decodeFromString(CompilerRunResponse.serializer(), raw)
    }
}

/**
 * Suspends until this promise settles.
 *
 * Kotlin/Wasm 2.4.20's standard library has no `Promise.await()` — `kotlin.js` declares
 * `then`, `catch` and `finally` and nothing to turn them into a suspension, so this is the
 * bridge, and it is written once rather than at every call site.
 *
 * Both handlers are given to `then`, not chained as `.then(...).catch(...)`. A rejected promise
 * whose rejection has no handler reports itself as an unhandled rejection, which in a browser
 * means a console error with no stack — so the failure is routed into the coroutine, where the
 * caller's `catch` reports it properly instead.
 *
 * The two awkward-looking details are forced by the signature rather than chosen. `then`'s
 * handler must return `JsAny?` and its rejection argument is a `JsAny`, not a `Throwable`:
 * a browser rejects with an `Error`, and `JsException` is the stdlib's own wrapper for one.
 * Both handlers therefore end in `null`, because the resumption is the only effect worth having
 * and the return value is discarded by the caller anyway.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private suspend fun <T : JsAny> Promise<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        then<JsAny?>(
            onFulfilled = { continuation.resume(it); null },
            onRejected = { continuation.resumeWithException(it.asJsException()); null },
        )
    }

/** An HTTP status outside 2xx. Its own type so the catch above can name it in a log. */
private class HttpFailure(val status: Int, val statusText: String) :
    Exception("HTTP $status $statusText")

/**
 * The service.
 *
 * The version is the project's own, matching `kotlin` in `libs.versions.toml`, so a solution that
 * compiles on the desktop compiles here. Pinning it is the point: a Kotlin release that changed
 * the meaning of `when` would otherwise make the browser and the desktop disagree about the same
 * source. It has to be changed by hand alongside that version -- the wasm build has no version
 * catalogue of its own to read it from.
 */
const val DEFAULT_ENDPOINT: String = "https://api.kotlinlang.org/api/2.4.20/compiler/run"

/** Log tag for this file, so one `grep` isolates the remote runner. Mirrors the local runner's. */
private const val TAG = "ScriptRunner"

/**
 * The parts of a `fetch` response this class reads.
 *
 * External declarations rather than `js("...")` inline, following `core`'s wasm `Log`: Kotlin/Wasm
 * has no `dynamic`, so an inline `js("r.ok ? r.text() : null")` could not be null-checked, wrapped
 * in a `try`, or stored in a `val` with a type. An external signature is checked by the compiler
 * and keeps the branching on this side where it can be reasoned about.
 *
 * `: JsAny` because that is `Promise`'s bound: a `Promise` may only carry a `JsAny?`, so a
 * response object has to be one to be awaited at all. `text()` returns `Promise<JsString>` for
 * the same reason, and the conversion to a Kotlin [String] happens where it is read.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private external interface JsResponse : JsAny {
    val ok: Boolean
    val status: Int
    val statusText: String
    fun text(): Promise<JsString>
}

/**
 * `fetch(url, { method: 'POST', body, headers })`.
 *
 * `content-type` is not decoration: the service rejects a request without it, and the rejection
 * is an HTTP 400 whose body is not the JSON this class expects.
 *
 * The request object is built here rather than declared as a top-level `val`, because it carries
 * a per-run body -- and a browser rejects a request whose body has been consumed, so reusing one
 * would work exactly once.
 */
@OptIn(ExperimentalWasmJsInterop::class)
private fun fetchResponse(url: String, body: String): Promise<JsResponse> = js(
    "fetch(url, { method: 'POST', headers: { 'content-type': 'application/json' }, body: body })",
)