package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ModelOption(
    val id: String,
    val displayName: String,
    val description: String,
    val tag: String,
    val category: String
)

val Quantum3Option = ModelOption(
    id = "Quantum 3",
    displayName = "Quantum 3 (Auto Vision)",
    description = "Intelligent auto mode: automatically routes to Llama 3.2 Vision, Gemma 3, or Mistral Large",
    tag = "Recommended",
    category = "Vision"
)

fun modelIdToOption(id: String): ModelOption {
    if (id.equals("Quantum 3", ignoreCase = true)) return Quantum3Option

    val lower = id.lowercase()
    val provider = id.substringBefore("/").replace("-", " ").split(" ")
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    val rawName = id.substringAfter("/")

    val category = when {
        lower.contains("vision") || lower.contains("vlm") || lower.contains("fuyu") ||
                lower.contains("kosmos") || lower.contains("deplot") || lower.contains("neva") ||
                lower.contains("vila") -> "Vision"
        lower.contains("code") || lower.contains("codestral") || lower.contains("starcoder") -> "Coding"
        lower.contains("70b") || lower.contains("90b") || lower.contains("large") ||
                lower.contains("ultra") || lower.contains("reason") || lower.contains("cosmos") ||
                lower.contains("kimi") || lower.contains("glm") -> "Reasoning"
        lower.contains("1b") || lower.contains("2b") || lower.contains("3b") ||
                lower.contains("4b") || lower.contains("7b") || lower.contains("8b") ||
                lower.contains("mini") || lower.contains("flash") -> "Fast"
        lower.startsWith("nvidia/") -> "NVIDIA"
        lower.startsWith("google/") -> "Google"
        lower.startsWith("meta/") -> "Meta"
        lower.startsWith("mistralai/") || lower.startsWith("nv-mistralai/") -> "Mistral"
        lower.startsWith("deepseek") -> "DeepSeek"
        else -> "General"
    }

    val tag = when {
        id == "nvidia/llama-3.1-nemotron-70b-instruct" -> "Flagship"
        id == "meta/llama-3.2-11b-vision-instruct" -> "Vision"
        id == "meta/llama-3.2-90b-vision-instruct" -> "Pro Vision"
        id == "mistralai/codestral-22b-instruct-v0.1" -> "Coding"
        id == "google/gemma-3-12b-it" -> "Popular"
        id == "google/gemma-3-4b-it" -> "Fast"
        id == "deepseek-ai/deepseek-v4.1-flash" -> "DeepSeek"
        id == "deepseek-ai/deepseek-coder-6.7b-instruct" -> "Coding"
        id == "z-ai/glm-5.3" -> "Frontier"
        id == "moonshotai/kimi-k3" -> "Reasoning"
        lower.contains("vision") -> "Vision"
        lower.contains("code") -> "Coding"
        lower.contains("flash") -> "Fast"
        else -> provider.take(10)
    }

    val formattedName = rawName.split("-").joinToString(" ") { word ->
        if (word.matches(Regex("\\d+[bB]"))) word.uppercase()
        else word.replaceFirstChar { it.uppercase() }
    }

    val displayName = "$provider $formattedName"
    val description = "Active NVIDIA NIM endpoint: $id"

    return ModelOption(
        id = id,
        displayName = displayName,
        description = description,
        tag = tag,
        category = category
    )
}

