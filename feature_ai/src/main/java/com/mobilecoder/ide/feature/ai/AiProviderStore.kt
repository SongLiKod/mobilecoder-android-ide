package com.mobilecoder.ide.feature.ai

import com.mobilecoder.ide.core.storage.AppStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多接口配置的持久化：JSON 序列化 + apiKey 加密 + 旧单配置迁移。
 *
 * JSON 结构（存于 `AppPreferences.aiProvidersJson()`）：
 * ```json
 * { "activeProviderId": "p1",
 *   "providers": [ { "id","name","baseUrl","keyEnc","enabled","activeModelId",
 *                    "models":[{"id","enabled"}] } ] }
 * ```
 * `keyEnc` 为 CryptoBox 密文（解密失败按空 Key 处理，绝不抛出）。
 */
object AiProviderStore {

    private val mutex = Mutex()

    /** 读取配置；无新键时用旧单接口配置迁移出第一条并落盘（幂等）。 */
    suspend fun load(): AiProvidersConfig = mutex.withLock {
        val raw = AppStorage.preferences.aiProvidersJson()
        if (raw.isNullOrBlank()) {
            return@withLock migrateLegacy()
        }
        return@withLock runCatching { parse(raw) }.getOrElse { migrateLegacy() }
    }

    /** 写回配置（调用方保证结构合法）。 */
    suspend fun save(config: AiProvidersConfig) {
        mutex.withLock {
            AppStorage.preferences.setAiProvidersJson(serialize(config))
        }
    }

    /** 新配置项 id（时间戳 36 进制 + 随机后缀，进程内唯一）。 */
    fun newId(): String = System.currentTimeMillis().toString(36) +
        (0..999).random().toString(36)

    /** 内置接口预设（设置页一键填写）。 */
    fun presets(): List<AiProviderPreset> = listOf(
        AiProviderPreset("OpenAI", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4o")),
        AiProviderPreset("DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner")),
        AiProviderPreset("Kimi", "https://api.moonshot.cn/v1", listOf("moonshot-v1-8k", "kimi-k2.5")),
        AiProviderPreset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", listOf("qwen-flash", "qwen-max")),
        AiProviderPreset("Ollama", "http://127.0.0.1:11434/v1", listOf("qwen3:8b", "llama3.2")),
    )

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    private suspend fun serialize(config: AiProvidersConfig): String {
        val providers = JSONArray()
        config.providers.forEach { p ->
            val models = JSONArray()
            p.models.forEach { m ->
                models.put(JSONObject().put("id", m.id).put("enabled", m.enabled))
            }
            providers.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("baseUrl", p.baseUrl)
                    .put("keyEnc", encryptKey(p.apiKey))
                    .put("enabled", p.enabled)
                    .put("activeModelId", p.activeModelId)
                    .put("models", models),
            )
        }
        return JSONObject()
            .put("activeProviderId", config.activeProviderId)
            .put("providers", providers)
            .toString()
    }

    private suspend fun parse(raw: String): AiProvidersConfig {
        val root = JSONObject(raw)
        val arr = root.optJSONArray("providers") ?: JSONArray()
        val providers = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val modelsArr = o.optJSONArray("models") ?: JSONArray()
            AiProvider(
                id = o.optString("id"),
                name = o.optString("name", o.optString("baseUrl", "接口")),
                baseUrl = o.optString("baseUrl"),
                apiKey = decryptKey(o.optString("keyEnc")),
                enabled = o.optBoolean("enabled", true),
                models = (0 until modelsArr.length()).map { j ->
                    val m = modelsArr.getJSONObject(j)
                    AiModel(id = m.optString("id"), enabled = m.optBoolean("enabled", true))
                },
                activeModelId = o.optString("activeModelId"),
            )
        }
        return AiProvidersConfig(
            providers = providers,
            activeProviderId = root.optString("activeProviderId"),
        )
    }

    /** 解密落盘 Key：空串短路，失败回退空串（Key 失效表现为 401，由容灾轮换接管）。 */
    private suspend fun decryptKey(encoded: String): String {
        if (encoded.isBlank()) return ""
        return runCatching { AppStorage.crypto.decryptToString(encoded) }.getOrDefault("")
    }

    /** 加密：空 Key 存空串（本地 Ollama 免 Key）。 */
    private suspend fun encryptKey(plain: String): String {
        if (plain.isEmpty()) return ""
        return runCatching { AppStorage.crypto.encryptToString(plain) }.getOrDefault("")
    }

    // ------------------------------------------------------------------
    // 旧单配置迁移
    // ------------------------------------------------------------------

    private suspend fun migrateLegacy(): AiProvidersConfig {
        val prefs = AppStorage.preferences
        val baseUrl = prefs.aiBaseUrl()
        val model = prefs.aiModel()
        val key = runCatching { prefs.aiApiKey() }.getOrDefault("")
        val config = legacyConfig(baseUrl, model, key)
        runCatching { prefs.setAiProvidersJson(serialize(config)) }
        return config
    }

    /** 由旧单接口三键组装出第一条多接口配置（纯函数，便于单测）。 */
    internal fun legacyConfig(baseUrl: String, model: String, key: String): AiProvidersConfig {
        val provider = AiProvider(
            id = newId(),
            name = legacyNameOf(baseUrl),
            baseUrl = baseUrl,
            apiKey = key,
            models = if (model.isBlank()) emptyList() else listOf(AiModel(model)),
            activeModelId = model,
        )
        return AiProvidersConfig(
            providers = listOf(provider),
            activeProviderId = provider.id,
        )
    }

    internal fun legacyNameOf(baseUrl: String): String = when {
        baseUrl.contains("openai.com") -> "OpenAI"
        baseUrl.contains("deepseek") -> "DeepSeek"
        baseUrl.contains("moonshot") -> "Kimi"
        baseUrl.contains("dashscope") -> "通义千问"
        baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost") -> "Ollama"
        else -> "默认接口"
    }
}
