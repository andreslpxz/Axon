package com.bridge.accessibility

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class SemanticMemoryManager(context: Context, private val secureSettings: SecureSettings) {
    private val db = Room.databaseBuilder(context, AppDatabase::class.java, "ai_memory_db").build()
    private val dao = db.memoryDao()

    suspend fun addMemory(content: String) {
        val apiKey = secureSettings.getHFKey() ?: return
        try {
            val embeddings = HfRetrofitClient.hfService.getEmbeddings(
                authHeader = "Bearer $apiKey",
                request = mapOf("inputs" to content)
            )
            dao.insert(Memory(content = content, embedding = embeddings.joinToString(",")))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun findRelevant(query: String, limit: Int = 3): List<String> {
        val apiKey = secureSettings.getHFKey() ?: return emptyList()
        val memories = dao.getAllSync()
        if (memories.isEmpty()) return emptyList()

        return try {
            val queryEmbedding = HfRetrofitClient.hfService.getEmbeddings(
                authHeader = "Bearer $apiKey",
                request = mapOf("inputs" to query)
            )

            memories.map { memory ->
                val memoryEmbedding = memory.embedding.split(",").map { it.toFloat() }
                val score = cosineSimilarity(queryEmbedding, memoryEmbedding)
                memory.content to score
            }
                .sortedByDescending { it.second }
                .take(limit)
                .map { it.first }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun cosineSimilarity(v1: List<Float>, v2: List<Float>): Float {
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]
            norm1 += v1[i] * v1[i]
            norm2 += v2[i] * v2[i]
        }
        return dotProduct / (sqrt(norm1) * sqrt(norm2))
    }
}