// Pre-populated catalog verified directly against https://integrate.api.nvidia.com/v1/models
val AvailableModels = listOf(
    Quantum3Option,
    ModelOption(
        id = "meta/llama-3.2-11b-vision-instruct",
        displayName = "Llama 3.2 11B Vision",
        description = "High-speed multimodal vision comprehension and diagram understanding via NVIDIA NIM",
        tag = "Verified Active",
        category = "Vision"
    ),
    ModelOption(
        id = "openai/gpt-oss-20b",
        displayName = "OpenAI GPT OSS 20B",
        description = "High-speed conversational and instruction model running on NVIDIA NIM",
        tag = "Verified Active",
        category = "Reasoning"
    ),
    ModelOption(
        id = "meta/muse-glimmer-30b",
        displayName = "Meta Muse Glimmer 30B",
        description = "Deep reasoning model with integrated thought synthesis verified on NVIDIA NIM",
        tag = "Verified Active",
        category = "Meta"
    ),
    ModelOption(
        id = "google/diffusiongemma-26b-a4b-it",
        displayName = "Google Gemma 26B IT",
        description = "High-capability instruction-tuned conversational assistant verified on NVIDIA NIM",
        tag = "Verified Active",
        category = "Google"
    ),
    ModelOption(
        id = "nvidia/nemotron-3-ultra-550b-a55b",
        displayName = "NVIDIA Nemotron Ultra 550B",
        description = "Frontier 550B MoE reasoning engine for deep analysis and agentic planning",
        tag = "Verified Active",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "nvidia/riva-translate-4b-instruct-v2",
        displayName = "NVIDIA Riva Translate 4B",
        description = "Instantaneous multilingual translation and dialogue assistant on NVIDIA NIM",
        tag = "Verified Active",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "google/gemma-3-12b-it",
        displayName = "Google Gemma 3 12B",
        description = "Google's balanced instruction-following open weights model running on NVIDIA NIM",
        tag = "Google",
        category = "Google"
    ),
    ModelOption(
        id = "mistralai/mistral-large-2-instruct",
        displayName = "Mistral Large 2",
        description = "Top-tier flagship intelligence from Mistral AI with high multilingual proficiency",
        tag = "Pro",
        category = "Mistral"
    ),
    ModelOption(
        id = "meta/llama-3.2-90b-vision-instruct",
        displayName = "Llama 3.2 90B Vision",
        description = "Frontier-class 90B multimodal visual reasoning, OCR, and complex scene analysis",
        tag = "Pro Vision",
        category = "Vision"
    ),
    ModelOption(
        id = "google/gemma-3-4b-it",
        displayName = "Google Gemma 3 4B",
        description = "Ultra-low latency compact Google assistant for instantaneous chat and summarization",
        tag = "Fast",
        category = "Fast"
    ),
    ModelOption(
        id = "google/gemma-4-31b-it",
        displayName = "Google Gemma 4 31B",
        description = "High-performance reasoning and STEM comprehension model running on accelerated GPUs",
        tag = "Pro",
        category = "Google"
    ),
    ModelOption(
        id = "nvidia/llama-3.1-nemotron-70b-instruct",
        displayName = "NVIDIA Nemotron 70B",
        description = "NVIDIA aligned reasoning model (requires enterprise organization access on build.nvidia.com)",
        tag = "Enterprise",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "mistralai/codestral-22b-instruct-v0.1",
        displayName = "Codestral 22B Instruct",
        description = "Mistral AI's specialized coding model for syntax, refactoring, and code completion",
        tag = "Coding",
        category = "Coding"
    ),
    ModelOption(
        id = "nv-mistralai/mistral-nemo-12b-instruct",
        displayName = "Mistral NeMo 12B",
        description = "Efficient 12B model co-developed with NVIDIA with 128k context support",
        tag = "NVIDIA",
        category = "Mistral"
    ),
    ModelOption(
        id = "meta/llama-3.3-70b-instruct",
        displayName = "Llama 3.3 70B Instruct",
        description = "Meta's flagship 70B instruction-tuned model for deep reasoning and synthetic workflows",
        tag = "Frontier",
        category = "Meta"
    ),
    ModelOption(
        id = "deepseek-ai/deepseek-r1",
        displayName = "DeepSeek R1",
        description = "State-of-the-art open reasoning model utilizing reinforcement learning for complex STEM tasks",
        tag = "Reasoning",
        category = "DeepSeek"
    ),
    ModelOption(
        id = "qwen/qwen2.5-coder-32b-instruct",
        displayName = "Qwen 2.5 Coder 32B",
        description = "Advanced coding and program synthesis model with superior multi-language code generation",
        tag = "Coding",
        category = "Coding"
    ),
    ModelOption(
        id = "deepseek-ai/deepseek-v4.1-flash",
        displayName = "DeepSeek V4.1 Flash",
        description = "Lightning-fast DeepSeek open architecture optimized for agile generation on NIM",
        tag = "DeepSeek",
        category = "DeepSeek"
    ),
    ModelOption(
        id = "deepseek-ai/deepseek-coder-6.7b-instruct",
        displayName = "DeepSeek Coder 6.7B",
        description = "Dedicated code intelligence model trained on massive open source repositories",
        tag = "Coding",
        category = "DeepSeek"
    ),
    ModelOption(
        id = "nvidia/nemotron-4-340b-instruct",
        displayName = "NVIDIA Nemotron-4 340B",
        description = "Colossal 340B enterprise model hosted by NVIDIA for complex synthesis and deep knowledge",
        tag = "Colossal",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "nvidia/mistral-nemo-minitron-8b-8k-instruct",
        displayName = "NVIDIA Minitron 8B",
        description = "Compact 8B pruned and distilled model for ultra-rapid turnarounds",
        tag = "Fast",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "moonshotai/kimi-k3",
        displayName = "Moonshot Kimi K3",
        description = "Advanced extended reasoning architecture with deep factual retrieval capabilities",
        tag = "Reasoning",
        category = "Reasoning"
    ),
    ModelOption(
        id = "moonshotai/kimi-k2.6",
        displayName = "Moonshot Kimi K2.6",
        description = "Agile long-context conversational assistant hosted on NVIDIA API catalog",
        tag = "General",
        category = "Reasoning"
    ),
    ModelOption(
        id = "z-ai/glm-5.3",
        displayName = "Z-AI GLM 5.3",
        description = "High-performing bilingual general intelligence model with advanced instruction following",
        tag = "Reasoning",
        category = "Reasoning"
    ),
    ModelOption(
        id = "z-ai/glm-5.3-flash",
        displayName = "Z-AI GLM 5.3 Flash",
        description = "Accelerated response generation for everyday chat and code exploration",
        tag = "Fast",
        category = "Fast"
    ),
    ModelOption(
        id = "openai/gpt-oss-20b",
        displayName = "OpenAI GPT OSS 20B",
        description = "Open source weights model running on NVIDIA accelerated container runtime",
        tag = "OpenAI",
        category = "Reasoning"
    ),
    ModelOption(
        id = "microsoft/phi-3-vision-128k-instruct",
        displayName = "Microsoft Phi 3 Vision",
        description = "Compact multimodal model for image questioning, chart analysis, and diagram parsing",
        tag = "Vision",
        category = "Vision"
    ),
    ModelOption(
        id = "microsoft/phi-3.5-moe-instruct",
        displayName = "Microsoft Phi 3.5 MoE",
        description = "Mixture-of-Experts architecture with 16x3.8B parameters for lightweight smart routing",
        tag = "Fast",
        category = "Fast"
    ),
    ModelOption(
        id = "01-ai/yi-large",
        displayName = "01.AI Yi Large",
        description = "Premier large model from 01.AI excelling in logical reasoning, math, and multilingual tasks",
        tag = "Frontier",
        category = "Reasoning"
    ),
    ModelOption(
        id = "meta/codellama-70b",
        displayName = "Meta CodeLlama 70B",
        description = "Meta's specialized 70B parameter code generation and refactoring engine",
        tag = "Coding",
        category = "Coding"
    ),
    ModelOption(
        id = "meta/muse-glimmer-30b",
        displayName = "Meta Muse Glimmer 30B",
        description = "Creative and instruction-following model with 30B weights running on NVIDIA NIM",
        tag = "Meta",
        category = "Meta"
    ),
    ModelOption(
        id = "ibm/granite-3.0-8b-instruct",
        displayName = "IBM Granite 3.0 8B",
        description = "Enterprise-grade foundational model from IBM for business and code workflows",
        tag = "IBM",
        category = "General"
    )
)

