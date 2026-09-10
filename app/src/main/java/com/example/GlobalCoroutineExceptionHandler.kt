package com.example

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
 * we treat them as "handled": the process stays alive, the full trace lands
 * in [CrashLog] (sign-in screen → 🐞 Report), and the UI still falls back to
 * its normal error path because the failed network op never completes.
 */
class GlobalCoroutineExceptionHandler : CoroutineExceptionHandler {

    override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        try {
            CrashLog.record("uncaught-coroutine", exception)
        } catch (_: Throwable) {
            // Must never throw from here — it would re-escalate to the OS.
        }
    }
}
