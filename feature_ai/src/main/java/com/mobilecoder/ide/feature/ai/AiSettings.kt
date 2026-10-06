package com.mobilecoder.ide.feature.ai

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobilecoder.ide.core.common.ui.AppAlertDialog
import kotlinx.coroutines.launch

/**
 * AI 接口设置（阶段4）：多接口列表 + 单接口编辑（每接口多模型）+ 连通性测试。
 *
 * 视图为页内三级结构：
 *  - LIST：接口卡片（当前模型 chips / 启用开关 / 测试 / 编辑 / 删除）+ 预设快捷添加 + 写入确认开关
 *  - EDIT：名称 / Base URL / Key / 模型列表（逐行增删、独立启用）
 * 所有修改先落在本地 working 副本，点「保存」统一回写 [onSave]（含失效冷却清除）。
 */
@Composable
fun ProviderSettingsView(
    config: AiProvidersConfig,
    confirmWrites: Boolean,
    systemPrompt: String,
    onBack: () -> Unit,
    onSave: (AiProvidersConfig) -> Unit,
    onConfirmWritesChange: (Boolean) -> Unit,
    onSaveSystemPrompt: (String) -> Unit,
    onTest: suspend (AiEndpoint) -> Result<String>,
    modifier: Modifier = Modifier,
) {
    // 本地工作副本：仅在来自控制器的配置真正变化时重置（保存后回写触发）
    var working by remember { mutableStateOf(config) }
    LaunchedEffect(config) { working = config }
    var editingId by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val testResults = remember { mutableStateMapOf<String, String>() }
    val testingIds = remember { mutableStateMapOf<String, Boolean>() }
    var pendingDeleteId by rememberSaveable { mutableStateOf("") }

    // 系统提示词：本地编辑副本（加载完成前不覆盖用户已输入内容）
    var promptText by rememberSaveable { mutableStateOf("") }
    var promptDirty by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(systemPrompt) {
        if (!promptDirty) promptText = systemPrompt
    }

    fun commit(next: AiProvidersConfig) {
        working = next
        onSave(next)
    }

    if (editingId.isNotBlank()) {
        val provider = working.providers.firstOrNull { it.id == editingId }
        if (provider == null) {
            editingId = ""
        } else {
            ProviderEditView(
                provider = provider,
                isNew = provider.name.isBlank() && provider.baseUrl.isBlank(),
                onBack = { editingId = "" },
                onSave = { updated ->
                    val providers = working.providers.map { if (it.id == updated.id) updated else it }
                    commit(
                        working.copy(
                            providers = providers,
                            activeProviderId = working.activeProviderId.ifBlank { updated.id },
                        ),
                    )
                    editingId = ""
                },
                modifier = modifier,
            )
            return
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BackRow(title = "接口设置", onBack = onBack)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------- 系统提示词（默认提示词可在此修改） ----------
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "系统提示词",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TextButton(
                                onClick = {
                                    promptText = AiController.DEFAULT_SYSTEM_PROMPT
                                    promptDirty = true
                                },
                            ) { Text("填入默认") }
                        }
                        Text(
                            text = "描述本项目与工具使用规则，发送前作为 system 消息。" +
                                "{project}=项目根目录绝对路径，{projectName}=项目名；清空并保存则用内置默认。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        OutlinedTextField(
                            value = promptText,
                            onValueChange = {
                                promptText = it
                                promptDirty = true
                            },
                            placeholder = { Text("留空使用内置默认提示词", style = MaterialTheme.typography.bodySmall) },
                            minLines = 4,
                            maxLines = 10,
                            textStyle = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(
                                onClick = {
                                    promptText = ""
                                    promptDirty = false
                                    onSaveSystemPrompt("")
                                },
                            ) { Text("恢复默认") }
                            TextButton(
                                enabled = promptDirty,
                                onClick = {
                                    onSaveSystemPrompt(promptText.trim())
                                    promptDirty = false
                                },
                            ) { Text("保存") }
                        }
                    }
                }
            }

            // ---------- 预设快捷添加 ----------
            item {
                Text(
                    text = "快速添加接口",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiProviderStore.presets().forEach { preset ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val provider = AiProvider(
                                    id = AiProviderStore.newId(),
                                    name = preset.name,
                                    baseUrl = preset.baseUrl,
                                    models = preset.models.map { AiModel(it) },
                                    activeModelId = preset.models.firstOrNull().orEmpty(),
                                )
                                commit(
                                    working.copy(
                                        providers = working.providers + provider,
                                        activeProviderId = working.activeProviderId
                                            .ifBlank { provider.id },
                                    ),
                                )
                                editingId = provider.id
                            },
                            label = { Text(preset.name) },
                        )
                    }
                }
            }

            // ---------- 接口卡片 ----------
            if (working.providers.isEmpty()) {
                item {
                    Text(
                        text = "还没有接口。点上方预设快速添加，或自行填写任意 OpenAI 兼容服务；" +
                            "可以配置多个接口，额度用尽时自动切换。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
            items(working.providers.size) { index ->
                ProviderCard(
                    provider = working.providers[index],
                    isActive = working.providers[index].id == working.activeProviderId ||
                        (working.activeProviderId.isBlank() && index == 0),
                    testResult = testResults[working.providers[index].id],
                    testing = testingIds[working.providers[index].id] == true,
                    onSetActive = {
                        commit(working.copy(activeProviderId = working.providers[index].id))
                    },
                    onToggleEnabled = { enabled ->
                        val providers = working.providers.toMutableList().also {
                            it[index] = it[index].copy(enabled = enabled)
                        }
                        commit(working.copy(providers = providers))
                    },
                    onSelectModel = { modelId ->
                        val providers = working.providers.toMutableList().also {
                            it[index] = it[index].copy(activeModelId = modelId)
                        }
                        commit(
                            working.copy(
                                providers = providers,
                                activeProviderId = working.providers[index].id,
                            ),
                        )
                    },
                    onEdit = { editingId = working.providers[index].id },
                    onDelete = { pendingDeleteId = working.providers[index].id },
                    onTest = {
                        val p = working.providers[index]
                        val modelId = p.activeModel?.id
                        if (modelId != null) {
                            testingIds[p.id] = true
                            scope.launch {
                                val result = onTest(AiEndpoint(p, modelId))
                                testResults[p.id] = result.fold(
                                    onSuccess = { "✓ $it（${p.name} / $modelId）" },
                                    onFailure = { "✗ ${it.message ?: "测试失败"}" },
                                )
                                testingIds.remove(p.id)
                            }
                        }
                    },
                )
            }

            // ---------- 添加按钮 ----------
            item {
                OutlinedButton(
                    onClick = {
                        val provider = AiProvider(
                            id = AiProviderStore.newId(),
                            name = "",
                            baseUrl = "",
                        )
                        commit(working.copy(providers = working.providers + provider))
                        editingId = provider.id
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.size(6.dp))
                    Text("添加接口")
                }
            }

            // ---------- 安全开关 ----------
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "写入前逐轮确认",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "开启后 AI 每轮改文件前弹窗确认（删除操作始终确认）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = confirmWrites, onCheckedChange = onConfirmWritesChange)
                    }
                }
            }

            item {
                Text(
                    text = "提示：额度/Key 异常的接口会自动冷却 10 分钟并切换到下一个可用的接口与模型，" +
                        "对话上下文不中断。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (pendingDeleteId.isNotBlank()) {
        AppAlertDialog(
            title = "删除该接口？",
            message = "仅删除本应用内的接口配置，不影响远端额度。可随时重新添加。",
            confirmLabel = "删除",
            destructive = true,
            onConfirm = {
                val id = pendingDeleteId
                pendingDeleteId = ""
                commit(
                    working.copy(
                        providers = working.providers.filterNot { it.id == id },
                        activeProviderId = working.activeProviderId.takeIf { it != id } ?: "",
                    ),
                )
            },
            onDismiss = { pendingDeleteId = "" },
        )
    }
}

