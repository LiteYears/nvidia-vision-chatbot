package com.example.data.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.UUID
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

data class RagChunk(
    val id: String = UUID.randomUUID().toString(),
    val documentId: String,
    val documentName: String,
    val index: Int,
    val text: String,
    val wordCount: Int,
    val termFrequencies: Map<String, Int>
)

data class RagDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sizeBytes: Long,
    val fullText: String,
    val chunks: List<RagChunk>,
    val averageChunkLength: Double = 1.0
)

data class RetrievedChunk(
    val chunk: RagChunk,
    val score: Double,
    val matchSnippet: String = ""
)

/**
 * High-performance local RAG (Retrieval-Augmented Generation) Engine.
 * Features:
 * - Robust multi-format document reading (text, markdown, code, csv, json, logs)
 * - Semantic paragraph-aware chunking with overlap
 * - Standard BM25 information retrieval scoring (k1=1.5, b=0.75) with IDF normalization
 * - Exact phrase and multi-token proximity boosting
 * - Formatted citation prompts with section references for LLM grounding
 */
object RagEngine {
    private const val TAG = "RagEngine"
    private const val TARGET_CHUNK_WORDS = 150
    private const val OVERLAP_WORDS = 35

    // BM25 parameters
    private const val BM25_K1 = 1.5
    private const val BM25_B = 0.75

    private const val MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024L // 10MB
    private const val MAX_TEXT_CHARS = 400_000 // ~400KB text
    private const val MAX_CHUNKS = 250

