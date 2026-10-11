# Issue 19 实施参考：息屏保活（长任务在锁屏/息屏下稳定执行）

> 对应 `docs/issue-backlog.md` 的 **Issue 19**。
> 本文档给出**代码级**改动清单，供直接落地实现。所有改动遵循项目现有架构（object 单例 + StateFlow、前台服务模式、DataStore 设置项、无 DI 框架）。

---

## 0. 目标与边界

**目标**：终端 / 构建的长任务（`npm install`、`git push`、Gradle 编译等）在**息屏、锁屏、切后台**后照常执行，屏幕可熄灭（省电），任务不中断。

**明确不做**：
- ❌ 不做「锁屏上显示 App 界面」（`setShowWhenLocked` / 悬浮窗 / Keyguard 覆盖层）——需求只是「执行」，不是「显示」；
- ❌ 不做「屏幕常亮」（`FLAG_KEEP_SCREEN_ON`）——那会整夜耗电，与「省电」目标相反。

**核心原理**：息屏后任务中断的三个系统级原因，逐一解决：

| 原因 | 解决手段 | 现状 |
|---|---|---|
| 进程被回收 / 冻结 | 前台服务 | ✅ 已有（Build / Terminal） |
| CPU 线程被挂起 | `PARTIAL_WAKE_LOCK` | ⚠️ 构建有，终端缺 |
| Doze 禁用网络 / 忽略唤醒锁 | 电池优化白名单 | ❌ 缺（最关键） |
| 服务被杀不复活 | `START_STICKY` | ❌ 两个服务都是 `START_NOT_STICKY` |

---

## 1. 改动文件总览

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `feature_terminal/.../TerminalForegroundService.kt` | 修改 | 补 `PARTIAL_WAKE_LOCK`；`START_NOT_STICKY → START_STICKY` |
| `feature_build/.../BuildForegroundService.kt` | 修改 | `START_NOT_STICKY → START_STICKY` |
| `app/src/main/AndroidManifest.xml` | 修改 | 新增 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限 |
| `core_storage/.../AppPreferences.kt` | 修改 | 新增 `keepRunningOnLockScreen()` 开关（默认开） |
| `app/src/main/java/com/mobilecoder/ide/ui/SettingsScreen.kt` | 修改 | 新增「息屏保活」设置区 + 白名单引导 |
| `core_common/.../PowerKeepAlive.kt` | 新增（可选） | 统一封装白名单检查/请求、屏幕状态监听、持锁/释放 |
| `app/src/main/java/com/mobilecoder/ide/MobileCoderApplication.kt` | 修改（可选） | 启动时读取保活开关，注册屏幕状态监听 |

---

## 2. 终端服务补 WakeLock（核心改动）

文件：`feature_terminal/src/main/java/com/mobilecoder/ide/feature/terminal/TerminalForegroundService.kt`

### 2.1 增加 WakeLock 字段与持锁/释放

参照 `BuildForegroundService` 的写法（`acquireWakeLock` / `releaseWakeLock`），在 `TerminalForegroundService` 中补齐：

```kotlin
class TerminalForegroundService : LifecycleService() {

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                promote(intent.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { keepAliveText(1) })
                stop()
            }
            else -> {
                // 会话存在期间持锁：息屏后 CPU 保持运行，长任务不被挂起
                acquireWakeLock()
                promote(intent?.getStringExtra(EXTRA_TEXT).orEmpty().ifBlank { keepAliveText(1) })
            }
        }
        return START_STICKY   // ← 由 START_NOT_STICKY 改为 START_STICKY
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    // ... 其余不变 ...

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileCoder:terminal").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }
}
```

### 2.2 关键点说明

- **持锁时机**：`onStartCommand` 的 `else` 分支（即 `keepAlive` 拉起时）持锁；`ACTION_STOP`（全部会话关闭）时释放。
- **释放时机**：`stop()` 里调用 `releaseWakeLock()`；`onDestroy()` 兜底释放（防泄漏）。
- **`setReferenceCounted(false)`**：避免重复 `keepAlive` 刷新通知时引用计数叠加导致无法释放。
- **`START_STICKY`**：进程被系统杀掉后，系统会尝试重建服务并重新投递 `onStartCommand`（intent 可能为 null，需在 `onStartCommand` 里对 null intent 做防御——上面 `intent?.action` 已用安全调用，null 时走 `else` 分支持锁+promote，安全）。

> ⚠️ 注意：`START_STICKY` 下系统重建时 `intent` 可能为 null，`promote` 的文案用默认值兜底即可（现有代码已处理）。

---

## 3. 构建服务改 START_STICKY

文件：`feature_build/src/main/java/com/mobilecoder/ide/feature/build/BuildForegroundService.kt`

仅改一行：

```kotlin
return START_STICKY   // ← 由 START_NOT_STICKY 改为 START_STICKY
```

