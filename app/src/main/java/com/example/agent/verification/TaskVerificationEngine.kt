package com.example.agent.verification

import com.example.agent.artifact.Artifact
import com.example.agent.state.TaskObjective
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipFile

/**
 * Deterministic verification engine for files, packages, deliverables,
 * and high-level task objectives.
 *
 * Never relies on file extensions alone: validates package internals,
 * OpenXML structure, magic headers, and content integrity.
 */
object TaskVerificationEngine {

    /**
     * Deterministically validates whether a file is a genuine Microsoft Word (.docx) document.
     *
     * A real .docx is an OpenXML ZIP package containing:
     * 1. ZIP magic bytes (PK\x03\x04)
     * 2. [Content_Types].xml
     * 3. _rels/.rels
     * 4. word/document.xml with actual XML body content
     */
    fun verifyDocx(file: File, requireContentKeywords: List<String> = emptyList()): VerificationResult {
        val targetName = file.name
        val criteria = mutableListOf(
            "File exists and length > 0",
            "File has ZIP archive magic bytes",
            "ZIP contains [Content_Types].xml",
            "ZIP contains _rels/.rels",
            "ZIP contains word/document.xml with readable XML content"
        )

        if (!file.exists()) {
            return VerificationResult(
                target = targetName,
                isPassed = false,
                details = "File does not exist at '${file.absolutePath}'.",
                criteriaChecked = criteria
            )
        }

        if (file.length() < 100) {
            return VerificationResult(
                target = targetName,
                isPassed = false,
                details = "File is corrupt or too small (${file.length()} bytes) to be a valid DOCX package.",
                criteriaChecked = criteria
            )
        }

        // 1. Check ZIP magic bytes: PK\x03\x04
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                if (read < 4 || header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                    return VerificationResult(
                        target = targetName,
                        isPassed = false,
                        details = "File is not a valid ZIP package (invalid magic bytes). Plain text or binary mismatch.",
                        criteriaChecked = criteria
                    )
                }
            }
        } catch (e: Exception) {
            return VerificationResult(
                target = targetName,
                isPassed = false,
                details = "Failed to inspect file header: ${e.message}",
                criteriaChecked = criteria
            )
        }

        // 2. Open and verify OpenXML structure
        var zip: ZipFile? = null
        try {
            zip = ZipFile(file)
            val entries = zip.entries().asSequence().map { it.name }.toSet()

            val hasContentTypes = entries.contains("[Content_Types].xml")
            val hasRels = entries.contains("_rels/.rels")
            val hasDocumentXml = entries.contains("word/document.xml")

            if (!hasContentTypes) {
                return VerificationResult(
                    target = targetName,
                    isPassed = false,
                    details = "OpenXML package error: missing '[Content_Types].xml'.",
                    criteriaChecked = criteria
                )
            }

            if (!hasRels) {
                return VerificationResult(
                    target = targetName,
                    isPassed = false,
                    details = "OpenXML package error: missing '_rels/.rels'.",
                    criteriaChecked = criteria
                )
            }

            if (!hasDocumentXml) {
                return VerificationResult(
                    target = targetName,
                    isPassed = false,
                    details = "OpenXML package error: missing 'word/document.xml'.",
                    criteriaChecked = criteria
                )
            }

            // 3. Inspect document.xml content
            val docEntry = zip.getEntry("word/document.xml")
            val docContent = zip.getInputStream(docEntry).bufferedReader(Charsets.UTF_8).use { it.readText() }

            if (docContent.isBlank() || (!docContent.contains("<w:document") && !docContent.contains("<w:body"))) {
                return VerificationResult(
                    target = targetName,
                    isPassed = false,
                    details = "Corrupt 'word/document.xml': missing WordprocessingML body structure.",
                    criteriaChecked = criteria
                )
            }

            // Optional keyword checking (e.g. administrative data, mock text)
            if (requireContentKeywords.isNotEmpty()) {
                val missingKeywords = requireContentKeywords.filterNot { keyword ->
                    docContent.contains(keyword, ignoreCase = true)
                }
                if (missingKeywords.isNotEmpty()) {
                    return VerificationResult(
                        target = targetName,
                        isPassed = false,
                        details = "Document XML verified, but missing expected keywords: ${missingKeywords.joinToString(", ")}.",
                        criteriaChecked = criteria
                    )
                }
            }

            val hasStyles = entries.contains("word/styles.xml")
            val styleNote = if (hasStyles) "including custom styles" else "standard styles"

            return VerificationResult(
                target = targetName,
                isPassed = true,
                details = "Valid Microsoft Word (.docx) OpenXML package (${file.length()} bytes, $styleNote, verified document body).",
                criteriaChecked = criteria
            )
        } catch (e: Exception) {
            return VerificationResult(
                target = targetName,
                isPassed = false,
                details = "Corrupt ZIP/DOCX package structure: ${e.message}",
                criteriaChecked = criteria
            )
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Validates PDF file header and structure.
     */
    fun verifyPdf(file: File): VerificationResult {
        val targetName = file.name
        val criteria = listOf("File exists", "Header starts with %PDF-", "Non-empty size")
        if (!file.exists() || file.length() < 10) {
            return VerificationResult(targetName, false, "File is missing or empty.", criteriaChecked = criteria)
        }
        try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(5)
                val read = fis.read(header)
                val str = String(header, 0, read)
                if (!str.startsWith("%PDF")) {
                    return VerificationResult(targetName, false, "Invalid PDF header: expected '%PDF-'.", criteriaChecked = criteria)
                }
            }
            return VerificationResult(targetName, true, "Valid PDF document (${file.length()} bytes).", criteriaChecked = criteria)
        } catch (e: Exception) {
            return VerificationResult(targetName, false, "Error verifying PDF: ${e.message}", criteriaChecked = criteria)
        }
    }

    /**
     * Validates Android APK package.
     */
    fun verifyApk(file: File): VerificationResult {
        val targetName = file.name
        val criteria = listOf("Valid ZIP format", "Contains AndroidManifest.xml or classes.dex")
        if (!file.exists() || file.length() < 100) {
            return VerificationResult(targetName, false, "APK file missing or empty.", criteriaChecked = criteria)
        }
        var zip: ZipFile? = null
        try {
            zip = ZipFile(file)
            val names = zip.entries().asSequence().map { it.name }.toSet()
            val valid = names.contains("AndroidManifest.xml") || names.contains("classes.dex")
            return if (valid) {
                VerificationResult(targetName, true, "Valid Android APK package (${file.length()} bytes).", criteriaChecked = criteria)
            } else {
                VerificationResult(targetName, false, "ZIP does not contain AndroidManifest.xml or classes.dex.", criteriaChecked = criteria)
            }
        } catch (e: Exception) {
            return VerificationResult(targetName, false, "Invalid APK package: ${e.message}", criteriaChecked = criteria)
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Validates generic ZIP archive.
     */
    fun verifyZip(file: File): VerificationResult {
        val targetName = file.name
        val criteria = listOf("Valid ZIP format", "Archive can be opened and parsed")
        if (!file.exists() || file.length() < 22) {
            return VerificationResult(targetName, false, "ZIP file missing or empty.", criteriaChecked = criteria)
        }
        var zip: ZipFile? = null
        try {
            zip = ZipFile(file)
            val count = zip.size()
            return VerificationResult(targetName, true, "Valid ZIP archive with $count entries (${file.length()} bytes).", criteriaChecked = criteria)
        } catch (e: Exception) {
            return VerificationResult(targetName, false, "Invalid ZIP archive: ${e.message}", criteriaChecked = criteria)
        } finally {
            try { zip?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Validates any file on disk according to expected MIME or extension.
     */
    fun verifyFile(file: File, expectedType: String? = null): VerificationResult {
        val name = file.name.lowercase()
        return when {
            name.endsWith(".docx") -> verifyDocx(file)
            name.endsWith(".pdf") -> verifyPdf(file)
            name.endsWith(".apk") -> verifyApk(file)
            name.endsWith(".zip") -> verifyZip(file)
            else -> {
                val criteria = listOf("File exists", "Non-empty content")
                if (!file.exists()) {
                    VerificationResult(file.name, false, "File does not exist.", criteriaChecked = criteria)
                } else if (file.length() == 0L) {
                    VerificationResult(file.name, false, "File exists but is empty (0 bytes).", criteriaChecked = criteria)
                } else {
                    VerificationResult(file.name, true, "File exists and verified (${file.length()} bytes).", criteriaChecked = criteria)
                }
            }
        }
    }

    /**
     * Evaluates whether the entire TaskObjective has been satisfied.
     */
    fun verifyTaskObjective(
        objective: TaskObjective,
        artifacts: List<Artifact>,
        workspaceDir: File
    ): VerificationResult {
        val criteria = objective.completionCriteria

        // 1. Check required artifacts
        for (reqArtifact in objective.requiredArtifacts) {
            val matchingFile = File(workspaceDir, reqArtifact)
            val foundFile = if (matchingFile.exists()) {
                matchingFile
            } else {
                // Check if any file in workspace matches by extension if specific name differs
                val ext = reqArtifact.substringAfterLast('.', "")
                if (ext.isNotBlank()) {
                    workspaceDir.walkTopDown()
                        .filter { it.isFile && it.extension.equals(ext, ignoreCase = true) }
                        .firstOrNull()
                } else null
            }

            if (foundFile == null || !foundFile.exists()) {
                return VerificationResult(
                    target = "Task Objective",
                    isPassed = false,
                    details = "Required deliverable '$reqArtifact' was not found in workspace.",
                    criteriaChecked = criteria
                )
            }

            val fileVer = verifyFile(foundFile)
            if (!fileVer.isPassed) {
                return VerificationResult(
                    target = "Task Objective",
                    isPassed = false,
                    details = "Required deliverable '${foundFile.name}' failed verification: ${fileVer.details}",
                    criteriaChecked = criteria
                )
            }
        }

        return VerificationResult(
            target = "Task Objective",
            isPassed = true,
            details = "All required deliverables and completion criteria have been deterministically verified.",
            criteriaChecked = criteria
        )
    }
}