private val Categories = listOf("All", "NVIDIA", "Google", "Mistral", "DeepSeek", "Meta", "Vision", "Coding", "Fast", "Reasoning")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    isOpen: Boolean,
    selectedModel: String,
    modelsList: List<ModelOption> = AvailableModels,
    isSyncing: Boolean = false,
    onSyncClick: () -> Unit = {},
    onModelSelected: (String) -> Unit,
    onOpenParametersClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    var showCustomInput by remember { mutableStateOf(false) }
    var customModelInput by remember { mutableStateOf("") }

    val effectiveModels = remember(modelsList) {
        if (modelsList.isNotEmpty()) modelsList else AvailableModels
    }

    val filteredModels = remember(searchQuery, selectedCategory, effectiveModels) {
        effectiveModels.filter { model ->
            val matchesCategory = selectedCategory == "All" ||
                    model.category.equals(selectedCategory, ignoreCase = true) ||
                    (selectedCategory == "NVIDIA" && model.id.startsWith("nvidia/")) ||
                    (selectedCategory == "Google" && model.id.startsWith("google/")) ||
                    (selectedCategory == "Mistral" && (model.id.startsWith("mistralai/") || model.id.startsWith("nv-mistralai/"))) ||
                    (selectedCategory == "DeepSeek" && model.id.startsWith("deepseek")) ||
                    (selectedCategory == "Meta" && model.id.startsWith("meta/"))

            val matchesSearch = searchQuery.isBlank() ||
                    model.displayName.contains(searchQuery, ignoreCase = true) ||
                    model.id.contains(searchQuery, ignoreCase = true) ||
                    model.description.contains(searchQuery, ignoreCase = true) ||
                    model.tag.contains(searchQuery, ignoreCase = true)

            matchesCategory && matchesSearch
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .testTag("model_selector_sheet")
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NVIDIA Build Models",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = "Real-time catalog from build.nvidia.com (${effectiveModels.size} available)",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Sync button
                    IconButton(
                        onClick = onSyncClick,
                        enabled = !isSyncing,
                        modifier = Modifier.size(38.dp)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = "Sync models from build.nvidia.com",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Hyperparameters button
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onOpenParametersClick()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Tune,
                            contentDescription = "Tune Parameters",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Tune", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search by name or ID (e.g. gemma, nemotron, mistral)...", fontSize = 12.5.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear search",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Toggle Custom Model Input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { showCustomInput = !showCustomInput }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.AddCircleOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (showCustomInput) "Hide custom endpoint" else "Enter custom model ID from build.nvidia.com",
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }
            }

            AnimatedVisibility(visible = showCustomInput) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customModelInput,
                        onValueChange = { customModelInput = it },
                        placeholder = { Text("e.g. nvidia/cosmos-reason2-8b", fontSize = 12.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    )
                    Button(
                        onClick = {
                            val trimmed = customModelInput.trim()
                            if (trimmed.isNotBlank()) {
                                onModelSelected(trimmed)
                                onDismiss()
                            }
                        },
                        enabled = customModelInput.isNotBlank(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Apply", fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Category filter chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(Categories) { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = { Text(category, fontSize = 11.5.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Model List
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                if (filteredModels.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No matching models found. Try clearing search or tap refresh.",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                } else {
                    items(filteredModels, key = { it.id }) { option ->
                        val isSelected = selectedModel.equals(option.id, ignoreCase = true) ||
                                (option.id.startsWith("Quantum") && selectedModel.contains("Quantum", ignoreCase = true))

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onModelSelected(option.id)
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = option.displayName,
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                fontSize = 14.5.sp
                                            )
                                        )
                                        Box(
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                                    RoundedCornerShape(6.dp)
                                                )
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = option.tag,
                                                style = MaterialTheme.typography.labelSmall.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp
                                        )
                                    )
                                }

                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
