package com.zkincamcam.xcam.core.capture

/**
 * Per-thread re-entrancy guard for the Xposed hook pipeline.
 *
 * While a guard is active on the current thread, hooks must pass calls through untouched,
 * so our own extraction code (decoding virtual.mp4, re-encoding JPEG) is never re-captured
 * by the very hooks that installed it.
 *
 * Replaces the previous `(isIgnoringHooks, setIgnoringHooks)` lambda pairs that every
 * caller had to thread through manually — and could forget to restore on an exception.
 */
internal object HookGuard {

    private val ignoring = ThreadLocal.withInitial { 0 }

    fun isIgnoring(): Boolean = ignoring.get() > 0

    /** Legacy paired API: `enter()` must be matched by `exit()` in a finally block. */
    fun enter() {
        ignoring.set(ignoring.get() + 1)
    }

    /** Never lets the depth go negative, so stray `exit()` calls cannot break the guard. */
    fun exit() {
        val depth = ignoring.get()
        if (depth > 0) ignoring.set(depth - 1)
    }

    fun <T> withGuard(block: () -> T): T {
        val depth = ignoring.get()
        ignoring.set(depth + 1)
        try {
            return block()
        } finally {
            // Restore exactly one level: nested guards keep the outer guard alive.
            ignoring.set(depth)
        }
    }
}
