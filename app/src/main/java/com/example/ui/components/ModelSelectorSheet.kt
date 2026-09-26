package com.example.ui.components

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
import androidx.compose.material.icons.outlined.Tune
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

val AvailableModels = listOf(
    ModelOption(
        id = "Quantum 3",
        displayName = "Quantum 3 (Auto Vision)",
        description = "Balanced intelligent mode with lightning-fast multimodal vision & reasoning",
        tag = "Recommended",
        category = "Vision"
    ),
    ModelOption(
        id = "meta/llama-3.3-70b-instruct",
        displayName = "Llama 3.3 70B Instruct",
        description = "Meta's flagship 70B open model with frontier-class analytical reasoning",
        tag = "Frontier",
        category = "Reasoning"
    ),
    ModelOption(
        id = "meta/llama-3.2-11b-vision-instruct",
        displayName = "Llama 3.2 11B Vision",
        description = "High-speed multimodal vision comprehension and diagram understanding via NVIDIA NIM",
        tag = "Vision",
        category = "Vision"
    ),
    ModelOption(
        id = "deepseek-ai/deepseek-r1",
        displayName = "DeepSeek R1",
        description = "State-of-the-art open reasoning model featuring deep chain-of-thought problem solving",
        tag = "Reasoning",
        category = "Reasoning"
    ),
    ModelOption(
        id = "deepseek-ai/deepseek-v3",
        displayName = "DeepSeek V3",
        description = "671B parameter Mixture-of-Experts architecture with wide factual breadth",
        tag = "MoE",
        category = "Reasoning"
    ),
    ModelOption(
        id = "qwen/qwen2.5-coder-32b-instruct",
        displayName = "Qwen 2.5 Coder 32B",
        description = "Specialized coding powerhouse for algorithms, architectural patterns & debugging",
        tag = "Coding",
        category = "Coding"
    ),
    ModelOption(
        id = "qwen/qwen2.5-72b-instruct",
        displayName = "Qwen 2.5 72B Instruct",
        description = "Flagship multilingual comprehension and STEM problem solving from Alibaba Cloud",
        tag = "Pro",
        category = "Reasoning"
    ),
    ModelOption(
        id = "nvidia/llama-3.1-nemotron-70b-instruct",
        displayName = "NVIDIA Nemotron 70B",
        description = "Custom NVIDIA aligned model tuned for peak instruction accuracy and helpfulness",
        tag = "NVIDIA",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "nvidia/nemotron-4-340b-instruct",
        displayName = "NVIDIA Nemotron-4 340B",
        description = "Enterprise-grade 340B synthetic data and heavy instruction model hosted by NVIDIA",
        tag = "NVIDIA",
        category = "NVIDIA"
    ),
    ModelOption(
        id = "mistralai/mistral-large-2-instruct",
        displayName = "Mistral Large 2",
        description = "Top-tier flagship intelligence from Mistral AI with high multilingual proficiency",
        tag = "Pro",
        category = "Reasoning"
    ),
    ModelOption(
        id = "mistralai/codestral-22b-v0.1",
        displayName = "Codestral 22B",
        description = "Mistral AI's specialized coding model for syntax, refactoring, and code completion",
        tag = "Coding",
        category = "Coding"
    ),
    ModelOption(
        id = "meta/llama-3.1-405b-instruct",
        displayName = "Llama 3.1 405B Instruct",
        description = "Colossal frontier model for complex synthesis, deep logic and domain knowledge",
        tag = "Colossal",
        category = "Reasoning"
    ),
    ModelOption(
        id = "meta/llama-3.1-70b-instruct",
        displayName = "Llama 3.1 70B Instruct",
        description = "Proven general intelligence with high instruction-following accuracy",
        tag = "Popular",
        category = "Reasoning"
    ),
    ModelOption(
        id = "google/gemma-2-27b-it",
        displayName = "Google Gemma 2 27B",
        description = "Google's capable open weights model running on accelerated NVIDIA NIM servers",
        tag = "Google",
        category = "General"
    ),
    ModelOption(
        id = "meta/llama-3.2-3b-instruct",
        displayName = "Llama 3.2 3B Instruct",
        description = "Ultra-low latency compact assistant for instant responses and drafting",
        tag = "Fast",
        category = "Fast"
    ),
    ModelOption(
        id = "microsoft/phi-3.5-mini-instruct",
        displayName = "Microsoft Phi 3.5 Mini",
        description = "Compact 3.8B model engineered for rapid reasoning and concise answers",
        tag = "Fast",
        category = "Fast"
    )
)

private val Categories = listOf("All", "Vision", "Reasoning", "Coding", "NVIDIA", "Fast")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    isOpen: Boolean,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    onOpenParametersClick: () -> Unit = {},
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val filteredModels = remember(searchQuery, selectedCategory) {
        AvailableModels.filter { model ->
            val matchesCategory = selectedCategory == "All" || model.category.equals(selectedCategory, ignoreCase = true)
            val matchesSearch = searchQuery.isBlank() ||
                    model.displayName.contains(searchQuery, ignoreCase = true) ||
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
                        text = "Free endpoints hosted on build.nvidia.com/build",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
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
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Tune", style = MaterialTheme.typography.labelMedium)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search models, architectures...", fontSize = 13.sp) },
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

            Spacer(modifier = Modifier.height(10.dp))

            // Category filter chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(Categories) { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = { Text(category, fontSize = 12.sp) },
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
                                text = "No matching models found.",
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
                                    .padding(14.dp),
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
                                                fontSize = 15.sp
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

                                    Spacer(modifier = Modifier.height(3.dp))

                                    Text(
                                        text = option.description,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.5.sp,
                                            lineHeight = 17.sp
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
