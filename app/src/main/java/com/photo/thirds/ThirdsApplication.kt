package com.photo.thirds

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThirdsApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        com.cySdkyc.clx.Helper.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    /**
     * 미처리 예외(OutOfMemoryError 등 Error 포함)를 filesDir/crash_시간.txt 에 저장한 뒤
     * 기존 기본 핸들러로 위임(시스템 크래시 다이얼로그/기록은 그대로 유지).
     * 착륙 후 확인: adb ... shell run-as com.photo.thirds cat files/crash_*.txt
     * ※ 네이티브 크래시(SIGSEGV 등)는 JVM 핸들러로 못 잡음 → 그 경우는 crash 버퍼/텀스톤 필요.
     */
    private fun installCrashLogger() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val sw = StringWriter()
                PrintWriter(sw).use { throwable.printStackTrace(it) }
                val text = buildString {
                    append("time=").append(ts).append('\n')
                    append("thread=").append(thread.name).append('\n')
                    append("message=").append(throwable.toString()).append("\n\n")
                    append(sw.toString())
                }
                File(filesDir, "crash_$ts.txt").writeText(text)
                Log.e("ThirdsApp", "CRASH saved to files/crash_$ts.txt", throwable)
            } catch (e: Throwable) {
                Log.e("ThirdsApp", "crash logger failed: $e")
            } finally {
                // 기존 핸들러로 넘겨 정상 종료 흐름(시스템 기록) 유지.
                prev?.uncaughtException(thread, throwable)
            }
        }
    }
}
