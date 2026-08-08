# FaultPilot T 型第 1 天细分学习计划

## 1. 今日定位

今天完成 T 型学习路线的“横向全景”：先理解整个系统如何运行、7 类故障如何共用同一条诊断主链，再用一个 CPU Incident 验证这条链确实能够跑通。

今天不逐行研究 Arthas，也不连续注入 7 个故障。JVM 的源码级纵向深挖安排在第 2 天。这样可以先回答“系统为什么这样组织”，再回答“某一类故障具体怎样定位到代码”。

本文是新增计划，不替代以下已有文档：

- [FaultPilot 五天项目掌握计划](FaultPilot-五天项目掌握计划.md)
- [FaultPilot 第 1 天细分学习计划](FaultPilot-第1天细分学习计划.md)
- [FaultPilot T 型五天项目掌握计划](FaultPilot-T型五天项目掌握计划.md)

计划总用时约 5 小时。

## 2. 今日完成标准

今天结束时，应当能够独立完成以下任务：

1. 画出 Browser、FaultPilot、业务服务、Prometheus、Arthas、PostgreSQL、Redis 和 GLM-5 的运行关系。
2. 解释便宜的 Baseline 全域采集与专业 Agent 按需调度的区别。
3. 从 `POST /api/incidents` 讲到最终 `DiagnosisDecision`，说清每个阶段的输入、输出和责任类。
4. 用同一个模板讲清 7 类故障对应的 Agent、直接证据、补强证据、反证和 CauseCode。
5. 独立跑通一个使用模糊描述的 CPU Incident，并根据页面结果判断是否成功。
6. 解释 `SUPPORTED`、`CONFIRMED`、`CONTRADICTED` 和 `INCONCLUSIVE` 的差异。
7. 用两分钟准确说明当前哪些能力达到源码级，哪些仍是组件级，以及缺少什么生产证据。

## 3. 总时间表

| 时间 | 学习单元 | 核心动作 | 必须产出 |
|---|---|---|---|
| 00:00-00:20 | 环境与安全基线 | 检查服务、模型配置存在性和场景状态 | 环境检查表 |
| 00:20-00:50 | 运行时组件架构 | 阅读部署与配置，梳理数据流向 | 一张架构图 |
| 00:50-01:30 | 统一 Incident 主链路 | 跟踪入口、图节点、模型角色和 Gate | 一张统一流程图 |
| 01:30-02:30 | 7 类故障源码矩阵 | 从枚举、场景、路由与 Gate 交叉整理 | 七类故障矩阵 |
| 02:30-03:20 | CPU 代表性实验 | 注入、观察指标、提交模糊描述、恢复 | 一个完整 Incident 记录 |
| 03:20-04:10 | 可信等级与能力边界 | 阅读 EvidenceGate，分析成功和降级条件 | 证据规则表、边界说明 |
| 04:10-04:40 | 绘图与口头复述 | 闭卷重画架构和主链 | 两张图、两分钟讲稿 |
| 04:40-05:00 | 闭卷验收与环境恢复 | 回答问题、恢复故障、核对状态 | 验收答案、干净环境 |

## 4. 开始前的规则

1. 使用一个独立的 PowerShell 窗口保存实验变量，不要在实验中途关闭。
2. 不在笔记、截图、命令历史或 Git 中记录 API Key、模型租户地址和登录密码。
3. 只检查 `$env:QWEN_API_KEY` 是否存在，不输出它的值。
4. 今天只运行一个 CPU 场景；其他 6 类先通过源码和已有验证记录建立横向认识。
5. 页面自然语言不是事实来源，判断结果时优先看 Evidence、Agent Steps、Critic、EvidenceGate 和 Event stream。

## 5. 00:00-00:20：建立环境与安全基线

### 5.1 检查基础设施

在项目根目录执行：

```powershell
docker compose --env-file .env -f deploy/docker-compose.yml ps
```

应至少看到以下容器处于运行状态：

- `faultpilot-postgres`
- `faultpilot-prometheus`
- `faultpilot-redis`
- `faultpilot-redis-exporter`

### 5.2 检查三个 Java 服务和 Prometheus

