# AI 免费模型接入方案（预设内置 + 动态拉取）

> 状态：**待确认**（未动代码）
> 关联模块：`feature_ai`（AiProviderStore / AiSettings / AiClient）
> 基于用户提供的平台清单整理，目标：开箱即用地接入免费/低价模型，Key 由用户提供、本地加密存储。

---

## 1. 背景与目标

当前设置页已有「快速添加接口」预设（`AiProviderStore.presets()`，5 个：OpenAI / DeepSeek / Kimi / 通义千问 / Ollama），
但存在三个缺口：

| 缺口 | 现状 | 目标 |
|---|---|---|
| 免费模型覆盖 | 预设均为商业模型，无“免费”导向 | 内置国内可直连的免费模型预设（智谱 glm-4-flash 等） |
| 模型名靠手输 | ProviderEditView 手动逐行填模型名，易拼错、不知道有哪些 | 联动平台接口**动态拉取模型列表**，勾选即添加 |
| 免费列表时效性 | 静态清单会过时（模型改名/下线/新免费档） | 拉取为准，静态清单仅作“一键起步” |

**原则**：
- 不改协议层（仍是 OpenAI 兼容 `{baseUrl}/chat/completions`），不引入 OkHttp（继续 HttpURLConnection）；
- Key 永远由用户自己填，App 只做本地 CryptoBox 加密存储（现状已如此）；
- 拉取失败必须可回退到现有“手动输入模型名”，不阻塞配置。

---

## 2. 方案总览

```
A. 内置静态预设（P0，纯本地）
   presets() 扩充：智谱 / 硅基流动 / Groq / OpenRouter / 百度(候选) / DeepSeek(已有)
   预设 chips 按「国内直连」「海外」分组，免费的带 ♆ 标记
        │
B. 动态拉取模型列表（P1，核心增量）
   通用 GET {baseUrl}/models（OpenAI 标准），编辑页新增「获取模型列表」
   → 弹窗：搜索 + 复选 + 一键添加；OpenRouter 额外识别 :free / pricing=0
        │
C. 免费清单聚合源（P2，可选）
   从 GitHub raw 拉取免费 API 清单（awesome-free-llm-apis 等）刷新“快速添加”区
   第三方依赖，仅作补充
```

---

## 3. A：内置静态预设清单

### 3.1 目标清单（对 `presets()` 的扩充）

| 分组 | 名称 | Base URL（拼 /chat/completions） | 预置模型（起步用） | 免费情况 | 备注 |
|---|---|---|---|---|---|
| 国内直连 | 智谱 AI | `https://open.bigmodel.cn/api/paas/v4` | `glm-4-flash` | **永久免费**，中文/代码不错 | 手机号注册即出 Key，OpenAI 兼容 |
| 国内直连 | 硅基流动 | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-7B-Instruct`（占位，以拉取为准） | 部分模型永久免费 + 新用户赠 token | 开源模型多（Qwen/DeepSeek/GLM），模型名长、**强烈依赖动态拉取** |
| 国内直连 | DeepSeek | `https://api.deepseek.com/v1` | `deepseek-chat` | 新用户赠额度（非永久免费） | **已有预设，保留**，代码能力强 |
| 国内直连 | 通义千问 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-flash` | 有免费额度 | **已有预设，保留** |
| 国内直连 | Kimi | `https://api.moonshot.cn/v1` | `moonshot-v1-8k` | 新用户赠额度 | **已有预设，保留** |
| 国内直连 | 百度千帆 | `https://qianfan.baidubce.com/v4`（**待真机验证**） | `ERNIE-Speed-8K`（待验证） | Speed 系列免费 | ⚠ 百度鉴权历史不标准，见风险 R4；先做“待验证”预设或暂不内置 |
| 海外 | Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` | 免费额度（速率高） | 推理极快；**需网络环境** |
| 海外 | OpenRouter | `https://openrouter.ai/api/v1` | `deepseek/deepseek-chat-v3-0324:free`（以拉取为准） | 免费模型池（`:free` 后缀） | 聚合平台；**需网络环境**；免费模型限流严 |
| — | OpenAI / Ollama | 现状保留 | — | — | 不变 |

> 说明：静态预设里的模型名只保证“能发起请求”，**是否仍免费、是否改名不做保证**——以第 4 节动态拉取结果为准。
> 智谱 glm-4-flash 为长期稳定的免费模型，作为默认推荐首选。

### 3.2 数据结构改动（小）

```kotlin
data class AiProviderPreset(
    val name: String,
    val baseUrl: String,
    val models: List<String>,
    val group: PresetGroup = PresetGroup.DOMESTIC,  // 新增：DOMESTIC 国内直连 / OVERSEAS 海外
    val freeTag: Boolean = false,                   // 新增：UI 显示「免费」标记
    val note: String = "",                          // 新增：一行说明（注册/网络提示）
)
```

- 不改 `AiProvider` / `AiModel` / 落盘 JSON 结构，**零迁移成本**；
- 现有 5 个预设补 `group=DOMESTIC`（Ollama 归 DOMESTIC 本地）。

