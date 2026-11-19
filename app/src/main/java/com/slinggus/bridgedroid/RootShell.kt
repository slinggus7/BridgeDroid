package com.slinggus.bridgedroid

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

object RootShell {

    data class Result(val exitCode: Int, val stdout: List<String>, val stderr: List<String>)

    fun isRootAvailable(): Boolean {
        val res = execute("id")
        return res.exitCode == 0 && res.stdout.any { it.contains("uid=0(root)") }
    }

    fun execute(vararg commands: String): Result {
        val stdout = mutableListOf<String>()
        val stderr = mutableListOf<String>()
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                for (cmd in commands) {
                    os.writeBytes(cmd + "\n")
                }
                os.writeBytes("exit\n")
                os.flush()
            }

            val readerOut = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?
            while (readerOut.readLine().also { line = it } != null) {
                line?.let { stdout.add(it) }
            }

            val readerErr = BufferedReader(InputStreamReader(process.errorStream))
            while (readerErr.readLine().also { line = it } != null) {
                line?.let { stderr.add(it) }
            }

            val exitCode = process.waitFor()
            return Result(exitCode, stdout, stderr)
        } catch (e: Exception) {
            stderr.add("Exception: ${e.message}")
            return Result(-1, stdout, stderr)
        } finally {
            process?.destroy()
        }
    }
}