```powershell
Invoke-RestMethod "http://localhost:8080/actuator/health"
Invoke-RestMethod "http://localhost:8081/actuator/health"
Invoke-RestMethod "http://localhost:18082/actuator/health"
Invoke-WebRequest "http://localhost:9090/-/ready" -UseBasicParsing
```

预期端口：

| 端口 | 组件 | 预期结果 |
|---:|---|---|
| 8080 | `faultpilot-server` | `UP` |
| 8081 | `order-service` | `UP` |
| 18082 | `inventory-service` | `UP` |
| 9090 | Prometheus | Ready |
| 55432 | PostgreSQL | Docker 容器健康 |
| 56379 | Redis | Docker 容器健康 |

### 5.3 只检查模型配置是否存在

```powershell
[pscustomobject]@{
    ModelNameConfigured = -not [string]::IsNullOrWhiteSpace($env:MODEL_NAME)
    ModelUrlConfigured  = -not [string]::IsNullOrWhiteSpace($env:MODEL_BASE_URL)
    ApiKeyConfigured    = -not [string]::IsNullOrWhiteSpace($env:QWEN_API_KEY)
}
```

三个结果都应为 `True`。不要执行 `$env:QWEN_API_KEY` 来打印密钥。

### 5.4 检查实验服务是否干净

```powershell
$orderDiagnostics = Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics"

$inventoryDiagnostics = Invoke-RestMethod `
  -Uri "http://localhost:18082/api/inventory/internal/diagnostics"

