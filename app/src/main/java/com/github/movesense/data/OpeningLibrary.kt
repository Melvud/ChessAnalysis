package com.github.movesense.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

object OpeningLibrary {

    private val TAG = "OpeningLibrary"

    // Key: ECO code, Value: List of variants sorted by length
    private val openings = ConcurrentHashMap<String, List<OpeningVariant>>()
    // Key: ECO code, Value: Name of the first opening encountered for this ECO (fallback)
    private val defaults = ConcurrentHashMap<String, String>()
    
    private var isInitialized = false

    data class OpeningVariant(
        val name: String,
        val moves: String // normalized moves "e4 e5 ..."
    )

    suspend fun initialize(context: Context) {
        if (isInitialized) return
        withContext(Dispatchers.IO) {
            val validFiles = listOf("a.tsv", "b.tsv", "c.tsv", "d.tsv", "e.tsv")
            try {
                val tempMap = HashMap<String, MutableList<OpeningVariant>>()

                for (fileName in validFiles) {
                    try {
                        context.assets.open("chess-openings/$fileName").use { inputStream ->
                            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                                var line = reader.readLine() // Header
                                while (line != null) {
                                    line = reader.readLine() ?: break
                                    val parts = line.split("\t")
                                    if (parts.size >= 3) {
                                        val eco = parts[0]
                                        val name = parts[1]
                                        val rawPgn = parts[2]

                                        // 1. Capture default (first one in file)
                                        if (!defaults.containsKey(eco)) {
                                            defaults[eco] = name
                                        }

                                        // 2. Normalize moves for storage
                                        val normMoves = normalizeMoves(rawPgn)
                                        
                                        val list = tempMap.getOrPut(eco) { ArrayList() }
                                        list.add(OpeningVariant(name, normMoves))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading openings file: $fileName", e)
                    }
                }

                // Sort variants by length of moves descending (longest match wins)
                for ((key, value) in tempMap) {
                    value.sortByDescending { it.moves.length }
                    openings[key] = value
                }
                
                isInitialized = true
                Log.d(TAG, "OpeningLibrary initialized with ${openings.size} ECO codes.")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize OpeningLibrary", e)
            }
        }
    }

    fun getOpeningName(eco: String?, pgn: String?): String? {
        if (!isInitialized || eco.isNullOrBlank()) return null

        // 1. Look up variants for this ECO
        val variants = openings[eco]
        if (variants.isNullOrEmpty()) return null

        // 2. If valid PGN provided, try to match specific variant
        if (!pgn.isNullOrBlank()) {
            val normalizedInput = normalizeMoves(pgn)
            // Find longest prefix match
            val match = variants.firstOrNull { variant ->
                normalizedInput.startsWith(variant.moves, ignoreCase = true)
            }
            if (match != null) {
                return match.name
            }
        }

        // 3. Fallback to default (first entry for this ECO)
        return defaults[eco]
    }

    /**
     * Normalizes PGN to "e4 e5 ..." format (removes move numbers, tags, comments).
     */
    private fun normalizeMoves(pgn: String): String {
        var processed = pgn

        // Remove tags [Tag "Val"]
        processed = processed.replace(Regex("\\[.*?\\]"), "")
        
        // Remove comments { ... }
        processed = processed.replace(Regex("\\{.*?\\}"), "")
        
        // Remove recursive variations ( ... )
        processed = processed.replace(Regex("\\(.*?\\)"), "")

        // Remove numeric tokens like "1.", "1...", "2."
        // Using regex to match digits followed by dots
        processed = processed.replace(Regex("\\d+\\.+"), "")

        // Remove result 1-0 etc
        processed = processed.replace(Regex("(1-0|0-1|1/2-1/2|\\*)"), "")

        // Normalize whitespace
        processed = processed.replace(Regex("\\s+"), " ").trim()

        return processed
    }
}
