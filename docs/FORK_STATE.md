# 本地分叉状态（给后续会话看的交接文档）

> 最后更新：2026-10-28（含 10-27 夜间一轮 + #006 会话内存修复，见 4.6）。只写实测结论；不确定的地方标「待核实」。
> 目的：任何会话接手前先读这一页，避免把「本地自研」当成上游行为，或把「上游已有」重复实现。

## 1. 代码基线

| 项 | 值 |
|---|---|
| 仓库 | `项目/Eta/Eta主源码`（fork `Oo0520/Eta-por`，upstream `Mangi-11/Eta`） |
| 当前分支 | `feat/auto-backup-full-backup` |
| HEAD | `c57ff0b`（自动备份可靠性与对账修复 + 全量压缩包导入导出） |
| 上游对齐点 | `origin/main` = `b322d98`（准备发布 3.3.4） |
| 工作区状态 | **不等于 HEAD**：`git status --porcelain` 实测 207 条（2026-10-28 实测，含 #006 修复的 4 个源文件与 1 个 DAO）。构建用的是工作区，不是 HEAD |
| versionCode | `2026102803`（工作区已前移）；#006 修复包为 `2026102801`；**设备上实际装的是 `2026102728`**（2026-10-28 `inspect_app` 实测，旧记录的 2026102719 已过期） |

结论：判断「某功能是否存在」时必须读工作区文件，`git show HEAD:` 会漏掉大量未提交实现。

## 2. 上游没有、本项目自己实现的能力（别当成上游行为）

用 `git grep <关键字> origin/main` 实测确认，以下在上游 `origin/main` 里**完全不存在**：

- **虚拟屏（灵屏）**整套：`agent/display/`、`VirtualScreenFrameRate`、截图裁剪与 JPEG 质量降档、灵屏设置页
  - 2026-10-27 追加：**虚拟屏多开**（会话级多屏、宿主面按 owner 分槽、焦点镜像），设计见 `docs/虚拟屏多开.md`
- **工具档**：`LocalToolProfile`、`LocalToolProfileStore`、`AgentToolSchemaCompactor.compact`、`AgentHistoryTrimmer`、`AgentToolDirectory`
  - 现在只剩 `FULL` / `CHAT` 两档（顶栏胶囊：全部工具 / 纯聊天）
- **MCP 工具 schema 压缩**：`AgentToolSchemaCompactor.compactMcp`（第三方 server 描述可达约 190KB）
- **记忆热区自动压缩**：`memoryAutoCompactEnabled` / `AgentMemoryRepository.maybeCompact`
- **会话分类、会话导航面板、工作区面板、提示词工坊、@ 引用、斜杠命令**等 UI 能力

## 3. 本轮（2026-10-28）已移除的本地改动

| 移除项 | 说明 |
|---|---|
| **上下文压缩轮数上限** | 原先「一个对话最多自动压缩 3 轮，压满即锁死、只能新开对话」。已整体删除：`AgentContextPolicy` 文件、`MAX_COMPACTION_ROUNDS`、`isCompactionLocked()`、`CONTEXT_COMPACTION_LIMIT`、`compactionRound` / `_eta_compaction_round` 字段、「已压 N/3 轮」胶囊与发送前拦截 |
| **上下文超限自动降档阶梯** | 原先 `LocalToolProfile.leaner()`（FULL→LIGHT→STANDARD→MINIMAL）在超限时自动降档重试。已整体删除：`leaner()`、中间四档、`AgentModelRetry.downgradeRequest`、`AgentEvent.ContextOverflowDowngrade`、`TokenUsageRecorder.recordDowngrade`、用量页「降档重试」统计 |
| **图片预览档** | 先按「与上游一致」把 `AgentModelImageEncoder.previewProfile` 从 1920 / 3,686,400 px 改回上游的 512 / 256,000 px；随后按「输入框图片预览模糊」的反馈，当天再放宽到 **1440 / 2,073,600 px（1440×1440）/ `PREVIEW_JPEG_QUALITY = 86`**。该副本只服务聊天与输入框显示，发给模型的仍是原图（`attachmentProfile`：JPEG 95、不缩放），所以放宽不影响模型输入与请求体 |

### 现在的上下文策略（与上游一致）