$orderDiagnostics | ConvertTo-Json -Depth 6
$inventoryDiagnostics | ConvertTo-Json -Depth 6
```

开始前应确认 CPU、线程池、慢 SQL、数据库池、Redis 和依赖延迟场景均未激活。若仍有活动场景，先根据场景代码调用对应服务的 `recover-active` 接口。

### 5.5 填写环境检查表

```text
检查时间：
FaultPilot 8080：
order-service 8081：
inventory-service 18082：
Prometheus 9090：
PostgreSQL：
Redis：
模型三项配置是否存在：
活动故障场景：
```

本阶段验收：所有服务可用，模型配置存在，且没有遗留故障影响后续 CPU 实验。

## 6. 00:20-00:50：建立运行时组件架构

### 6.1 按顺序阅读

1. 根目录 `pom.xml`：确认 4 个 Maven 模块。
2. `README.md`：确认启动方式、外部模型和生产只读模式。
3. `deploy/docker-compose.yml`：确认 PostgreSQL、Prometheus、Redis 和 Redis Exporter。
4. `deploy/prometheus/prometheus.yml`：确认三个 Java 服务的抓取目标。
5. `faultpilot-server/src/main/resources/application.yml`：确认 Service Catalog、工具地址、模型配置和安全开关。
6. `faultpilot-server/src/main/java/com/astrayzjt/faultpilot/tool/http/ProductionDiagnosticToolsConfiguration.java`：确认生产工具是受控的只读工具。

### 6.2 建立组件职责表

| 组件 | 职责 | 主要数据流 | 是否直接作出诊断 |
|---|---|---|---|
| Browser | 提交 Incident、查看 Evidence 和事件 | HTTP/SSE ↔ FaultPilot | 否 |
| FaultPilot Server | 编排、工具注册、证据、模型角色、Gate、审计 | 调用所有外部组件 | 是，经过模型角色与本地 Gate |
| GLM-5 | Supervisor、Specialist、Diagnosis、Critic 的结构化推理 | OpenAI-compatible API ↔ FaultPilot | 提出和审查，不拥有最终证据权限 |
| order-service | 被诊断业务、订单故障实验、Actuator 指标 | 指标 → Prometheus；诊断数据 → FaultPilot | 否 |
| inventory-service | 下游依赖与超时实验 | 指标 → Prometheus；HTTP ← order-service | 否 |
| Prometheus | 周期抓取和查询指标 | PromQL → FaultPilot | 否 |
| Arthas | JVM 热点线程、等待线程和源码位置 | 受控命令 → FaultPilot | 否，只返回观察事实 |
| PostgreSQL | FaultPilot 状态、实验数据和只读数据库诊断 | JDBC ↔ FaultPilot/业务服务 | 否 |
| Redis | 缓存服务和诊断目标 | Redis 协议 ↔ 业务服务/FaultPilot | 否 |

### 6.3 画架构图

图中至少包含以下关系：

```text
Browser → FaultPilot：创建 Incident、查询结果、SSE
FaultPilot → GLM-5：规划、专业调查、诊断、审查
FaultPilot → Prometheus：PromQL 只读查询
FaultPilot → Arthas：白名单只读 JVM 命令
FaultPilot → PostgreSQL/Redis：预注册只读诊断
Prometheus ← order/inventory：Actuator 指标抓取
order-service → inventory-service：业务下游调用
FaultPilot → PostgreSQL：Incident、Evidence、Event、Trace 持久化
```

### 6.4 必须回答

1. 为什么 Prometheus 和 Arthas 只提供事实，不能直接给出 FaultPilot 的最终根因？
2. 为什么 GLM-5 能提出结论，但不能绕过 EvidenceGate？
3. 为什么业务服务与 FaultPilot 必须是独立进程？
4. 为什么 Trace 后端没有配置时，系统必须保留 `DATA_UNAVAILABLE` 或缺失证据？

## 7. 00:50-01:30：跟踪统一 Incident 主链路

### 7.1 阅读顺序

1. `incident/api/IncidentController.java`
2. `incident/application/IncidentService.java`
3. `orchestration/IncidentOrchestrator.java`
4. `triage/BaselineCollector.java`
5. `triage/RoutingAdvisor.java`
6. `orchestration/SupervisorPlanner.java`
7. `agent/runner/SpecialistAgentRunner.java`
8. `diagnosis/DiagnosisSynthesizer.java`
9. `diagnosis/DiagnosisCritic.java`
10. `diagnosis/EvidenceGate.java`

如果某个包路径与上面不同，使用下面的命令按类名定位，不要凭记忆猜路径：

```powershell
rg --files faultpilot-server/src/main/java | rg "IncidentOrchestrator|SupervisorPlanner|SpecialistAgentRunner|DiagnosisSynthesizer|DiagnosisCritic|EvidenceGate"
```

### 7.2 手写统一主链

```text
用户提交 service + 模糊 symptom
→ IncidentController 返回 202 Accepted
→ IncidentService 保存 IncidentSnapshot
→ IncidentOrchestrator 后台启动图
→ BaselineCollector 执行低成本全域探针
→ EvidenceService 保存结构化 Evidence
→ RoutingAdvisor 计算确定性路由信号
→ GLM Supervisor 选择需要深入调查的 Agent
→ Specialist Agent 循环选择受控工具并观察结果
→ GLM Diagnosis Synthesizer 提出 Proposal
→ GLM Critic 独立审查
→ 本地 EvidenceGate 校验证据引用与因果规则
→ PASS、修订、第二轮补证或结束
→ 保存 DiagnosisDecision 和 Event stream
```

### 7.3 重点区分：Baseline 不等于所有 Agent 都运行

`BaselineCollector` 会调用各领域中便宜、只读、限时的概览工具，用来避免用户描述错误时漏掉真实异常。它产生的是初筛 Evidence。

专业 Agent 只在 Supervisor 选中后运行，并可在有限步骤内调用更深入的工具。例如 CPU 异常时，基线可能读取 CPU、线程池、数据库池、依赖和 Redis 的概览，但专业阶段只调度 `JVM_AGENT` 去调用 Arthas。规划仍然决定了深度调查范围、成本和权限边界。

### 7.4 建立阶段责任表

| 阶段 | 权威输入 | 核心输出 | 是否调用模型 |
|---|---|---|---|
| 创建 Incident | 用户请求、Service Catalog | `IncidentSnapshot` | 否 |
| Baseline | 只读工具结果 | 初始 Evidence | 否 |
| RoutingAdvisor | EvidenceType | 带分数的 RoutingSignal | 否 |
| Supervisor | Incident、Evidence、RoutingSignal | Agent 计划 | 是 |
| Specialist | Incident、Evidence、工具目录 | AgentFinding、新 Evidence | 是 |
| Diagnosis | 本 Incident 的 Evidence 和 Finding | DiagnosisProposal | 是 |
| Critic | Proposal 与同一证据集 | DiagnosisCritique | 是 |
| EvidenceGate | Proposal、Critique、Evidence | EvidenceGateResult | 否 |
| Orchestrator | Gate 结果和轮数 | 结束、修订或 Follow-up | 否 |

### 7.5 找到循环边界

在 `IncidentOrchestrator` 中定位并记录：

- `MAX_ROUNDS = 2`
- Critic 要求 `REVISE` 时的修订路径
- Critic 或 Gate 要求补证时的 `FOLLOW_UP` 路径
- 远程模型超时或非法结构化响应时的 `DIAGNOSIS_INCONCLUSIVE`

本阶段验收：不看页面，也能按顺序写出至少 10 个主链节点，并说清哪些节点使用 GLM-5。

## 8. 01:30-02:30：整理 7 类故障源码矩阵

### 8.1 从四组源码交叉阅读

第一组，场景入口：

- `faultpilot-lab-order/.../fault/ScenarioCode.java`
- `faultpilot-lab-inventory/.../fault/ScenarioCode.java`
- 两个模块中的 `FaultScenarioManager.java`

第二组，证据与根因词汇：

- `common/domain/EvidenceType.java`
- `common/domain/CauseCode.java`
- `common/domain/AgentType.java`

第三组，路由：

- `triage/BaselineCollector.java`
- `triage/RoutingAdvisor.java`

第四组，可信规则：

- `diagnosis/EvidenceGate.java`

### 8.2 使用检索命令定位，不通读大文件

```powershell
rg -n "CPU_HOTSPOT|THREAD_POOL_EXHAUSTED|SLOW_SQL|DB_POOL_EXHAUSTED|REDIS_LATENCY|REDIS_CLIENT_POOL_EXHAUSTED|DEPENDENCY_TIMEOUT" `
  faultpilot-lab-order/src/main/java `
  faultpilot-lab-inventory/src/main/java