构建服务已持 `PARTIAL_WAKE_LOCK`（`acquireWakeLock` 在 `else` 分支），无需再补。改为 `START_STICKY` 后，构建中途进程被杀可自动重建续跑。

> ⚠️ 同样注意：`onStartCommand` 里 `intent?.action` 已用安全调用，null intent 时走 `else` 分支（持锁 + promote），安全。

---

## 4. 新增权限

文件：`app/src/main/AndroidManifest.xml`

在 `<uses-permission>` 区新增：

```xml
<!-- 息屏保活：引导用户加入「电池优化白名单」，Doze 下网络/唤醒锁不受限 -->
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

> 说明：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 仅用于**跳转系统白名单设置页**（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），不申请「直接豁免」权限（那需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 之外的 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹窗，且 Google Play 对滥用有审查）。这里采用「引导用户手动授权」的合规做法。

---

## 5. 设置项持久化

文件：`core_storage/src/main/java/com/mobilecoder/ide/core/storage/AppPreferences.kt`

在「终端」或新增「息屏保活」区块加入：

```kotlin
// ---------------- 息屏保活 ----------------

/** 息屏保活：开启后终端/构建长任务在锁屏、息屏下持续执行（默认开）。 */
suspend fun keepRunningOnLockScreen(): Boolean = get(KEY_KEEP_RUNNING) != "0"
suspend fun setKeepRunningOnLockScreen(value: Boolean) =
    set(KEY_KEEP_RUNNING, if (value) "1" else "0")
```

并在文件底部 `companion object` 的 key 常量区新增：

```kotlin
private val KEY_KEEP_RUNNING = stringPreferencesKey("keep_running_on_lock_screen")
```

> 参考现有 `terminalScreenOn()`（`KEY_TERM_SCREEN_ON`）的写法，保持一致。

---

## 6. 设置页 UI

文件：`app/src/main/java/com/mobilecoder/ide/ui/SettingsScreen.kt`

在「导航」区块之后（或「文件打开」之前）新增「息屏保活」设置区。需要：

1. 新增参数：`keepRunningOnLockScreen: Boolean`、`onKeepRunningOnLockScreenChange: (Boolean) -> Unit`；
2. 新增「加入电池优化白名单」按钮，跳系统设置页；
3. 说明文案（含国产 ROM 后台限制提示）。

### 6.1 新增参数（函数签名）

```kotlin
@Composable
fun SettingsScreen(
    themeManager: ThemeManager,
    // ... 现有参数 ...
    keepRunningOnLockScreen: Boolean = true,
    onKeepRunningOnLockScreenChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
)
```

### 6.2 新增设置区 UI

```kotlin
HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