- `AgentContextSession` 内 `private companion object { const val TRIGGER_RATIO = 0.85 }`
- 用量达到窗口 85% 触发一次自动压缩；**压缩次数无上限**，压完继续对话
- 超限仍报 `CONTEXT_OVERFLOW`（不可重试），提示压缩或新开会话

## 4. 本轮修复与新增（2026-10-28）

### 4.1 Robolectric 在本机跑不了（重要）

`nativeruntime-dist-compat-1.0.19.jar` 只发布三种平台产物：`linux/x86_64`、`mac/aarch64`、`windows/x86_64`。**没有 linux-aarch64**，所以在本机（arm64 Debian chroot）Robolectric 4.17 会在 `AndroidTestEnvironment.setUpApplicationState` 直接抛
`The Robolectric native runtime is not supported on Linux (aarch64)`，整类用例变红。

对策：`app/src/test/.../testsupport/EtaRobolectricTestRunner.kt`
- 继承 `RobolectricTestRunner`，在 `runChild` 前做平台判定，不支持则 `assumeTrue` 跳过
- 全仓 92 个 `@RunWith(RobolectricTestRunner::class)` 已统一替换为该 runner
- 支持的平台（x86_64 / macOS / Windows）行为不变

**含义**：本机的 `testDebugUnitTest` 只覆盖纯 JVM 用例，Robolectric 类一律 skipped。要真跑 UI/数据库用例，必须换 x86_64 环境或上设备。

### 4.2 root 身份下的可写性断言

以 root 运行时 `File.canWrite()` 恒为 true，「设只读再断言不可写」必然失败。
对策：`app/src/test/.../testsupport/PosixAssertions.kt` 的 `assertNotOwnerWritable()`，改看 POSIX owner-write 权限位。

### 4.3 其它按设计对齐的修正

- `PromptCustomization.fingerprint()` 不再把 `enabled` 计入：只切开关不应升版本（避免打乱请求前缀、破坏 provider 缓存）
- `SpeechText`：行内代码保留文字（只丢反引号）、不再 trim 句尾「。」、`_` 不再当非法符号
- `AgentWebTools` 内置搜索恢复多来源 fallback：DDG 被 202 挑战时继续尝试 Bing RSS，全失败报 `SEARCH_PROVIDERS_FAILED` 并带 `attempts` 明细；`WebResponseDecoder` 允许 `application/rss+xml` / `atom+xml` / `xml`
- `AgentLoop` 远端（MCP）工具不再参与本地参数预检：`remoteToolNames` 内的工具交给第三方服务器解释 schema

### 4.4 Linux 工具环境：能力检测误报与可选工具卸载（2026-10-28）

**修掉的存量问题**：Linux 工具环境页「环境能力 · 自动检测」把「基础命令」「Node.js / npm」「Kimi Code」报成「未安装」，而同页「可选工具」显示已安装，两套口径互相打脸。

三个独立成因，全部实测确认：

| 成因 | 实测证据 |
|---|---|
| App 进程 stat 不到 rootfs 里的符号链接 | rootfs 由 root 解包，链接的 SELinux 标签是 `app_data_file:s0`（无 App 的 MLS 类别 `c13,c258,c512,c768`）；`untrusted_app` 身份下 `ls -l` / `readlink` / `test -L` 对 `bin/sh`、`usr/local/bin/node`、`usr/local/bin/kimi` 全部 EACCES，而 `usr/bin/grep`、`usr/bin/tar` 等真实文件正常。对照实验：App 自己创建的链接带类别，可正常 stat |
| 绝对链接目标跨语义 | `usr/local/bin/node -> /opt/eta/node/26.8.1/bin/node` 在 chroot 内有效，在 App 进程里按宿主根路径解析，目标必然不存在 |
| 探测不随安装刷新 | `LaunchedEffect` 的 key 只有发行版、backend、环境版本、`apkAnalysisReady`，装完 profile 不会重测（Kimi 21:09 就绪、界面仍显示未安装） |

**修法**（`agent/terminal/LinuxCapabilityProbe.kt` 整文件重写）：