rg -n "JVM_CPU_HOTSPOT|JVM_THREAD_POOL_EXHAUSTED|DB_SLOW_QUERY|DB_POOL_EXHAUSTED|DEPENDENCY_TIMEOUT|REDIS_SERVER_LATENCY|REDIS_CLIENT_POOL_EXHAUSTED" `
  faultpilot-server/src/main/java
```

### 8.3 完成七类故障矩阵

| 场景 | 主要 Agent | 直接证据 | 独立补强证据 | 关键反证 | CauseCode | 当前定位层级 |
|---|---|---|---|---|---|---|
| CPU 热点 | `JVM_AGENT` | `PROCESS_CPU_HIGH` | `CPU_HOT_METHOD_FOUND` 或 `REPEATED_RUNNABLE_STACK` | `PROCESS_CPU_NORMAL` | `JVM_CPU_HOTSPOT` | 方法/源码行级 |
| 线程池耗尽 | `JVM_AGENT` | `THREAD_POOL_ACTIVE_AT_MAX` 或 `THREAD_POOL_QUEUE_GROWING` | `BLOCKING_TASK_FOUND` | `THREAD_POOL_NORMAL` | `JVM_THREAD_POOL_EXHAUSTED` | 阻塞线程/源码行级 |
| 慢 SQL | `DATABASE_AGENT` | `SLOW_SQL_FOUND` | `API_AND_SQL_TIME_CORRELATED` 或 `ABNORMAL_EXECUTION_PLAN` | 由其他域正常证据辅助隔离 | `DB_SLOW_QUERY` | SQL 指纹级 |
| 数据库池耗尽 | `DATABASE_AGENT` | `DB_POOL_PENDING_HIGH` 或 `DB_POOL_ACTIVE_AT_MAX` | `CONNECTION_HOLDING_QUERY_FOUND` | 由池恢复与其他域证据辅助隔离 | `DB_POOL_EXHAUSTED` | 连接池级 |
| Redis 服务端延迟 | `CACHE_AGENT` | `REDIS_COMMAND_LATENCY_HIGH` | `REDIS_SLOW_COMMAND_FOUND` 或 `REDIS_TRACE_LATENCY_CORRELATED` | `REDIS_COMMAND_LATENCY_NORMAL` | `REDIS_SERVER_LATENCY` | 组件级 |
| Redis 客户端池耗尽 | `CACHE_AGENT` | `REDIS_CLIENT_POOL_PENDING_HIGH` | `REDIS_COMMAND_LATENCY_NORMAL`，用于隔离服务端 | `REDIS_CLIENT_POOL_NORMAL` | `REDIS_CLIENT_POOL_EXHAUSTED` | 客户端池级 |
| 下游依赖超时 | `DEPENDENCY_AGENT` | `DOWNSTREAM_LATENCY_HIGH` | `SLOW_CHILD_SPAN_FOUND` | 由下游恢复与其他域证据辅助隔离 | `DEPENDENCY_TIMEOUT` | 服务级 |

