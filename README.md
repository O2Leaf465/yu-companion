# 薇希 · 安卓聊天 App（M1）

从 QQAgent 重写出来的专用聊天 App。目标不是"功能更多"，是**同样的聊天量花 1/40 的钱**。

## 省 token 的六条杠杆 → 落在哪个文件

| # | 手段 | 实现位置 | 状态 |
|---|---|---|---|
| 1 | 不要工具循环（回复直接落 UI） | `ChatViewModel.runTurn()` 一次 `LlmClient.chat()` | ✅ |
| 2 | 历史窗口 20 条 + 本地摘要 | `PromptBuilder(historyLimit = 20)` | ✅（摘要位留给 M2） |
| 3 | 前缀稳定 → 命中缓存 | `PersonaEngine.systemBlock` 只算一次；`PromptBuilder.build()` 里 `require()` 拦住易变内容 | ✅ |
| 4 | 记忆按需检索 ≤600 字 | `ChatViewModel` 的 `recall = null`（M2 接） | ⬜ M2 |
| 5 | 主动开口独立轻量 prompt | — | ⬜ M4 |
| 6 | 输出限长 | `max_tokens = 180`；`ReplySplitter.MAX_CHARS = 60` | ✅ |

## 三个刻意的反直觉决定

**1. 不做流式。**
token 由输入输出长度决定，跟流式无关。不做流式省掉一个 SSE 解析器和一堆半包 bug，
而观感靠"本地伪造打字节奏"更好：`ReplySplitter.typingDelayMillis()` 按字数算延迟，
`betweenBubblesMillis()` 给条间随机间隔。

**2. 一次调用吐 1~3 条，用换行切。**
不用 JSON（中文引号会把解析搞炸）。`ReplySplitter.split()` 负责切条、去 Markdown、
限制字数、剥掉"好感 58"这类数字泄漏。

**3. 连发合并。**
800ms 内连发三条 → 合成一次请求（`ChatViewModel.DEBOUNCE_MS`）。
不合并就是三次全量调用。

## 目录

```
app/src/main/
  assets/persona.md      人设（改它 = 改每轮成本的基础，动前先想缓存）
  assets/anchors.json    19 条锚点，从 migration-seed.json 生成，全量常驻
  java/com/leaf/vixi/
    App.kt               单例入口
    llm/PersonaEngine.kt 稳定前缀（人设 + 锚点 + 输出规则）
    llm/PromptBuilder.kt 稳定前缀 + 易变尾部，带断言
    llm/LlmClient.kt     OpenAI 兼容，一次调用，空回复加重试
    llm/ReplySplitter.kt 切条 + 演出节奏参数
    ui/ChatViewModel.kt  一轮对话的编排（唯一花钱的路径）+ 内部状态 dump
    ui/ChatScreen.kt     气泡 / 时间分隔 / 打字指示 / 输入条
    ui/DebugScreen.kt    0 token 看全内部状态，可一键复制
    ui/SettingsScreen.kt baseUrl + model + key + 测试连接
    data/SettingsStore.kt key 走 AndroidKeyStore AES-GCM
    data/MessageStore.kt 聊天记录落盘（files/chat.jsonl，追加写）
```

## 开发循环

不要"出安装包再装"，也不要 `gradlew installDebug` —— 这台 vivo 会弹「继续安装」
确认框把 adb 卡死。用这两个：

```powershell
cd android-companion
.\gradlew.bat assembleDebug
.\install-apk.ps1 -Apk .\app\build\outputs\apk\debug\app-debug.apk
```

`install-apk.ps1` 会在装的同时轮询界面，自动把「继续安装」点掉，全程约 10 秒，不用碰手机。

## 界面上的三件事（都是为了让对面像人）

1. **全面屏 insets**：`enableEdgeToEdge()` + `windowInsetsPadding(WindowInsets.safeDrawing)`。
   targetSdk 35 在 Android 15 上强制全面屏，不做这个输入框会画到导航栏底下。
2. **"对方正在输入…"**：顶部副标题显示，配三个跳动的点。不用静态 `···`，那个一眼是机器。
3. **连续气泡只留一个尖角**：同一方 10 分钟内的连续消息，只在最后一条收尖角，
   看起来才像"一口气说了几句"，而不是几条独立通知。

## 构建

```powershell
# 本机没有系统 JDK，全部依赖放在 toolchain/ 里
& C:\Users\JG\Documents\deepseek-harness\default-workspace\build_m1.ps1
# 或直接
cd android-companion; .\gradlew.bat assembleDebug
```

`gradlew.bat` 顶部有一段 `VIXI_ENV_PINNED`，固化三件事（本机特有，换机器要改）：
- `JAVA_HOME` → `toolchain/jdk-17.0.21+9`
- `GRADLE_USER_HOME` → `toolchain/gradle-home`
- `GRADLE_OPTS` 把 `java.io.tmpdir` 和 `org.gradle.native.dir` 指到工作区
  （沙箱不允许往系统临时目录解压 `native-platform.dll`，否则 Gradle 起不来）

`distributionUrl` 指向本地 `gradle-8.9-bin.zip`，不走网。