- `probe()` 改成 `suspend`，首选在目标 Linux 环境里跑一次 `command -v`：`InstallerShellRunner.run(environment = distribution.terminalEnvironment, linuxRootfsPath = rootfs)`，chroot 走 root、proot 走 proot，11 项能力合并成一条脚本，超时 20s，输出 `eta-cap:<key>=0/1`，只认全部 11 项都拿到
- 环境未就绪或探测失败回退静态判定，**只判真实文件与目录**：`usr/bin/{dash,bash}`、`opt/eta/node`、`opt/eta/uv`、`usr/local/lib/node_modules/@moonshot-ai/kimi-code/dist/main.mjs`、`opt/eta/apk-analysis/current/{jadx/bin/jadx,bin/apktool}`，Python 用 `opt/eta/python` 兜底
- 刷新：`LaunchedEffect` key 增加 `profileReady` 与 `probeToken`，安装/卸载成功后 `probeToken++`

设备侧验证：App 身份下静态判定的每个落点都命中（dash／grep／tar／opt/eta/* / main.mjs 全部可见，`opt/eta/apk-analysis` 确实 missing，与「未装」一致）；真实 chroot 内 `command -v` 解析出 `sh=/usr/bin/sh`、`node=/usr/local/bin/node`、`kimi=/usr/local/bin/kimi`、`python3=/usr/local/bin/python3`、`java=/usr/bin/java`、`jadx=MISSING`。

**新增能力：可选工具卸载**

- `LinuxPackageProfiles.kt`：`LinuxPackageSpec.uninstallScript`（Linux 内执行，null = 只撤 marker）+ `PackageProfileUninstallResult`（`NotInstalled` / `EnvironmentNotReady` / `BlockedByDependent` / `Uninstalled` / `Failed`）+ `LinuxPackageProfileInstaller.uninstall()`，与 install 共用同一把 mutex
- 依赖保护：`dependsOn` 反查，Kimi 仍就绪时拒绝卸载 Node，返回 `BlockedByDependent("kimi")`
- 卸载语义按安装方式区分：Python 真删 `/opt/eta/{python,uv}` 与 `usr/local/bin/{uv,uvx,python*}`；Node（Debian 归档）真删 `/opt/eta/node` 与 `usr/local/bin/{node,npm,npx}`；Kimi 删 `usr/local/bin/kimi` 与 `usr/local/lib/node_modules/@moonshot-ai`；APK 分析删 `opt/eta/apk-analysis` 与 5 个包装（JVM 保留）；**Alpine 的 Node 与两个发行版的 SSH 是包管理器装的，只撤销 marker**
- UI：已安装行右侧一个 32dp 图标按钮（`Icons.Rounded.Delete`，中性 `onSurfaceVariantActions` 着色，不用开关、不抢主色），点了弹破坏性确认框；`EtaPreferenceRow` 的动作区宽度上限是行宽 45%，所以 Kimi 行只放「启动/打开 + 卸载」，运行中的「停止」移到 `bottomAction`

### 4.5 自检 / 巡检 / 诊断与日志 / 顶栏菜单（2026-10-27 夜 → 10-28）

#### 功能自检页（`ui/screens/selfcheck/FeatureSelfCheckScreen.kt`）

| 改动 | 说明 |
|---|---|
| 删「③ 已内置功能」 | 52 条硬编码文案本身没有校验能力，占页面 81% 体积却贡献 0 条可验证结论（连 `STATE_BUILTIN` 一起删）。`total` 现在只算真实检测项 |
| 状态三态 + 一档 | `✔ 正常` / `✘ 待处理` / `● 未实测` / `● 系统不提供`；`problemCount` 只统计 `MISSING`，`unknownCount` 单列，摘要会点名未实测项，不再把「未实测」一律说成 Root |
| Root 改实测 | 首次进页面不跑 su（不弹授权框），只有点「重新检测」才执行 `su -c id -u`，4 秒超时；返回 `0` 才判正常，「su 文件存在」不再是依据 |
| 文本库体量拆分 | `agent_text_chunks` 全表 GROUP BY 单独走 `probeTextStore()`（独立 `LaunchedEffect`），列表先挂「读取中…」占位，权限组毫秒级先出 |
| 新增 4 项 | 电池优化白名单（`isIgnoringBatteryOptimizations`）、自启动（见下）、后台弹出界面（AppOps）、精确闹钟（`canScheduleExactAlarms`） |
| 跳转修复 | `ACTION_VIEW package:` 在本机**无处理者**（`pm query-activities` 返回空），改 `ACTION_APPLICATION_DETAILS_SETTINGS`；无障碍与通知使用权直达各自设置页；失败不再静默 |
| 共存包误报 | `contains(pkg)` 会把 `io.github.mangi.eta.beta` 的授权算到主包头上，改 `ComponentName.unflattenFromString(...)?.packageName ==` 精确比对 |
| DB 版本 | 文案不再写死「预期 28」，改引用 `EtaDatabase` 里的 `ETA_DB_SCHEMA_VERSION`（当前 29） |

**自启动的实证链路（新增）**：`core/BootReceipt.kt` 落 `files/boot-receipt.json`（`lastAt` / `installAt` / `alarmRescheduled` / `receivers`），`DiagnosticsPatrolReceiver` 的开机分支在 `DiagnosticsPatrolAlarm.refresh()` 之后写一份。
判定：接收器未被禁用 **且** `lastAt >= installAt` **且** `alarmRescheduled=true` → `✔`；否则 `● 未实测`，detail 写明「重启一次后才会变绿」。
实测（2026102719）：`{"lastAt":1793115549803,"installAt":1793115543455,"alarmRescheduled":true,"receivers":1}`，该项已绿。

**为什么「后台弹出界面」标成 `● 系统不提供`**：ColorOS 的 op 名是 `android:background_start_activity`，而 `AppOpsManager.checkOpNoThrow(String, …)` 在**客户端**先查 op 名→op code 静态表，私有字符串不在表里直接抛 `IllegalArgumentException`；shell 侧 `cmd appops get` 走服务端表才读得到 `allow`。App 永远拿不到真值，点多少次「重新检测」都不变 —— 所以拆出这一档，摘要改成「运行条件就绪（1 项系统不提供读数）」，不再永久挂「1 项未实测」。

#### 极致自动排查（`core/DiagnosticsPatrol.kt`）

| 修的判定 | 原因（都有实测证据） |
|---|---|
| 崩溃增量改比时间 | 原来比「份数」，而 `CrashRecorder` 只留 10 份：满仓后新崩溃顶掉最旧的、份数恒为 10 → 新崩溃永远漏报（10-27 12:15 那次被判「本次没有新增」）。改比最新崩溃文件 mtime，结论 JSON 增 `crashLatestAt` |
| 日志项加时间窗 | 原来统计整个 logcat 缓冲的 `FATAL EXCEPTION`，已修好的旧崩溃每轮复报（同一份报告里「日志抓到 FATAL」与「崩溃本次无新增」互相打脸）。新增 `filterSince()` 按 `logcat -v time` 行首时间戳过滤，起点 = 上次巡检时间，首次取 6 小时前 |
| 账目 0 次降噪 | `BehaviorAudit` 的「0 次」大多是本周期没用过，不再产生 `NOTICE`，只留指标 |
| 无障碍判定 | 同上一条前缀误报，改组件级比对 |
| 附录日志 | `StringBuilder.append(List)` 走 `toString()`，120 行挤成一行逗号串；改 `joinToString("\n")` |

#### 「导出诊断包」+「崩溃记录」合并成「诊断与日志」

- 新页 `ui/screens/diagnostics/DiagnosticsCenterScreen.kt` + `AppRoute.DiagnosticsCenter`；关于页两行收成一行，摘要随崩溃条数变化
- 一次修掉两个 bug：`DiagnosticsBundle.window()` 的正则缺 `RegexOption.MULTILINE`，诊断包里「实际覆盖」恒为「无时间戳」（而日志里明明有 `10-27 18:18:24.721`）；日志超 2 MB 时 `take()` 保留的是**最旧**的前段，改 `takeLast()` 保尾
- 分享崩溃记录必须用 **Activity context + `FLAG_ACTIVITY_NEW_TASK`**：原来 `applicationContext.startActivity` 且无 NEW_TASK，抛 `AndroidRuntimeException` 被 `runCatching` 吞掉，现象是「按钮闪一下、分享面板不弹」，复现时 `logcat` 里没有任何 `CHOOSER` 启动记录
- 崩溃逐份「分享」、清空走破坏性确认，都在 `EtaPreference.endActions` 里用文字按钮（就地执行不带右箭头）

#### 顶栏「⋯」菜单加 Kimi Code

`kimiWebLabel` / `onLaunchKimiWeb` 从 `AgentAppRoot` 一路透传到 `TopBarOverflowMenu`，但**函数体从未使用**（死参数，所以菜单里一直看不到 Kimi）。已接上：未运行显示「启动 Web UI / 安装 Kimi Code / 重试」，运行中显示「停止 Kimi Web」，只占一个位置。
同时把「按下标硬分发」换成「项 + 动作成对」（`rows: List<Pair<DropdownItem, () -> Unit>>`），避免以后插项串位。

#### strings.xml 清理

累计删 150 条零引用（2304 → 2154，仍剩 249 条零引用待清）：20 条诊断/崩溃/检查更新文案、16 条 `data_backup_restore_*`（**「从整机备份还原」在代码里没有实现**）与 `conversation_*`、5 条 `chat_queue_*`、109 条 `page_*`（批量提取工具产物，名字带 6 位十六进制后缀，`page_ready_7cca0c` 的内容甚至是 `} 已就绪`）。
判定口径：Kotlin `R.string.X`、其他 XML `@string/X`、全项目文本出现，三者都为 0 才删；删完跑 `:app:processDebugResources` 确认无断引。

#### 已知遗留（下一轮接手先看）

| 遗留 | 状态 |
|---|---|
| 检查更新 | `AppUpdateChecker` 零引用、`strings` 相关文案已删；fork 没有 Release（`releases/latest` 实测 404）。要接回需先发 Release |
| 降档阶梯 | 事件 `AgentEvent.ContextOverflowDowngrade`、`AgentAppState` 的 notice 字段、`AgentAppRoot` 传参、菜单降档项**四处都已移除**；日后实现要一起重建 |
| 后台弹出界面 | App 侧读不到，只能靠人工确认，或改成「实证」（记录一次后台成功拉起界面） |
| 崩溃即取证 | `CrashRecorder.capture()` 仍会调 `DiagnosticsPatrol.captureNow()` 在崩溃路径跑全量巡检，会留半截产物（实测 `Eta-巡检-20261019-144557.json` 无对应 md） |
| 崩溃栈可读性 | release 无 `mapping.txt`，栈全是 `tt2.c(r8-map-id-…:3)`，诊断包里也没有映射文件 |

### 4.6 会话内存：启动只加载选中会话（#006 OOM 修复，2026-10-28）

> 完整记录（症状、三条 dropbox 证据、修复清单、遗留）：`记录/Bug-006-会话内存OOM-20261028.md`

**症状**：`java.lang.RuntimeException: Cannot create an instance of class oa`（`ViewModelProvider` 反射构造，OOM 被包成 `InvocationTargetException`），崩在 `EtaDatabase` 的 Room DAO 读取路径，`Caused by: OutOfMemoryError` 落在 `AbstractStringBuilder.<init>`。跨 `2026102715 / 2026102720 / 2026102726` 三个版本复现，进程跑 38～47 分钟后炸。

**根因（实测量化）**：`AgentConversationStore.loadSnapshot` 启动时把**全部会话**的消息与 checkpoint 一次性 `restoreText` 还原。

| 项 | 实测值（2026-10-28 的 eta.db，142 MB） |
|---|---|
| 会话 / 消息 / 分块 | 46 个 / 19,963 条 / 6,099 块（`agent_text_chunks` 92.88 MB） |
| 消息还原字符 | 74.9 M |
| checkpoint 还原字符 | 20.9 M |
| 合计常驻 | **95.8 M chars ≈ 192 MB（UTF-16 上限）** |

与原机 `dumpsys meminfo` 的 `Dalvik Heap Alloc 192,148 KB` 几乎逐位吻合。每次保存 / 重载都会再建一份 Snapshot（旧的那份仍被 StateFlow 引用），瞬时翻倍 → 撞 512 MB largeHeap 上限。崩点的 `StringBuilder(764,928)` 只是压垮的那一刻，不是元凶。

**修复**（只改存储与状态装配，不动 UI）：

- `AgentConversationStore.Snapshot` 新增 `partiallyLoadedConversationIds`（占位会话集合）与 `previews`（会话列表摘要）
- `loadSnapshot` 只对 `selected` 与 `misalignedConversationIds()`（sort_index 有空洞、必须整段重写的会话）做全量还原，其余只放元数据占位
- 新增 `AgentConversationStore.loadConversation(context, id)`；`AgentAppState.selectConversation` 触发按需补载，补载完成替换占位状态并保留草稿 / 待发送图片 / 排队消息 / 编辑态
- 保存路径：`AgentConversationPersistence` 新增 `markLoaded()` / `markDropped()`；`saveChanges` 对占位会话**只写元数据**，并把它们排除在 `stored.keys - current.keys` 的删除判定之外（否则空列表会清空库内历史）
- `updateConversation` 对占位会话只接草稿类瞬时字段；`recoverRuntimeRuns` 在写入前 `awaitConversationLoaded`
- 新增 DAO 查询 `ConversationDao.misalignedConversationIds()`

**效果**：启动常驻 192 MB → 当前选中会话 ≈ 0 MB；最坏情况（选中最大的 17.7 M chars 会话）≈ 35 MB。

**产物**：`/storage/emulated/0/MT2/mcp/产物/诊断包/Eta-OOM修复-2026102801.apk`
11,786,572 B，md5 `f8f4ffc16be56e81a36819061402ff4b`，v1/v2/v3 校验通过。**未安装**，等用户指令。

**遗留 / 注意**：

- 切换会话时历史有约 0.1～0.3 s 的补载窗口；这期间发送会被挂起，等加载完成再重放
- 图片仍以 base64 常驻（`conversation_messages.images` 60.99 M chars）：单个巨型会话最坏仍要 30 MB+。若还要继续压，下一步是把图片落到文件、库里只存路径
- Robolectric 用例在本机一律 skipped，这次改动**没有自动化用例覆盖**，只能上设备实测

### 4.7 自动任务页拆成「列表开关 + 二级详情」（#003，2026-10-28）

- 现象：任务卡的 summary 直接渲染 `task.prompt`，`EtaPreferenceRow` 只设 `heightIn(min = RowMinHeight)`、不设上限，长描述把卡片线性撑高。
- 组件层：`EtaPreferenceRow` / `EtaPreference` / `EtaSwitchPreference` 新增 `summaryMaxLines: Int = Int.MAX_VALUE`（默认不限，只有显式传值才约束），summary 同时用 `TextOverflow.Ellipsis`。
- 页面层：`AgentTasksScreen` 拆两段 —— 列表页只剩 `title + 触发摘要(1 行) + EtaSwitch + 进入箭头`，行高固定 52dp；prompt 全文、状态、触发详情、最近运行、三个操作按钮全部移入新增的 `AgentTaskDetailPage`（`MiuixScaffoldPage(compactTitle = true)`，整页 LazyColumn 滚动）。
- 详情页 `task` 每次从 `observeTasks()` 按 id 取，任务被删自动回落列表；`execute()` 增加 `onSuccess` 回调用于删除后返回。
- 新增字符串：`automation_task_enabled`、`automation_task_enabled_summary`、`automation_task_prompt_label`。
- 验证：`compileDebugKotlin` exit 0；本机 Robolectric 全 skipped，无自动化覆盖。未出包、未装机。
- 详细记录：`记录/Bug-003-自动任务卡片与二级页-20261028.md`。

### 4.8 会话分类归属：新会话掉进「未分类」（#002，2026-10-28）

- 现象：用户每次出包覆盖安装后，**新建**的会话出现在「未分类」，并怀疑「在当前分类下新建」失效。
- 主因（与升级无因果关系，升级只是让它更容易命中）：`AgentAppState.allocateConversationId` 分配 id 后立刻
  `setConversationCategory(id, ...)`，走的是 `UPDATE conversations SET category_id = ? WHERE id = ?`，
  可这一行**还没插入**（首次发送时才由持久化写入），UPDATE 影响 0 行后静默失败，紧接着 INSERT 写回 `NULL`。
  内存里分类是对的，所以界面当场看不出问题，进程一重启（覆盖安装）就按库渲染成未分类。
  写入先后没有顺序保证（`scope.launch` vs `scope.async(IO)` + `join`），首启 IO 更慢，失败概率更高。
- 次因：`createConversationInCategory` 用 `conversationCategories.any { … }` 过滤待定分类，
  分类表未就绪时（升级后首启、备份重载途中）会直接把新建意图丢掉。
- 修复：归属改为随会话行落库 ——
  `persistConversations` 传 `conversationCategoryIds` → `AgentConversationPersistence.save(categoryIds)` →
  `AgentConversationStore.saveChanges` 构造实体时取该映射；`allocateConversationId` 只更新内存映射；
  `createConversationInCategory` 只校验非空。
- **第一轮误判已回滚**（避免后续接手者重复走）：曾按「整行 upsert 抹列」加了
  `insertConversations` 的库内兜底 + `ConversationCategoryAssignment` 投影、`ConversationCategoryJournal`
  冗余快照与启动对账、`EtaFullBackupArchive.preservedOnRestore`，三项全部删除，
  `ConversationDao` / `ConversationEntities` / `EtaFullBackupArchive` 已回到 HEAD 状态。
- 保留：备份补分类表 —— 实测 `Eta-自动备份-20261028-092139`（163 MB）里有 21 条非空 `categoryId`
  却**没有** `conversationCategories` 字段，恢复后归属必然悬空。现在导出带该字段、导入时整体替换分类表
  （旧包无此字段 → 保留本机分类），并校验 id 非空/不重复、名称非空。
- 验证：`compileDebugKotlin` 与 `compileDebugUnitTestKotlin` 均 exit 0。
- **真机通过（2026-10-28 20:44，versionCode 2026102814）**：在「测试」分类下新建会话发「你好」，
  库里该行 `category_id = f23b6626…`（测试分类）✔；51 个会话只剩 1 条无归属，是 `archive-` 前缀的
  运行时归档行（设计如此）。另：2026102814 的 DEX 字符串池与回滚后源码逐条一致，说明装机包已含本修复。
- 详细记录：`记录/Bug-002-会话分组丢失-20261028.md`（含真机库实测表与去留表）。

## 5. 验证现状（2026-10-28 实测）

```
./gradlew :app:compileDebugKotlin --offline     → BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest --offline      → BUILD SUCCESSFUL
  total=1489  skipped=92  failures=0  errors=0

./gradlew :app:compileDebugKotlin --offline --rerun-tasks
  → BUILD SUCCESSFUL（Linux 工具环境改动后全量重跑，2026-10-28）

./gradlew :app:processDebugResources --offline  → BUILD SUCCESSFUL（strings 清理后逐次复验）
./gradlew :app:compileDebugKotlin --offline     → BUILD SUCCESSFUL in 1m17s（4.5 全部改动后）
  ⚠ 4.5 只跑了编译，没有重跑 testDebugUnitTest；下次动到 runtime/db 时补跑

./gradlew :app:compileDebugKotlin --offline     → BUILD SUCCESSFUL（4.6 #006 修复，三轮：首轮报 AgentAppState:481 取错 handoff 字段，修后通过）
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --offline
  → BUILD SUCCESSFUL（4.8 #002 分类归属修复；第一轮误判改动回滚后复验一次）
./gradlew :app:assembleRelease --offline        → BUILD SUCCESSFUL in 2m5s（versionCode 2026102801，产物 v1/v2/v3 重签）
./gradlew :app:testDebugUnitTest --tests '*AgentConversationStoreTest*' --tests '*AgentConversationPersistenceTest*'
  → tests=15 与 tests=6 全部 failures（Robolectric native runtime 不支持 linux-aarch64，用例没进去，不能当结论）
```

修改前同一命令：`1481 tests / 14 failed / 92 skipped`；更早的全量状态是 `1967 tests / 491 failed`（其中绝大部分为 native runtime 不可用）。

## 6. 构建与交付约定（沿用既有规则）

- 构建环境：Debian chroot，`sdk.dir=/workspace/android-sdk`，签名用 `local.properties` / 环境变量里的 keystore
- **禁止自动安装/卸载应用**：出包后必须等用户明确指令再装
- 产物目录按用户指定位置放置（不要把 APK 写进源码树）
- 不要主动 `git commit` / `git push`，除非用户明确要求

## 7. 接手时的最小检查清单

1. `git status --short | head` 确认工作区改动规模（不该是干净的）
2. `git log --oneline -3` 与本文档第 1 节比对，判断基线是否已前移
3. 需要判断「上游有没有」时：`git grep -n '<关键字>' origin/main -- '*.kt'`
4. 改完跑 `:app:compileDebugKotlin` + 定向 `--tests '*XXXTest'`；注意本机 Robolectric 类会 skipped，不要误判为「没覆盖」
5. 编译报错但不在你改的文件里：先 `git diff <该文件>` 看是不是**另一个会话正在改**（10-27 夜曾遇到 `AgentAppState` / `AgentAppShell` 的降档管线在编译期间被改动，只能做「让编译过」的最小清理，别顺手重写别人的半成品）
6. 判断某个「看起来没做」的功能：先 grep 参数是否只是透传未使用（Kimi 菜单就是透传三层却没人用），再看 `git show HEAD:` 与工作区的差异
