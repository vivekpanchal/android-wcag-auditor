package com.a11yauditor.app

import android.app.Application
import android.os.Process
import android.util.Log

/**
 * ponytail: the platform kills the process on any uncaught exception on the
 * main thread no matter what a handler does — that can't be prevented here.
 * What this buys us is the one thing we actually control: the crash lands in
 * logcat with a clear tag and full stack trace instead of whatever terse line
 * the platform's default handler prints, so a `adb logcat` after a silently
 * dead audit session tells you what killed it.
 */
class AuditorApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL: uncaught exception on thread ${thread.name}", throwable)
            if (platformHandler != null) {
                platformHandler.uncaughtException(thread, throwable)
            } else {
                // No platform handler to delegate to (not expected on a real
                // device — ActivityThread always installs one — but seen under
                // JVM/Robolectric test harnesses). Without this, we'd log and
                // silently return, leaving the process alive with a dead thread
                // instead of crashing cleanly so the OS can restart it.
                Process.killProcess(Process.myPid())
            }
        }
    }

    companion object {
        private const val TAG = "A11yAuditor.App"
    }
}