### 8.4 每个场景都回答同一组问题

```text
用户可能看到什么现象？
Baseline 能先得到什么？
RoutingAdvisor 为什么给这个 Agent 正分？
专业 Agent 还需要调用什么工具？
什么 Evidence 能证明异常存在？
什么 Evidence 能解释异常为什么发生？
缺少补强证据时为什么只能 SUPPORTED？
当前最多定位到服务、组件、SQL、线程还是源码行？
```

### 8.5 本阶段验收

随机遮住矩阵中的任意三列，能够根据源码补回。特别要避免以下错误：

- 把场景注入接口当成诊断证据。
- 把 AgentFinding 的自然语言当成 Evidence。
- 把 `SLOW_SQL_FOUND` 直接等同于当前 Incident 已和该 SQL 时间相关。
- 把 Redis 服务端延迟和 Redis 客户端连接池耗尽混为一类。
- 在没有 Trace Span 时声称已经定位到下游具体接口或代码行。

## 9. 02:30-03:20：运行一个代表性 CPU Incident

### 9.1 注入 CPU 场景

在同一个 PowerShell 窗口执行：

```powershell
$run = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenarios/CPU_HOTSPOT/inject" `
  -ContentType "application/json" `
  -Body (@{
      ttlSeconds = 600
      startedBy = "t-shaped-day-1"
  } | ConvertTo-Json)

$runId = $run.scenarioRunId
$run | ConvertTo-Json -Depth 6
```

记录 `$runId`，等待两个以上 Prometheus 抓取周期：

```powershell
Start-Sleep -Seconds 15

Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics" |
  ConvertTo-Json -Depth 6
```

预期 `cpuHotspot=true`，并且 CPU worker 数大于 0。

### 9.2 独立验证 Prometheus 指标

```powershell
$query = [Uri]::EscapeDataString(
  'process_cpu_usage{job="faultpilot-lab-order"}'
)

$prometheusResult = Invoke-RestMethod `
  -Uri "http://localhost:9090/api/v1/query?query=$query"

$prometheusResult.data.result | ConvertTo-Json -Depth 8
```

这里验证的是“真实指标已升高”，不是“注入接口返回成功”。FaultPilot 不读取 `$runId` 来决定根因。

### 9.3 用模糊描述创建 Incident

打开 `http://localhost:8080/`，提交：

```text
Service：order-service
Symptom：订单接口最近明显变慢，但原因不清楚
Allow remediation：关闭
```

不要在描述中写 CPU。这样才能验证用户描述只是上下文，Baseline Evidence 和 Agent 调查才决定根因。

### 9.4 页面观察清单

每隔 20 到 30 秒刷新，记录：

```text
Incident ID：
最终 Incident Status：
Baseline Evidence：
Routing Signals：
Supervisor 选择：
Specialist 工具调用：
Diagnosis Proposal：
Critic verdict：
EvidenceGate status：
Primary Cause：
Missing Evidence：
模型失败事件：
```

完整成功的目标结果：

```text
Incident Status = DIAGNOSED
Diagnosis Status = CONFIRMED
Primary Cause = JVM_CPU_HOTSPOT
Evidence 包含 PROCESS_CPU_HIGH
Evidence 包含 CPU_HOT_METHOD_FOUND 或 REPEATED_RUNNABLE_STACK
Agent Tasks 只需包含 JVM_AGENT
Critic = PASS
EvidenceGate 没有缺失证据
```

