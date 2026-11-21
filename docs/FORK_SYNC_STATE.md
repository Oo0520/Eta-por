# 分支同步状态（相对上游 Mangi-11/Eta）

> 最后更新：2026-11-21。基线：上游 `5e048c5a`（v3.4.4-61）。
> 目的：给后续会话一张「哪些是上游自带、哪些是本分支独有、哪些已废弃」的表，
> 避免重复实现或把上游行为当成自研。

## 一、结论速览

- 本分支 = 上游最新 + 我方增量；所有增量都在 `feat/*` 上单独提交，可逐个对比。
- **上游从 3.3.5 到 3.4.4 已自带**：子智能体并发、后台虚拟屏 + 顶部胶囊、多会话并行、
  对话分支、会话用量卡、流体云 / Live Updates、Gemini/MiniMax/Mimo/DeepSeek 语音、
  RaTeX 公式、定时任务引擎、AI 生图、Vertex AI、工作区文件、工具逐项开关。
  → 这些**不再重复实现**。
- 已移植到上游基线：极致自动排查（诊断 + 巡检页）、提示词工坊（内核 + 接线 + 页面）、
  MCP schema 压缩、上下文构成估算、灵屏多开四层（槽位表 / 按 owner 资源 / 工具带 owner）。
- 明确废弃：`breeno` 接管（上游 11-16 已移除小布与超级小爱接管）、`LocalToolProfile` 档位
  （与上游工具逐项开关重复）。

## 二、已移植（可回归）

| 能力 | 入口 | 关键约束 |
|---|---|---|
| 诊断 / 巡检 | 设置 → 工具 → 诊断与巡检 | 报告目录优先共享路径，不可写则回退 `getExternalFilesDir/diag`；无常驻进程，仅一次闹钟 |
| 提示词工坊 | 设置 → 工具 → 提示词工坊 | 角色会话不生效；空配置不产生任何 system 消息（保护请求前缀缓存） |
| MCP schema 压缩 | 运行时自动 | 只截 description，结构（type/enum/required/anyOf）不动 |
| 上下文构成估算 | 内核（未接 UI） | 与 `AgentHistoryTrimmer.estimateTokens` 同一套口径 |
| 灵屏多开 | 内核 | owner = 会话 id，上限 2 块屏，到顶回退主屏；单 owner 行为与上游一致 |

## 三、待做（按优先级）

1. **审批卡**：判定器 + 模型/落盘已就位；`AgentConfirmGuard`（拦截队列 / 通知 / approve·reject）
   与 `AgentConfirmActivity` 未做，需装机联调。
2. **上下文构成的面板接线**：把估算结果塞进上游的用量浮层（`ChatStatsPopover` 一带）。
3. **会话分类 / Markdown 卡片 / 全量备份 / Linux 能力探测 / 自检页**：未开始。
4. **灵屏接管与浮窗**：需要触摸注入与 Surface，必须实机验证。

## 四、上游已在、不要再做

| 曾自研 | 上游对应 |
|---|---|
| 子智能体并发 | `agent/subagent/*`（3.3.5） |
| 虚拟屏 + 顶部胶囊 + 画布分流 | `agent/virtualdisplay/*`、`overlay/VirtualDisplayStageCard`（3.4.0） |
| 多会话并行 | 3.3.4 |
| 对话分支 | `ConversationBranch*`（3.4.1） |
| 会话用量 / 性能卡 | `ChatStatsPopover`（3.4.1） |
| 流体云 / 通知胶囊 | Live Updates（3.4.3） |
| 语音（识别 + 播报 + 浮窗） | `agent/voice/*`（3.4.4 → 11-21） |
| 公式渲染 | RaTeX（11-17） |
| 定时 / 自动任务 | `agent/scheduler/*`（11-19，含 Cron、熔断、静默运行） |
| AI 生图 / Vertex | `agent/imagegen/*`（11-19） |
| 工具逐项开关 | `ui/screens/tools/AgentToolsScreen.kt` + `Prefs.disabledTools` |

## 五、纪律

1. 动跨层签名时，`grep` 范围必须包含 `app/src/main`、`app/src/test`、`app/src/androidTest`。
2. 引入新符号（图标 / 组件 / API）前，先在仓库内 `grep` 证明确有人在用，再写。
3. 搬家（复制 fork 文件）前先查同包重复顶层声明：
   ```bash
   grep -hE "^(internal )?(data class|class|object|enum class|fun) [A-Za-z]" <目标包>/*.kt | sort | uniq -d
   ```
4. 每块切片一个提交、一次 `PR Check`；红了先看耗时（3~5 分钟挂多为 Kotlin 编译期，10 分钟以上多为 lint/单测/打包）。
