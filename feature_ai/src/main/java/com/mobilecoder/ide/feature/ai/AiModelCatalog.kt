package com.mobilecoder.ide.feature.ai

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 平台模型目录条目（`GET /models` 解析结果）。 */
internal data class CatalogModel(
    /** 发给接口的模型 id（如 `glm-4-flash`、`deepseek/deepseek-chat-v3:free`）。 */
    val id: String,
    /** 是否识别为免费模型（仅 OpenRouter 等可程序化判定的平台）。 */
    val free: Boolean = false,
    /** 副文本（owned_by 等）。 */
    val hint: String = "",
)

/**
 * 动态模型列表拉取（方案 P1）：OpenAI 标准 `GET {baseUrl}/models`。
 *
 * - Key 可空：OpenRouter / Ollama 的列表公开，其余平台 401 会走失败提示，不阻塞手动输入；
 * - 免费识别：id 以 `:free` 结尾恒算免费；OpenRouter 额外认 `pricing` 全 0；
 *   其余平台无统一“免费”字段，靠内置名单标注（见 [AiProviderStore.presets]）；
 * - 失败一律 `Result.failure`，绝不抛到 UI 层。
 */
internal object AiModelCatalog {

    /** 结果上限（防超大列表拖垮 UI）。 */
    private const val MAX_MODELS = 500

    /** 拉取并解析模型列表。 */
    suspend fun fetch(baseUrl: String, apiKey: String): Result<List<CatalogModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val normalized = baseUrl.trim().trimEnd('/')
                require(normalized.isNotEmpty()) { "Base URL 为空" }
                val conn = (URL("$normalized/models").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    setRequestProperty("Accept", "application/json")
                    if (apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = runCatching {
                            conn.errorStream?.bufferedReader()?.use { it.readText() }
                        }.getOrNull().orEmpty()
                        throw IOException(
                            "HTTP $code" + (if (err.isNotBlank()) "：${err.take(120)}" else ""),
                        )
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseModels(body, normalized)
                } finally {
                    conn.disconnect()
                }
            }
        }

    /**
     * 解析 `{"data":[{"id","owned_by","pricing"?}...]}`。
     *
     * 注意：org.json 在单元测试环境不可用（mockable jar），构造异常由调用方 runCatching 兜底；
     * 可测的纯逻辑（免费标记、排序限流）拆在 [hasFreeTag] / [finalizeList]。
     */
    internal fun parseModels(body: String, baseUrl: String): List<CatalogModel> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val freeCapable = baseUrl.contains("openrouter.ai", ignoreCase = true)
        val out = ArrayList<CatalogModel>(data.length())
        val seen = HashSet<String>()
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isBlank() || !seen.add(id)) continue
            out += CatalogModel(
                id = id,
                free = hasFreeTag(id) || (freeCapable && pricingAllZero(o)),
                hint = o.optString("owned_by"),
            )
        }
        return finalizeList(out)
    }

    /** 免费标记（纯函数）：`:free` 后缀（OpenRouter 惯例）。 */
    internal fun hasFreeTag(id: String): Boolean = id.trim().endsWith(":free")

    /** OpenRouter pricing 全 0（prompt/completion 都为 0）视为免费。 */
    private fun pricingAllZero(raw: JSONObject): Boolean = runCatching {
        val pricing = raw.optJSONObject("pricing") ?: return false
        val p = pricing.optString("prompt").toDoubleOrNull()
        val c = pricing.optString("completion").toDoubleOrNull()
        p != null && c != null && p == 0.0 && c == 0.0
    }.getOrDefault(false)

    /** 排序与限量（纯函数）：免费在前 → 字母序，上限 [MAX_MODELS]。 */
    internal fun finalizeList(models: List<CatalogModel>): List<CatalogModel> =
        models
            .sortedWith(compareByDescending<CatalogModel> { it.free }.thenBy { it.id })
            .take(MAX_MODELS)
}