// ---------------------------------------------------------------------------
// 接口卡片
// ---------------------------------------------------------------------------

@Composable
private fun ProviderCard(
    provider: AiProvider,
    isActive: Boolean,
    testResult: String?,
    testing: Boolean,
    onSetActive: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = provider.name.ifBlank { "未命名接口" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isActive) {
                            Spacer(modifier = Modifier.size(6.dp))
                            Text(
                                text = "当前",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = provider.baseUrl.ifBlank { "未填写地址" },
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Switch(
                    checked = provider.enabled,
                    onCheckedChange = { onToggleEnabled(it) },
                )
            }

            // 模型 chips：点选 = 设为该接口当前模型（并切为当前接口）
            if (provider.models.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    provider.models.forEach { model ->
                        val selected = model.id == provider.activeModelId && isActive
                        FilterChip(
                            selected = selected,
                            onClick = { if (!selected) onSelectModel(model.id) },
                            enabled = model.enabled,
                            label = {
                                Text(
                                    text = model.id,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            },
                            trailingIcon = if (selected) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            } else {
                Text(
                    text = "未配置模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            // 测试结果
            testResult?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.startsWith("✓")) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("编辑")
                }
                TextButton(onClick = onTest, enabled = !testing && provider.looksReady) {
                    if (testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                    Spacer(modifier = Modifier.size(4.dp))
                    Text("测试")
                }
                if (!isActive) {
                    TextButton(onClick = onSetActive) { Text("设为当前") }
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "删除接口",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 编辑视图
// ---------------------------------------------------------------------------

@Composable
private fun ProviderEditView(
    provider: AiProvider,
    isNew: Boolean,
    onBack: () -> Unit,
    onSave: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by rememberSaveable(provider.id) { mutableStateOf(provider.name) }
    var baseUrl by rememberSaveable(provider.id) { mutableStateOf(provider.baseUrl) }
    var apiKey by rememberSaveable(provider.id) { mutableStateOf(provider.apiKey) }
    // 模型列表用运行时副本（增删行）
    var models by remember(provider.id) { mutableStateOf(provider.models) }
    var activeModelId by rememberSaveable(provider.id) { mutableStateOf(provider.activeModelId) }

    fun valid(): Boolean {
        if (baseUrl.isBlank()) return false
        if (models.none { it.id.isNotBlank() }) return false
        return true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        BackRow(title = if (isNew) "添加接口" else "编辑接口", onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称（如 DeepSeek）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL（OpenAI 兼容，含 /v1）") },
                    placeholder = { Text("https://api.deepseek.com/v1") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key（本地加密存储，Ollama 可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "模型（${models.size} 个，可分别启用）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { models = models + AiModel("") },
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
                        Text("添加模型")
                    }
                }
            }

            items(models.size) { index ->
                ModelRow(
                    model = models[index],
                    isActive = models[index].id == activeModelId,
                    onChange = { text ->
                        val oldId = models[index].id
                        models = models.toMutableList().also { it[index] = it[index].copy(id = text) }
                        if (oldId == activeModelId) activeModelId = text
                    },
                    onToggleEnabled = { enabled ->
                        models = models.toMutableList().also { it[index] = it[index].copy(enabled = enabled) }
                    },
                    onSetActive = { activeModelId = models[index].id },
                    onDelete = {
                        val oldId = models[index].id
                        models = models.toMutableList().also { it.removeAt(index) }
                        if (activeModelId == oldId) activeModelId = models.firstOrNull()?.id ?: ""
                    },
                )
            }

            if (models.isEmpty()) {
                item {
                    Text(
                        text = "至少添加一个模型名（如 deepseek-chat），同一接口可配置多个模型参与自动轮换。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onBack) { Text("取消") }
                    TextButton(
                        onClick = {
                            val cleaned = models.filter { it.id.isNotBlank() }
                            val active = cleaned.firstOrNull { it.id == activeModelId }?.id
                                ?: cleaned.firstOrNull()?.id.orEmpty()
                            onSave(
                                provider.copy(
                                    name = name.trim().ifBlank { "未命名接口" },
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    models = cleaned,
                                    activeModelId = active,
                                    enabled = true,
                                ),
                            )
                        },
                        enabled = valid(),
                    ) { Text("保存") }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    model: AiModel,
    isActive: Boolean,
    onChange: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isActive) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = model.id,
                onValueChange = onChange,
                placeholder = { Text("模型名", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp),
            )
            IconButton(onClick = onSetActive, enabled = model.enabled) {
                Icon(
                    imageVector = if (isActive) Icons.Default.Check else Icons.Default.Edit,
                    contentDescription = if (isActive) "当前模型" else "设为当前模型",
                    tint = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Switch(checked = model.enabled, onCheckedChange = onToggleEnabled)
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除模型",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