若得到 `SUPPORTED`，说明异常信号成立，但热点方法或重复栈等独立补强证据缺失。若 Event stream 出现 `MODEL_CALL_FAILED` 并最终为 `INCONCLUSIVE`，说明这次运行卡在必要的远程模型角色，不等同于 CPU 指标或工具链没有生效。

### 9.5 立即恢复

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenario-runs/$runId/recover"

Start-Sleep -Seconds 10

Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics" |
  ConvertTo-Json -Depth 6
```

预期 `cpuHotspot=false` 且 CPU worker 数恢复为 0。

## 10. 03:20-04:10：理解可信等级与能力边界

### 10.1 阅读 EvidenceGate 的固定顺序

在 `EvidenceGate.evaluate` 中依次找到：

1. Proposal 是否引用了当前 Incident 之外的 Evidence ID。
2. Critic 是否存在以及 verdict 是否允许继续。
3. Proposal 是否处于 `READY_FOR_REVIEW`。
4. CauseCode 是否存在本地规则。
5. Proposal 是否引用了所需直接信号。
6. 最新 Evidence 中是否存在反证。
7. 是否引用了独立补强证据。
8. 最终返回 `SUPPORTED`、`CONFIRMED`、`CONTRADICTED` 或 `INSUFFICIENT`。

### 10.2 建立可信等级表

| 结果 | 含义 | 典型条件 | 是否代表程序失败 |
|---|---|---|---|
| `CONFIRMED` | 直接异常和独立因果补强均成立 | CPU 高 + 热点方法 | 否，最完整成功 |
| `SUPPORTED` | 主要异常成立，但独立补强缺失 | 慢 SQL 指纹存在，但无时间相关/异常执行计划 | 否，是诚实降级 |
| `CONTRADICTED` | 当前有效反证与候选根因冲突 | Proposal 为 CPU 热点，但最新 CPU 正常 | 否，结论被否定 |
| `INSUFFICIENT` | Proposal、Critic 或所需信号不足 | Proposal 未引用必要 Evidence | 否，证据不足 |
| Incident `INCONCLUSIVE` | 调查未形成可发布的可信报告 | 必要模型角色失败、两轮后仍不足 | 不一定，要看 Event stream |
| Incident `FAILED` | 编排或系统执行出现不可恢复错误 | 未处理异常或基础设施错误 | 是 |

### 10.3 当前定位能力

用下面四句话作为边界基线：

```text
JVM CPU 和线程池：Prometheus 指标加 Arthas，可定位到线程、方法和源码行。
数据库：可定位 SQL 指纹、慢查询累计状态和连接池状态；缺少 Trace、执行计划时不宣称完整因果。
Redis：可区分服务端命令延迟与客户端池等待；热点 Key、淘汰归因和调用方映射仍需更多证据。
下游依赖：可定位到服务级延迟；配置 Jaeger Span 后才能补强到具体接口和慢调用链。
```

### 10.4 两分钟说明模板

```text
FaultPilot 先通过全域低成本探针获得不依赖用户描述的基线事实，再由 RoutingAdvisor 和 GLM Supervisor 选择需要深入调查的专业 Agent。Agent 只能调用预注册只读工具，产生的观察必须记录成 Evidence。Diagnosis Agent 提出根因，Critic 做独立反思，本地 EvidenceGate 再检查 Evidence ID、直接信号、补强证据和反证。JVM 方向已经能借助 Arthas 定位到方法和源码行；数据库、Redis 和依赖方向根据已接入证据分别做到 SQL 指纹、组件或服务级，缺少 Trace 或执行计划时会返回 SUPPORTED 或 INCONCLUSIVE，不让模型补造事实。
```

## 11. 04:10-04:40：闭卷绘图与复述

### 11.1 闭卷画运行时架构图

关闭源码和本文，在 8 分钟内画出：

- 9 个核心组件。
- 业务数据流、指标流、诊断工具流、模型调用流和持久化流。
- 哪些箭头只读。
- 哪个组件拥有最终 Evidence 规则权威。

然后打开第 6 节检查遗漏。

### 11.2 闭卷画统一流程图

在 8 分钟内画出：

```text
Create → Baseline → Routing → Supervisor → Specialist
→ Diagnosis → Critic → Gate → Revise/Follow-up/Done
```

必须标出：

- 4 个 GLM-5 角色。
- 本地 RoutingAdvisor 和本地 EvidenceGate。
- 最多两轮调查。
- Evidence 是各阶段共享的事实边界。

### 11.3 完成两分钟复述

录音或计时复述一次。要求：

- 前 30 秒说明业务目标。
- 接下来 60 秒说明 Agent 与 Evidence 主链。
- 最后 30 秒说明 JVM 深度和其他领域边界。
- 不按技术栈列表背诵，不声称所有领域都已达到源码级。

## 12. 04:40-05:00：闭卷验收与恢复

### 12.1 闭卷问题

不看源码回答：

1. 用户描述错误时，系统为什么仍有机会找到真实异常？
2. Baseline 读取多个领域，为什么不等于四个 Agent 全部执行？
3. RoutingAdvisor 与 GLM Supervisor 分别解决什么问题？
4. Specialist Agent 为什么不能调用任意 shell 或自由拼接 SQL？
5. Diagnosis Proposal 为什么不能直接显示为最终 `CONFIRMED`？
6. Critic 的自我反思不合格后，系统可能走哪三条路径？
7. EvidenceGate 为什么必须保留为本地权威层？
8. CPU 热点从直接信号到源码行需要哪两类 Evidence？
9. `SUPPORTED` 与 `INCONCLUSIVE` 有什么区别？
10. 为什么累计 `pg_stat_statements` 中存在慢 SQL 不能证明它导致了当前 Incident？
11. Redis 服务端延迟与客户端池耗尽如何区分？
12. 没有 Jaeger Trace 时，下游依赖最多能可靠定位到哪一层？

至少答对 10 题，并且第 2、6、7、9 题必须正确，才进入第 2 天。

### 12.2 最终环境恢复检查

```powershell
Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics" |
  ConvertTo-Json -Depth 6