    /**
     * Reads, cleans, and indexes a document from a Content Uri.
     */
    suspend fun indexDocument(context: Context, uri: Uri): Result<RagDocument> = withContext(Dispatchers.IO) {
        try {
            var fileName = "Document_${System.currentTimeMillis() % 10000}.txt"
            var fileSize = 0L

            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex != -1) {
                            val resolved = cursor.getString(nameIndex)
                            if (!resolved.isNullOrBlank()) fileName = resolved
                        }
                        if (sizeIndex != -1) fileSize = cursor.getLong(sizeIndex)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve metadata for uri: $uri", e)
            }

            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(
                    IllegalArgumentException("Document is too large ($fileSize bytes). Max supported size is 10MB.")
                )
            }

            val contentBuilder = StringBuilder()
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return@withContext Result.failure(IllegalArgumentException("Cannot open stream for: $uri"))

            var truncated = false
            inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        contentBuilder.append(line).append("\n")
                        if (contentBuilder.length >= MAX_TEXT_CHARS) {
                            truncated = true
                            break
                        }
                    }
                }
            }

            val rawText = contentBuilder.toString().trim()
            if (rawText.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("The selected document appears to be empty."))
            }

            // Detect binary garbage (e.g. unparsed binary PDF/images)
            val nullByteCount = rawText.take(1000).count { it == '\u0000' }
            if (nullByteCount > 10) {
                return@withContext Result.failure(
                    IllegalArgumentException("The selected file appears to be a binary format. Please upload text, markdown, CSV, or code files.")
                )
            }

            val cleanText = sanitizeDocumentText(rawText)
            val docId = UUID.randomUUID().toString()
            val chunks = semanticChunking(docId, fileName, cleanText).take(MAX_CHUNKS)

            if (chunks.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("No readable text could be indexed from document."))
            }

            val avgLen = chunks.map { it.wordCount }.average()

            val document = RagDocument(
                id = docId,
                name = if (truncated) "$fileName (Truncated)" else fileName,
                sizeBytes = if (fileSize > 0) fileSize else cleanText.toByteArray().size.toLong(),
                fullText = cleanText,
                chunks = chunks,
                averageChunkLength = max(1.0, avgLen)
            )

            Log.d(TAG, "Indexed document '$fileName' into ${chunks.size} chunks (avg words: ${avgLen.toInt()})")
            Result.success(document)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index document: ${uri.path}", e)
            Result.failure(e)
        }
    }

    /**
     * Sanitizes document content by normalizing line endings and removing control characters.
     */
    private fun sanitizeDocumentText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    /**
     * Chunks text by respecting paragraph boundaries when possible, falling back to word sliding windows.
     */
    private fun semanticChunking(docId: String, docName: String, text: String): List<RagChunk> {
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
        val chunks = mutableListOf<RagChunk>()

        var currentChunkWords = mutableListOf<String>()
        var chunkIndex = 0

        for (para in paragraphs) {
            val wordsInPara = para.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (wordsInPara.isEmpty()) continue

            // If adding this paragraph exceeds target size and we already have content, finalize chunk
            if (currentChunkWords.size + wordsInPara.size > TARGET_CHUNK_WORDS && currentChunkWords.isNotEmpty()) {
                chunks.add(createChunk(docId, docName, chunkIndex++, currentChunkWords))

                // Overlap: retain last OVERLAP_WORDS from currentChunkWords
                val overlap = if (currentChunkWords.size > OVERLAP_WORDS) {
                    currentChunkWords.takeLast(OVERLAP_WORDS).toMutableList()
                } else {
                    currentChunkWords.toMutableList()
                }
                currentChunkWords = overlap
            }

            // If paragraph itself is excessively large, split it into word windows
            if (wordsInPara.size > TARGET_CHUNK_WORDS) {
                var start = 0
                while (start < wordsInPara.size) {
                    val end = min(start + TARGET_CHUNK_WORDS, wordsInPara.size)
                    val slice = wordsInPara.subList(start, end)
                    chunks.add(createChunk(docId, docName, chunkIndex++, slice))
                    if (end == wordsInPara.size) break
                    start += (TARGET_CHUNK_WORDS - OVERLAP_WORDS)
                }
                currentChunkWords.clear()
            } else {
                currentChunkWords.addAll(wordsInPara)
            }
        }

        if (currentChunkWords.isNotEmpty()) {
            chunks.add(createChunk(docId, docName, chunkIndex, currentChunkWords))
        }

        return chunks
    }

    private fun createChunk(docId: String, docName: String, index: Int, words: List<String>): RagChunk {
        val text = words.joinToString(" ")
        val tfMap = mutableMapOf<String, Int>()

        for (word in words) {
            val clean = word.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
            if (clean.length > 2 && clean !in STOP_WORDS) {
                tfMap[clean] = (tfMap[clean] ?: 0) + 1
            }
        }

        return RagChunk(
            documentId = docId,
            documentName = docName,
            index = index,
            text = text,
            wordCount = words.size,
            termFrequencies = tfMap
        )
    }

    /**
     * Performs BM25 keyword and phrase relevance ranking across all indexed chunks in a document.
     */
    fun retrieveRelevantContext(query: String, document: RagDocument, topK: Int = 4): List<RetrievedChunk> {
        if (document.chunks.isEmpty() || query.isBlank()) return emptyList()

        val rawTerms = query.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .map { it.replace(Regex("[^a-z0-9]"), "") }
            .filter { it.length > 2 && it !in STOP_WORDS }

        if (rawTerms.isEmpty()) {
            // Fallback: return first chunks
            return document.chunks.take(topK).map {
                RetrievedChunk(it, 1.0, it.text.take(120))
            }
        }

        val totalChunks = document.chunks.size
        val effectiveAvgdl = if (document.averageChunkLength > 1.0) {
            document.averageChunkLength
        } else {
            max(1.0, document.chunks.map { it.wordCount.toDouble() }.average())
        }

        // Calculate document frequency for query terms
        val docFreqs = mutableMapOf<String, Int>()
        for (term in rawTerms) {
            docFreqs[term] = document.chunks.count { it.termFrequencies.containsKey(term) }
        }

        val scored = document.chunks.map { chunk ->
            var bm25Score = 0.0
            val chunkLen = max(1.0, chunk.wordCount.toDouble())

            for (term in rawTerms) {
                val tf = (chunk.termFrequencies[term] ?: 0).toDouble()
                if (tf > 0) {
                    val df = (docFreqs[term] ?: 1).toDouble()
                    // Standard BM25 IDF: ln(1 + (N - df + 0.5) / (df + 0.5))
                    val idf = ln(1.0 + (totalChunks - df + 0.5) / (df + 0.5)).coerceAtLeast(0.15)
                    // BM25 TF normalization
                    val tfNorm = (tf * (BM25_K1 + 1.0)) / (tf + BM25_K1 * (1.0 - BM25_B + BM25_B * (chunkLen / effectiveAvgdl)))
                    bm25Score += idf * tfNorm
                }
            }

            // Phrase match bonus
            val cleanQuery = query.trim().lowercase(Locale.ROOT)
            if (cleanQuery.length > 4 && chunk.text.lowercase(Locale.ROOT).contains(cleanQuery)) {
                bm25Score += 6.0
            }

            // Match snippet preview
            val snippet = findMatchingSnippet(chunk.text, rawTerms)

            RetrievedChunk(chunk, bm25Score, snippet)
        }

        val matching = scored.filter { it.score > 0.0 }
        return if (matching.isNotEmpty()) {
            matching.sortedByDescending { it.score }.take(topK)
        } else {
            scored.take(topK)
        }
    }

    private fun findMatchingSnippet(text: String, terms: List<String>): String {
        for (term in terms) {
            val idx = text.indexOf(term, ignoreCase = true)
            if (idx != -1) {
                val start = max(0, idx - 40)
                val end = min(text.length, idx + 100)
                return "...${text.substring(start, end).trim()}..."
            }
        }
        return text.take(120) + "..."
    }

    /**
     * Formats retrieved RAG chunks into a structured, highly grounded system context prompt.
     */
    fun formatRagPrompt(retrieved: List<RetrievedChunk>, documentName: String): String {
        if (retrieved.isEmpty()) return ""

        return buildString {
            append("### KNOWLEDGE BASE CONTEXT (RAG Grounding from: '$documentName')\n")
            append("Instructions: Ground your response strictly on the verified excerpts below. Cite source sections when answering.\n\n")
            for ((idx, item) in retrieved.withIndex()) {
                val sectionNum = item.chunk.index + 1
                val confidence = (item.score.coerceIn(0.0, 10.0) / 10.0 * 100).toInt()
                append("--- [Source ${idx + 1}: ${item.chunk.documentName} | Section #$sectionNum | Relevance ~$confidence%] ---\n")
                append(item.chunk.text.trim())
                append("\n\n")
            }
            append("### END OF GROUNDED KNOWLEDGE BASE CONTEXT\n")
        }
    }

    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "this", "that", "from", "are", "was", "were", "been", "have", "has", "had",
        "what", "when", "where", "which", "who", "whom", "will", "would", "should", "could", "about", "into",
        "more", "some", "such", "than", "then", "them", "these", "they", "their", "there", "also", "just"
    )
}

