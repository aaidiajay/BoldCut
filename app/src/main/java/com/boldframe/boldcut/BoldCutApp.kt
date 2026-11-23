package com.boldframe.boldcut

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * App crash ho to stack trace file me save hota hai, aur agli baar app kholne par
 * screen pe dikhta hai (phone se hi screenshot bhej sako).
 */
class BoldCutApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "last_crash.txt").writeText("Thread: ${t.name}\n$sw")
            }
            prev?.uncaughtException(t, e)
        }
    }
}