### 3.3 UI 改动（设置页「快速添加接口」区）

```
快速添加接口 · 国内直连
[智谱 AI ♆] [硅基流动 ♆] [DeepSeek] [通义千问] [Kimi] [百度千帆*] [OpenAI] [Ollama]

快速添加接口 · 海外（需网络环境）
[Groq] [OpenRouter]
```

- 沿用现有 FilterChip 横滑，拆成两行分组；
- `freeTag=true` 的 chip label 追加 ` ♆ 免费`；
- 点击行为不变：一键填 baseUrl + 首模型 + 跳编辑页（Key 留空待填）。

---

## 4. B：动态拉取模型列表（核心）

### 4.1 接口约定

绝大多数 OpenAI 兼容平台实现了标准模型列表端点：

```
GET  {baseUrl}/models
Header: Authorization: Bearer {apiKey}   （Ollama / OpenRouter 公开列表可不带）
响应: { "data": [ { "id": "glm-4-flash", "owned_by": "...", ... }, ... ] }
```

| 平台 | /models 可用 | 免费判定字段 | 需要 Key |
|---|---|---|---|
| OpenRouter | ✓（且**公开，可无 Key**） | `id` 以 `:free` 结尾，或 `pricing.prompt=="0"` | 否 |
| 智谱 | ✓ | 无（按内置名单标注） | 是 |
| 硅基流动 | ✓ | 无标准字段（部分响应有标签，需探测） | 是 |
| Groq | ✓ | 无（免费额度制） | 是 |
| DeepSeek / Kimi / 通义 | ✓ | 无 | 是 |
| Ollama | ✓（本地） | 全免费 | 否 |
| 百度千帆 | 待验证 | 待验证 | 待验证 |

**免费判定策略**：
- OpenRouter：程序化识别（`:free` 后缀 / pricing=0），可靠；
- 其他平台：**无法程序化判定“免费”**，UI 提供「仅看免费」开关时——只对 OpenRouter 生效，其余平台该开关隐藏，
  改为按内置免费名单（智谱 `glm-4-flash` 等）做**优先置顶 + ♆ 标注**。

### 4.2 新文件 `AiModelCatalog.kt`（feature_ai）

```kotlin
internal object AiModelCatalog {
    /** GET {baseUrl}/models → 模型条目列表（失败返回 Result.failure，绝不抛到 UI）。 */
    suspend fun fetch(baseUrl: String, apiKey: String): Result<List<CatalogModel>>
}

internal data class CatalogModel(
    val id: String,
    val free: Boolean,       // OpenRouter 可判定，其余恒 false
    val hint: String = "",   // owned_by / 描述等副文本
)
```

实现要点：
- `HttpURLConnection`，`connectTimeout=10s / readTimeout=15s`，与 AiClient 风格一致；
- baseUrl 归一化：`trimEnd('/') + "/models"`；
- 响应解析容错（`data[].id` 缺失的条目跳过；非 JSON → 失败）；
- 结果按：`free 优先 → 字母序`；上限 500 条防 UI 卡顿；
- **单测注意**：项目单元测试环境 `org.json` 不可用（mockable jar 构造即抛），
  解析函数做成 `internal` 并走 `runCatching` 兜底路径测试（同 AiClient.httpError 既有做法），
  完整解析靠真机验证。

### 4.3 UI：ProviderEditView 新增「获取模型列表」

```
┌─ 编辑接口 ────────────────────────┐
│ 名称 / Base URL / API Key（现状不变）      │
│                                        │
│ 模型（3 个，可分别启用）   [＋添加模型] [⤓获取模型列表] │
│ ┌──────────────────────────────────┐ │
│ │ ModelRow（现状，手改仍可用）              │ │
│ └──────────────────────────────────┘ │
│ ...                                   │
└────────────────────────────────────────┘
```

点击「获取模型列表」→ 底部弹窗（ModalBottomSheet 或 AlertDialog）：

```
┌─ 可用模型（42） ─────────────────────┐
│ [搜索模型名............]  [仅看免费✓]   │   ← 仅看免费：OpenRouter 可用
│ ☑ glm-4-flash        ♆ 免费           │
│ ☐ glm-4.5-flash                       │
│ ☑ glm-4-air                           │
│ ...（LazyColumn 高效滚动）                │
│            [取消]  [添加所选 (2)]        │
└──────────────────────────────────────┘
```

行为：
1. 入口启用条件：`baseUrl` 非空；Key 可空（OpenRouter/Ollama 公开列表允许）；
2. 加载中：按钮转圈，弹窗内 ProgressBar；
3. 失败：Toast/内联提示「该平台不支持自动获取（或网络不可达），可手动输入模型名」——**不阻断手动流程**；
4. 勾选「添加所选」→ 合并进 `models`（**去重**，已存在的跳过）；
   默认把第一个新添加的模型设为 `activeModelId`（若当前为空）；