Invoke-RestMethod `
  -Uri "http://localhost:18082/api/inventory/internal/diagnostics" |
  ConvertTo-Json -Depth 6
```

确认所有场景均未激活。若 `$runId` 丢失但 CPU 场景仍在运行：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenarios/CPU_HOTSPOT/recover-active"
```

## 13. 今日提交物

- [ ] 环境检查表
- [ ] 一张运行时组件架构图
- [ ] 一张统一 Incident 流程图
- [ ] 一张 7 类故障矩阵
- [ ] CPU Scenario Run ID
- [ ] CPU Incident ID
- [ ] CPU 页面结果与 Event stream 摘要
- [ ] EvidenceGate 可信等级表
- [ ] 两分钟项目讲解稿
- [ ] 12 道闭卷题答案
- [ ] 已恢复的干净实验环境

## 14. 学习笔记推荐格式

每发现一个新点，只按下面格式记录，避免变成源码抄写：

```text
问题：这个类或阶段解决什么问题？
输入：它信任哪些数据？
输出：它新增了什么状态或证据？
边界：它明确不能做什么？
源码：类名 + 方法名。
实验：哪个 Event 或 Evidence 证明它运行过？
我的表述：用一句自己的话解释。
```

## 15. 与第 2 天的衔接

第 1 天完成的是 T 的横向部分：知道 7 类故障怎样进入统一架构，也知道每类能力的真实边界。

第 2 天只纵向深入 JVM 两条链：

```text
PROCESS_CPU_HIGH
→ Arthas hot threads
→ CPU_HOT_METHOD_FOUND
→ 方法和源码行
→ JVM_CPU_HOTSPOT / CONFIRMED

THREAD_POOL_ACTIVE_AT_MAX
→ Arthas waiting threads
→ BLOCKING_TASK_FOUND
→ 阻塞操作和源码行
→ JVM_THREAD_POOL_EXHAUSTED / CONFIRMED
```

完成本文验收后，下一步使用：

```text
开始 T 型第 2 天
```