// ---------------- 息屏保活 ----------------
Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
    SectionHeader(title = "息屏保活")
    Spacer(modifier = Modifier.padding(top = 4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "锁屏/息屏后继续执行任务",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "开启后，终端里的安装/同步/编译与构建打包在息屏、锁屏时不被中断，屏幕可正常熄灭省电",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = keepRunningOnLockScreen,
            onCheckedChange = onKeepRunningOnLockScreenChange,
        )
    }

    // 加入电池优化白名单（Doze 下网络/唤醒锁不受限，长任务才真正稳定）
    Spacer(modifier = Modifier.padding(top = 8.dp))
    OutlinedButton(
        onClick = { requestIgnoreBatteryOptimizations(context) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("加入电池优化白名单")
    }
    Text(
        text = "部分系统（尤其国产 ROM）还需在「省电策略→无限制」「后台运行白名单/自启动」中放行本应用，否则息屏后仍可能被系统冻结。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}
```

### 6.3 跳转白名单设置页的辅助函数

```kotlin
private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(context.packageName)) {
            // 已在白名单，提示即可
            Toast.makeText(context, "已在电池优化白名单中", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }.onFailure {
        // 部分 ROM 不支持该 action，回退到应用详情页
        runCatching {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }
    }
}
```

> 需要 import：`android.os.PowerManager`、`android.provider.Settings`、`android.net.Uri`、`android.widget.Toast`、`android.content.Intent`、`android.content.Context`、`androidx.compose.material.icons.filled.BatteryChargingFull`。

### 6.4 在 AppRoot 中接线

文件：`app/src/main/java/com/mobilecoder/ide/ui/AppRoot.kt`

在 `SettingsScreen(...)` 调用处新增两个参数（与现有 `floatingDotEnabled` 等参数同模式）：

```kotlin
var keepRunningOnLockScreen by remember { mutableStateOf(true) }
LaunchedEffect(Unit) {
    keepRunningOnLockScreen = runCatching {
        AppStorage.preferences.keepRunningOnLockScreen()
    }.getOrDefault(true)
}
// ...
SettingsScreen(
    // ... 现有参数 ...
    keepRunningOnLockScreen = keepRunningOnLockScreen,
    onKeepRunningOnLockScreenChange = { next ->
        keepRunningOnLockScreen = next
        dotScope.launch {
            runCatching { AppStorage.preferences.setKeepRunningOnLockScreen(next) }
        }
    },
)
```

---

## 7. 可选：统一封装 PowerKeepAlive

文件：`core_common/src/main/java/com/mobilecoder/ide/core/common/PowerKeepAlive.kt`（新增）

把白名单检查、屏幕状态监听、持锁/释放收敛到一处，供终端/构建服务复用：

```kotlin
package com.mobilecoder.ide.core.common

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager

/**
 * 息屏保活统一封装（Issue 19）。
 *
 * 职责：
 *  1. 电池优化白名单状态检查 / 是否已豁免；
 *  2. 屏幕开关（SCREEN_OFF / SCREEN_ON）监听，供 UI 提示或按需降频；
 *  3. 持锁/释放的幂等封装（PARTIAL_WAKE_LOCK，setReferenceCounted(false)）。
 */
object PowerKeepAlive {

    /** 是否已加入电池优化白名单（Doze 下网络/唤醒锁不受限）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    /** 注册屏幕开关监听（返回 receiver，调用方负责注销）。 */
    fun registerScreenReceiver(
        context: Context,
        onScreenOff: () -> Unit,
        onScreenOn: () -> Unit,
    ): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(receiver, filter)
        return receiver
    }
}
```

> 说明：`registerReceiver` 在 Android 13+ 需指定 `RECEIVER_NOT_EXPORTED` flag（`ContextCompat.registerReceiver`），落地时按项目 minSdk 29 处理（`ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`）。

---

## 8. 触发时机（无需用户额外操作）

沿用现有钩子，自动进入/退出保活：

| 场景 | 钩子 | 动作 |
|---|---|---|
| 新建终端会话 | `TerminalManager.create()` → `syncKeepAlive()` | 拉起前台服务 + 持锁 |
| 关闭全部会话 | `TerminalManager.closeAll()` → `syncKeepAlive()` | 停服务 + 释放锁 |
| 构建开始 | `BuildRunner` 构建启动 | 拉起前台服务 + 持锁（已有） |
| 构建结束 | `BuildRunner` 构建结束 | 停服务 + 释放锁（已有） |

> 若实现「保活开关」，可在 `syncKeepAlive()` 与 `BuildRunner` 中读取 `AppStorage.preferences.keepRunningOnLockScreen()`，关闭时跳过持锁（仅保留前台服务，或连前台服务也不拉）。

---

## 9. 验收清单

### 9.1 功能验收
- [ ] 开着终端会话，息屏 10 分钟以上，`npm install` / `git push` 不被中断、网络不通断；
- [ ] 构建全程息屏不被中断，构建完有完成通知；
- [ ] 全部终端会话关闭后 WakeLock 释放、通知消失；
- [ ] 服务被系统杀掉后自动重启并续跑（`START_STICKY`）；
- [ ] 设置项开关持久化、重启后仍生效；
- [ ] 「加入电池优化白名单」跳转正确；已在白名单时给出提示；ROM 不支持时回退应用详情页。

### 9.2 稳定性 / 耗电验收
- [ ] 关闭保活开关后，息屏耗电曲线正常（无异常耗电）；
- [ ] 开启保活开关跑长任务时，息屏耗电在可接受范围（CPU 保持运行的必然代价）；
- [ ] 原生 Android 与主流国产 ROM（MIUI / EMUI / ColorOS）各验一轮。

### 9.3 单测
- [ ] 持锁/释放边界：会话数 0↔N 时正确持锁/释放（`keepAliveText` 已有单测，可扩展）；
- [ ] 白名单状态判断（`isIgnoringBatteryOptimizations`）；
- [ ] 相关文案单测。

---

## 10. 风险与注意

| 风险 | 说明 | 对策 |
|---|---|---|
| 国产 ROM 杀后台 | 即使白名单，部分 ROM 仍会冻结 | 设置页文案引导「省电策略→无限制」「自启动」 |
| 后台启动前台服务限制 | Android 12+ 限制后台拉起前台服务 | 现有服务都是前台时刻拉起（建会话/点构建），不受影响 |
| `START_STICKY` 重建时 intent 为 null | 系统重建服务时可能传 null | `onStartCommand` 用安全调用 + 默认文案兜底（现有代码已处理） |
| 持锁耗电 | 息屏后 CPU 保持运行必然耗电 | 提供设置开关，默认开但可关；会话关闭即释放 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 合规 | Google Play 对滥用有审查 | 采用「引导用户手动授权」而非直接申请豁免 |
| targetSdk 28 与 Android 12+ 行为差异 | 低 targetSdk 下前台服务类型限制 | 现有服务类型 `dataSync` 已声明，回归验证即可 |

---

## 11. 相关文档

- `docs/issue-backlog.md` → Issue 19（主 issue）
- `README.md` → 2.3 内置终端 / 2.6 编译构建 / 9 权限说明（落地后需同步更新）