5. 添加后仍可逐行启用/禁用/删除（现有 ModelRow 能力不变），参与既有容灾候选序列 `candidates()`。

### 4.4 与现有容灾的协同（无需改动，说明）

- 拉来的多个模型勾选启用后，自动进入 `AiProvidersConfig.candidates()`：
  `当前模型优先 → 其余启用模型按序`，429/额度失败自动轮换（已有逻辑）；
- 免费模型 429 高发 → 既有 `AiFailureTracker` 冷却 + 切换横幅（上一轮已修：横幅常驻、from 恒为首选）正好覆盖。

---

## 5. C：免费清单聚合源（P2，可选）

用户提到的 GitHub 清单仓库定位是“**API 供应商目录**”（awesome-free-llm-apis / public-apis），不是模型列表：

- 可用法：App 内「模型市场」页签，raw 拉取清单仓库的 Markdown/JSON，
  解析出「平台名 + base_url + 免费说明」→ 一键生成预设草稿（Key 用户自填）；
- 风险：第三方仓库结构随时变、国内访问 GitHub 不稳 → **仅作补充，失败静默回退到内置预设**；
- 结论：建议放 P2，先看 P0/P1 使用反馈再决定。

---

## 6. 改动清单（按阶段）

### P0 — 内置预设扩充（半天）
| 文件 | 改动 |
|---|---|
| `AiProviderStore.kt` | `AiProviderPreset` 加 `group/freeTag/note`；`presets()` 扩至 ~9 项 |
| `AiSettings.kt` | 快速添加区分组两行 + 免费标记；点击逻辑不变 |
| `AiProviderStoreTest`（新增/扩展） | presets 唯一性、baseUrl 规范、首模型非空断言 |

### P1 — 动态拉取（1~1.5 天）
| 文件 | 改动 |
|---|---|
| **新** `AiModelCatalog.kt` | GET /models + 解析 + 免费识别（OpenRouter） |
| `AiSettings.kt` | 编辑页「获取模型列表」按钮 + 模型勾选弹窗（搜索/仅看免费/去重合并） |
| 单测 | `parseModels()` 兜底路径、免费识别规则（`:free` / pricing=0） |

### P2 — 聚合清单（可选，0.5~1 天）
| 文件 | 改动 |
|---|---|
| **新** `AiPresetMarket.kt` | raw 拉取 + 简单解析 + 失败回退 |
| `AiSettings.kt` | 「更多免费来源」入口 |

**验收标准**：
1. 不填任何 Key：预设 chips 分组可见，免费标记正确；
2. 智谱：填 Key → 获取列表 → 勾 `glm-4-flash` → 测试连通 ✓ → 发消息走通（真机）；
3. OpenRouter：不填 Key 也能拉到列表，「仅看免费」只见 `:free` 模型；
4. 拉取失败平台：提示后仍可手动输入模型名并保存；
5. 既有 46 例单测不回归，新增用例通过。

---

## 7. 风险与限制

| # | 风险 | 应对 |
|---|---|---|
| R1 | 海外平台（Groq/OpenRouter）国内直连不稳 | 预设 note 标注「需网络环境」；失败走既有容灾（超时→切换候选） |
| R2 | 免费模型 429/限流频繁 | 既有 FailureTracker 冷却 + 候选轮换 + 常驻切换横幅，无需新代码 |
| R3 | 模型改名/下线导致静态预设失效 | 静态仅作起步，动态拉取为准；`test()` 按钮可先验连通 |
| R4 | 百度千帆鉴权非纯 OpenAI 兼容（历史 API Key/Token 体系） | 标「待真机验证」，验证通过再启用预设，否则不内置 |
| R5 | `/models` 端点部分平台不支持 | 按钮失败提示 + 手动输入兜底，配置流程不被阻断 |
| R6 | Key 安全 | 维持现状：仅本地 CryptoBox 加密落盘，不上传、不进日志 |
| R7 | “免费”无统一标准（除 OpenRouter 外无法程序化判定） | 内置名单 ♆ 标注 + OpenRouter 程序化识别；文案不承诺“永久免费” |
| R8 | org.json 单测不可用 | 解析走 runCatching 兜底测试 + 真机验证（项目既有做法） |

---

## 8. 待确认问题（请拍板）

1. **预设范围**：国内 6 家 + 海外 2 家全加？还是先只加「智谱 + 硅基流动 + Groq/OpenRouter」4 家？
2. **百度千帆**：因鉴权待验证——A. 先内置并标「待验证」；B. 暂不内置，验证后补（推荐 B）？
3. **动态拉取**：只做 P1（各平台 /models 标准端点，推荐）？还是连 P2（GitHub 清单聚合）一起做？
4. **海外预设的网络提示**：chip 上直接标「需网络环境」可以吗？
5. **OpenRouter 无 Key 拉取**：允许不填 Key 就「获取模型列表」（公开接口，推荐允许）？

确认后按 P0 → P1 顺序实施，每阶段完成会跑全量单测 + 真机验收。
