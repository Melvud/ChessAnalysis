package com.github.movesense.utils

import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile

object CpuMonitor {
    private const val TAG = "CpuMonitor"
    private var monitorJob: Job? = null

    /**
     * Начать мониторинг загрузки CPU процессом Stockfish
     */
    fun startMonitoring(scope: CoroutineScope) {
        stopMonitoring()

        monitorJob = scope.launch(Dispatchers.IO) {
            val pid = Process.myPid()
            var lastProcessTime = 0L
            var lastSystemTime = 0L

            while (isActive) {
                try {
                    // 1. Загрузка процесса через /proc/[pid]/stat
                    val processStat = readProcessStat(pid)
                    if (processStat != null) {
                        val (utime, stime) = processStat
                        val currentProcessTime = utime + stime

                        // 2. Системное время через SystemClock
                        val currentSystemTime = android.os.SystemClock.elapsedRealtimeNanos()

                        // 3. Вычисляем использование CPU
                        if (lastProcessTime > 0 && lastSystemTime > 0) {
                            val processDelta = currentProcessTime - lastProcessTime
                            val systemDelta = (currentSystemTime - lastSystemTime) / 1_000_000 // ns -> ms

                            if (systemDelta > 0) {
                                // Процесс использует CPU в тиках (обычно 100 Hz = 10ms per tick)
                                val processUsagePercent = (processDelta * 10.0 / systemDelta) * 100.0

                                // 4. Количество активных потоков
                                val threads = countThreads(pid)

                                Log.i(TAG, "📊 Stockfish: ${String.format("%.1f", processUsagePercent)}% CPU | Threads: $threads")

                                // 5. Детальная информация о потоках (опционально)
                                if (processUsagePercent > 50) {
                                    val threadDetails = getThreadDetails(pid)
                                    if (threadDetails.isNotEmpty()) {
                                        Log.d(TAG, "🔥 Active threads: ${threadDetails.joinToString(", ")}")
                                    }
                                }
                            }
                        }

                        lastProcessTime = currentProcessTime
                        lastSystemTime = currentSystemTime
                    }

                } catch (e: Exception) {
                    Log.w(TAG, "Monitor error: ${e.message}")
                }

                delay(1000) // Каждую секунду
            }
        }
    }

    /**
     * Остановить мониторинг
     */
    fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * Чтение статистики процесса из /proc/[pid]/stat
     * Возвращает пару (utime, stime) в тиках
     */
    private fun readProcessStat(pid: Int): Pair<Long, Long>? {
        return try {
            val stat = File("/proc/$pid/stat").readText()
            val values = stat.split(" ")

            // Формат: pid (comm) state ppid pgrp ... utime stime ...
            // utime = индекс 13, stime = индекс 14 (после split по пробелам)
            val utime = values.getOrNull(13)?.toLongOrNull() ?: 0L
            val stime = values.getOrNull(14)?.toLongOrNull() ?: 0L

            utime to stime
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /proc/$pid/stat: ${e.message}")
            null
        }
    }

    /**
     * Подсчёт количества потоков процесса
     */
    private fun countThreads(pid: Int): Int {
        return try {
            File("/proc/$pid/task").listFiles()?.size ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Получить детальную информацию о потоках
     * Возвращает список имён активных потоков
     */
    private fun getThreadDetails(pid: Int): List<String> {
        return try {
            val taskDir = File("/proc/$pid/task")
            taskDir.listFiles()?.mapNotNull { threadDir ->
                try {
                    val tid = threadDir.name.toIntOrNull() ?: return@mapNotNull null
                    val commFile = File(threadDir, "comm")
                    if (commFile.exists()) {
                        val name = commFile.readText().trim()
                        "$name($tid)"
                    } else null
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * АЛЬТЕРНАТИВА: Мониторинг через /proc/[pid]/status
     * Более надёжный метод для Android 8.0+
     */
    private fun getProcessCpuUsageFromStatus(pid: Int): Double {
        return try {
            val status = File("/proc/$pid/status").readLines()

            // Ищем строку "Threads:"
            val threadsLine = status.find { it.startsWith("Threads:") }
            val threads = threadsLine?.split(":")?.getOrNull(1)?.trim()?.toIntOrNull() ?: 1

            // Примерная оценка: если потоков много, значит CPU загружен
            threads.toDouble() * 10.0 // Условная метрика
        } catch (e: Exception) {
            0.0
        }
    }
}