## 持久化（chat.jsonl）

`files/chat.jsonl`，一行一条 `{"fromUser":true,"text":"...","ts":...}`。

**方向约定别搞混**：`fromUser` 和 `Bubble.self` 都表示"是手机这头的人（Leaf）发的"，
而 `llm.Turn.self` 表示"是薇希说的"，正好相反。第一版把两套约定串了，
后果是重启后气泡左右颠倒 —— 改这里时先确认用的是哪一套。

以后接三层记忆时，这个文件直接丢掉重建即可，不需要迁移。

## 设置页的「测试连接」

发一个 `max_tokens=8` 的最小请求（约 ¥0.00001），一次区分：key 错 / 地址错 /
模型名错 / 网络不通。比在聊天页试错快得多。

## 人设 / 系统提示词（两个入口，分工明确）

设置里是分开的两项：

| 入口 | 内容 | 可编辑 |
|---|---|---|
| **人设** | 只是角色设定：她是谁、怎么说话。存 `files/persona_override.md`，没有就回退 `assets/persona.md`，「还原默认」永远可用 | ✅ |
| **系统提示词** | 每轮实际发给模型的**完整 system**：人设 + 输出格式约定，逐字只读，可复制 | ❌ |

为什么分开：格式约定（怎么分条、能不能用 Markdown）是 App 的机械规则，不是性格的一部分。
第一版把它 append 在人设后面且不给看，造成"改了人设却没效果" —— 现在规则可视化，
但它不会污染人设编辑器。

改人设即时生效（`PersonaEngine.systemBlock` 是可失效缓存，`PromptBuilder` 每轮现取）。
代价：改一次会让下一轮缓存失效一次，之后重新稳定 —— 别频繁微调。

### 换角色时还要注意一件事

**聊天历史会把她拽回原来的身份**。模型对"最近几轮我们是怎么说话的"的模仿，
强于对 system 的服从。所以改完人设后旧对话还在的话，她仍可能自称旧角色。
要真正换人，得同时清掉 `files/chat.jsonl`。

## 请求记录（每次发给 API 的完整提示词）

调试 → 请求记录。每条请求都完整保存：**SYSTEM 全文 / USER 全文 / 模型原始返回 / 切条结果 /
token 用量 / 成本**，可以单条看，也可以"复制全部"发出去。

只留最近 12 条，只在内存里（退出 App 就没了），不会把提示词写进手机存储。
失败请求也会记，带错误信息 —— 那是查"为什么她没回"最快的入口。

## 那几个系统已清空（2026-11-16）

好感、心情、检索、日卡摘要全部从 prompt 里移除。现在 user 段只有真实存在的东西：

```
【已读】最近 20 条
【新消息】这一轮的话
【现在】23:40，夜里，有点困
```

`PromptBuilder.build()` 的签名只剩 `history / incoming / momentLine`。
重新引入时改动点上：加参数 + 在尾部加一行 append，别往 system 里塞。

**记忆锚点也已移除**（准备换一套记忆实现思路）：`assets/anchors.json` 从 App 里删了，
`PersonaEngine` 不再拼锚点。原始 19 条没有销毁，仍在工作区 `migration-seed.json` 里。

副作用：system 段从 4684 字降到 230 字左右，每轮输入 token 大幅下降。
注意调试页的**缓存命中率会变低**，那不是变贵了 —— 是分母（稳定前缀）变小了。
真正决定成本的还是"每轮一次调用 + 历史窗口 20 条"。

## M1 验收（已在 vivo V2329A / Android 15 上跑通）

- [x] 聊天界面、输入框不被导航栏遮挡、系统返回键不直接退出
- [x] 一次请求 → 多条气泡逐条吐出（用本地假模型服务验证：1 次请求 → 3 条「嗯/干嘛/困了」）
- [x] 调试页显示真实数字：prompt 3208、cache_hit 2663（83%）、成本 ¥0.00061
- [x] 连发合并、空回复重试、错误条（401/余额/超时各有提示）
- [x] 记录落盘：杀进程重开后气泡、顺序、左右方向都对
- [x] 设置页「测试连接」可用
- [x] 人设可查看 / 修改 / 还原
- [ ] 真实 DeepSeek key 跑 20 轮：她不变客服、不总结、不每条都问句收尾 ← **需要你填 key**

调试页的 `in` 数字每轮只涨一次 = 每轮只有一次调用；缓存率低于 70% 说明有易变内容漏进了前缀。

## 还没做（按你之前说的，后面再说）

- 记忆三层（`messages` / `cards` / `anchors` 表）与旧数据迁移 —— 待重写
- 好感 / 心情 / 生活节奏 —— 待重写，接口已清空
- 主动开口 + 前台服务 + 通知

## 已知取舍

- 人设里原本依赖工具的句子（`send_message` / `web_search` / `memory_append`）改成了
  "另起一行分条""不能联网，别编"。**这是唯一改动过人设正文的地方**，其余逐字保留。
- 锚点 19 条实测 1338 字，比交接文档估的 900 字多，占每轮约 7%。
