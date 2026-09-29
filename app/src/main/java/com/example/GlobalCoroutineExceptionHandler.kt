package com.example

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * GLOBAL uncaught-coroutine-exceptions handler, installed via the
 * `META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler`
 * ServiceLoader contract (see app/src/main/resources).
 *
 * Why this exists: the JavaSteam client launches its websocket receive /
 * connect / send loops on ITS OWN library scope, and only guards them with
 * `catch (e: Exception)`. Any `Error` raised inside that scope
 * (class-loading, linkage, verification, engine setup, ...) escapes into the
 * app's uncaught-exception pipeline and instantly kills the process — the
 * "press Sign In and the app vanishes" bug.
 *
 * When this handler is installed, kotlinx-coroutines routes those throwables
 * here FIRST (before the thread's default handler). By simply not rethrowing
 * we treat them as "handled": the process stays alive. The error is NOT
 * swallowed though — the full trace lands in [CrashLog] (sign-in screen →
 * 🐞 Report), a logcat line is emitted for adb/bug reports, and a short
 * message is surfaced to the human as an error state via [AppErrors] instead
 * of letting the app silently continue as if nothing happened.
 */
class GlobalCoroutineExceptionHandler : CoroutineExceptionHandler {

    override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        try {
            CrashLog.record("uncaught-coroutine", exception)
        } catch (_: Throwable) {
            // Must never throw from here — it would re-escalate to the OS.
        }
        try {
            Log.e(TAG, "Uncaught coroutine error (kept the app alive)", exception)
        } catch (_: Throwable) {
        }
        try {
            AppErrors.publish(
                "Something went wrong (${exception.javaClass.simpleName}) — " +
                    "open the 🐞 Report dialog for the full details."
            )
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "DepotDownloader"
    }
}
