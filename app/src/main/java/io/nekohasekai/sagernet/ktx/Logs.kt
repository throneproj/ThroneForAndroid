package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.database.DataStore
import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // 级别语义与 ConfigBuilder 的 sing-box log.level 映射一致：
    // 0=panic 1=warn 2=info 3=debug 4=trace。
    // 本通道（Kotlin -> JNI nekoLogPrintln -> Go std log）官方不过滤，
    // 必须在源头按 DataStore.logLevel 门控，否则 warn 档也会冒出 debug 日志。
    // 读取失败（如 DataStore 未就绪）时放行，避免吞掉关键日志。
    private fun enabled(required: Int): Boolean {
        return runCatching { DataStore.logLevel >= required }.getOrDefault(true)
    }

    // JNI 通道在 JVM 单元测试环境不可用，输出失败时静默忽略，不影响业务流程
    private fun printLog(line: String) {
        runCatching { Libcore.nekoLogPrintln(line) }
    }

    fun d(message: String) {
        if (!enabled(3)) return
        printLog("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        if (!enabled(3)) return
        printLog("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        if (!enabled(2)) return
        printLog("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        if (!enabled(2)) return
        printLog("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        if (!enabled(1)) return
        printLog("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        if (!enabled(1)) return
        printLog("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        if (!enabled(1)) return
        printLog("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        printLog("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        printLog("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        printLog("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}