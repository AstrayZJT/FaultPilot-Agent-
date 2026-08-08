# FaultPilot 第 1 天细分学习计划

## 1. 今日目标

第一天只掌握一条主线：一个 Incident 如何从用户提交，经过基线采集、Agent 调查、诊断反思和证据门禁，最终成为诊断报告。

今天不学习所有故障类型，不修改业务功能。完成后应能：

1. 说清四个 Maven 模块的职责和运行关系。
2. 说清 Incident 主链路中的每个节点。
3. 将页面 Event stream 中的事件映射到源码方法。
4. 解释 Incident、Evidence、AgentTask、Proposal、Critique 和 Decision 的关系。
5. 使用数据库记录证明整个流程可持久化、可审计、可重放。

计划用时约 5 小时 30 分钟。可分上午、下午两段完成，但实验过程中不要关闭保存了 `$runId` 的 PowerShell 窗口。

## 2. 总时间表

| 时间 | 学习任务 | 实际操作 | 当场输出 | 完成 |
|---|---|---|---|---|
| 00:00-00:20 | 环境基线 | 检查 4 个服务和故障标志 | 环境检查结果 | [ ] |
| 00:20-00:50 | 模块地图 | 阅读根 POM、README、启动类 | 四模块职责表 | [ ] |
| 00:50-01:25 | 领域对象 | 阅读 8 个核心 record/enum | 对象关系表 | [ ] |
| 01:25-02:05 | HTTP 与编排图 | 跟踪创建接口和 LangGraph4j 节点 | 主调用链草图 | [ ] |
| 02:05-02:30 | 注入 CPU 故障 | 注入、等待、检查指标 | Scenario Run ID | [ ] |
| 02:30-03:10 | 创建 Incident | 使用模糊描述提交并观察页面 | Incident ID、最终结果 | [ ] |
| 03:10-03:55 | 事件映射源码 | 将 Event stream 逐项定位 | 事件与方法对照表 | [ ] |
| 03:55-04:25 | 持久化验证 | 查询 Incident、Evidence、模型调用 | 数据库查询截图或笔记 | [ ] |
| 04:25-05:05 | 绘图与复述 | 完成架构图、时序图和 3 分钟复述 | 两张图、一段讲稿 | [ ] |
| 05:05-05:30 | 闭卷验收与恢复 | 回答验收题、恢复场景 | 验收答案、干净环境 | [ ] |

## 3. 00:00-00:20：环境基线

### 3.1 检查服务

在 PowerShell 中执行：

```powershell
Invoke-RestMethod "http://localhost:8080/actuator/health"
Invoke-RestMethod "http://localhost:8081/actuator/health"
Invoke-RestMethod "http://localhost:18082/actuator/health"
Invoke-WebRequest "http://localhost:9090/-/ready" -UseBasicParsing
```

预期：

- 8080：FaultPilot，`UP`
- 8081：order-service，`UP`
- 18082：inventory-service，`UP`
- 9090：Prometheus Ready

### 3.2 检查实验服务是否干净

```powershell
Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics" |
  ConvertTo-Json
```

开始实验前应满足：

```text
cpuHotspot=false
threadPoolExhausted=false
slowSql=false
dbPoolExhausted=false
redisLatency=false
redisClientPoolExhausted=false
blockedActive=0
blockedQueue=0
```

如果 `cpuHotspot=true`，执行：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenarios/CPU_HOTSPOT/recover-active"
```

返回 404 只代表当前没有该活动场景，不需要重启服务。

### 3.3 记录

在自己的学习笔记中写下：

```text
FaultPilot：
order-service：
inventory-service：
Prometheus：
初始故障标志：
```

## 4. 00:20-00:50：建立模块地图

### 4.1 阅读顺序

1. 根目录 `pom.xml`
2. `README.md` 的 Modules、Build and Test、Connect a real business service
3. `faultpilot-server/src/main/java/com/astrayzjt/faultpilot/FaultPilotApplication.java`
4. `faultpilot-lab-order/src/main/java/com/astrayzjt/faultpilot/lab/order/OrderLabApplication.java`
5. `faultpilot-lab-inventory/src/main/java/com/astrayzjt/faultpilot/lab/inventory/InventoryLabApplication.java`
6. `faultpilot-evaluation/pom.xml`

### 4.2 必须形成的模块表

| 模块 | 端口或形式 | 职责 | 是否被诊断对象 |
|---|---|---|---|
| `faultpilot-server` | 8080 | Incident、Agent 编排、工具、诊断、安全与页面 | 否 |
| `faultpilot-lab-order` | 8081 | 订单业务、故障注入、Actuator 指标 | 是 |
| `faultpilot-lab-inventory` | 18082 | 下游库存服务、依赖超时实验 | 是 |
| `faultpilot-evaluation` | 测试模块 | 固定数据集与模式对比 | 否 |

### 4.3 此阶段只回答三个问题

1. 为什么 FaultPilot 和 order-service 必须是两个独立进程？
2. Prometheus 为什么不属于 FaultPilot 的 Java 模块？
3. `faultpilot-lab-order` 为什么既是实验服务，又能模拟正式业务接入？

## 5. 00:50-01:25：理解核心领域对象

### 5.1 阅读文件

位于 `faultpilot-server/src/main/java/com/astrayzjt/faultpilot/common/domain`：

1. `Incident.java`
2. `IncidentSnapshot.java`
3. `IncidentStatus.java`
4. `Evidence.java`
5. `AgentTask.java`
6. `DiagnosisProposal.java`
7. `DiagnosisCritique.java`
8. `DiagnosisDecision.java`

### 5.2 填写对象关系表

| 对象 | 谁创建 | 主要内容 | 谁消费 |
|---|---|---|---|
| `IncidentSnapshot` | `IncidentService` | 服务、症状、时间窗、是否允许处置 | 所有编排和 Agent 节点 |
| `Evidence` | Baseline 或 DiagnosticTool | 类型、来源、摘要、哈希、原始引用 | 路由、Agent、Diagnosis、Critic、Gate |
| `AgentTask` | `IncidentOrchestrator` | Agent 类型、目标、轮次、最大步骤 | Specialist Agent |
| `DiagnosisProposal` | Diagnosis Agent | 根因、支持/反对证据、补证请求 | Critic、EvidenceGate |
| `DiagnosisCritique` | Critic Agent | verdict、问题、建议 Agent | 修订、EvidenceGate、下一轮规划 |
| `DiagnosisDecision` | EvidenceGate 转换 | 最终可信等级和根因 | 页面、事件、处置模块 |

### 5.3 必须理解

- `IncidentSnapshot` 是调查期间稳定的输入，不让节点各自解释用户请求。
- `Evidence` 是事实载体，Agent 的自然语言总结不能代替 Evidence。
- Proposal 是候选结论，不是最终报告。
- Critique 是独立审查，不直接决定 `CONFIRMED`。
- Decision 是经过 EvidenceGate 后的权威结果。

## 6. 01:25-02:05：跟踪 HTTP 与编排图

### 6.1 阅读入口

1. `incident/api/IncidentController.java` 的 `create`
2. `incident/application/IncidentService.java` 的 `create`
3. `orchestration/IncidentOrchestrator.java` 的 `start`、`runGraph`、`buildGraph`
4. `orchestration/IncidentGraphState.java`

### 6.2 手写主调用链

```text
POST /api/incidents
→ IncidentController.create
→ IncidentService.create
→ 保存 Incident 与 Snapshot
→ IncidentOrchestrator.start
→ HTTP 返回 202 Accepted
→ 后台线程执行 LangGraph4j
→ load_incident
→ collect_baseline
→ supervisor_plan
→ dispatch_agents
→ synthesize_diagnosis
→ critique_diagnosis
→ evidence_gate
→ 结束或进入 follow-up
```

### 6.3 此阶段重点

- 找到为什么 HTTP 请求不会等待整个诊断完成。
- 找到图节点和边的注册位置。
- 找到 `REVISE` 与 `FOLLOW_UP` 两种回路的区别。
- 找到最大调查轮数 `MAX_ROUNDS`。
- 找到 PostgreSQL checkpoint 的配置位置。

## 7. 02:05-02:30：注入 CPU 故障

### 7.1 注入

保持同一个 PowerShell 窗口，执行：

```powershell
$run = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenarios/CPU_HOTSPOT/inject" `
  -ContentType "application/json" `
  -Body (@{
      ttlSeconds = 600
      startedBy = "day-1-learning"
  } | ConvertTo-Json)

$runId = $run.scenarioRunId
$run | ConvertTo-Json
```

TTL 使用 600 秒，是为了给 GLM-5 完整推理留出时间。

### 7.2 等待 Prometheus 抓取

```powershell
Start-Sleep -Seconds 20

Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics" |
  ConvertTo-Json
```

预期：`cpuHotspot=true` 且 `cpuHotspotWorkers` 大于 0。

### 7.3 查看 Prometheus 原始指标

```powershell
$promQl = [Uri]::EscapeDataString(
  'process_cpu_usage{job="faultpilot-lab-order"}'
)

$result = Invoke-RestMethod `
  -Uri "http://localhost:9090/api/v1/query?query=$promQl"

$result.data.result | ConvertTo-Json -Depth 6
```

记录当前 value。此处的意义是确认 FaultPilot 后续使用的 CPU Evidence 来自真实 Prometheus 指标，而不是根据注入接口直接判断。

## 8. 02:30-03:10：创建并观察 Incident

### 8.1 页面提交

打开 `http://localhost:8080/`，填写：

```text
Service：order-service
Symptom：订单接口响应变慢，但我不确定具体原因
Allow remediation：关闭
```

提交后立即记录 Incident ID。

### 8.2 为什么使用模糊描述

故意不写“CPU 过高”，用于验证：

- 用户 symptom 只是弱先验。
- BaselineCollector 先采集结构化指标。
- RoutingAdvisor 根据 `PROCESS_CPU_HIGH` 生成 JVM 正向路由信号。
- Supervisor 应只选择 `JVM_AGENT`，而不是全部 Agent。

### 8.3 观察页面

每 20 到 30 秒刷新一次，按顺序记录：

```text
Incident Status：
Evidence：
Routing Signals：
Supervisor 选择的 Agent：
Agent Steps：
Proposal：
Critic verdict：
EvidenceGate：
最终 Diagnosis：
```

正常预期：

```text
Incident Status = DIAGNOSED
Diagnosis Status = CONFIRMED
Primary Cause = JVM_CPU_HOTSPOT
Evidence 包含 PROCESS_CPU_HIGH
Evidence 包含 CPU_HOT_METHOD_FOUND
Agent Tasks 包含 JVM_AGENT
Critic = PASS
Pending action = None
```

`CPU_HOT_METHOD_FOUND` 应包含 `FaultScenarioManager` 的方法和源码行号，说明 Arthas 完成了从进程指标到代码位置的补强。

如果出现 `INCONCLUSIVE`，先查看 Event stream 中是否有 `MODEL_CALL_FAILED` 或 `MODEL_OUTPUT_INVALID`；不要直接把它解释为 CPU 场景失败。

## 9. 03:10-03:55：将事件映射到源码

打开 `IncidentOrchestrator.java`，按下面的表逐项定位：

| 页面事件 | 主要源码方法 | 该阶段做了什么 |
|---|---|---|
| `INVESTIGATION_STARTED` | `loadIncidentNode` | 加载 Incident，切换为调查状态 |
| `BASELINE_COLLECTED` | `collectBaselineNode` | 采集 CPU、线程池、依赖和缓存基线 |
| `ROUTING_SIGNALS_COMPUTED` | `RoutingAdvisor.derive` | 将 Evidence 转成 Agent 路由信号 |
| `INVESTIGATION_PLANNED` | `supervisorNode` | GLM Supervisor 选择 Agent |
| Agent Steps | `SpecialistAgentRunner.run` | Agent 多步选择工具并观察结果 |
| `AGENTS_COMPLETED` | `dispatchNode` | 等待本轮专业 Agent 完成 |
| `DIAGNOSIS_PROPOSED` | `synthesizeNode` | GLM Diagnosis Agent 生成 Proposal |
| `DIAGNOSIS_CRITIQUED` | `critiqueNode` | GLM Critic 独立审查 Proposal |
| `DIAGNOSIS_REVISED` | `reviseNode` | 根据 Critic 意见修订一次 |
| `EVIDENCE_GATE_DECIDED` | `gateNode` | 本地规则决定可信等级 |
| `FOLLOW_UP_REQUESTED` | `gateNode` | 返回 Supervisor 规划第二轮 |
| `DIAGNOSIS_COMPLETED` | `gateNode` | 保存报告并结束 Incident |

### 9.1 阅读顺序

不要一次跳到 Agent Prompt。严格按以下顺序：

```text
IncidentOrchestrator.buildGraph
→ loadIncidentNode
→ collectBaselineNode
→ supervisorNode
→ dispatchNode
→ synthesizeNode
→ critiqueNode
→ gateNode
```

### 9.2 每个方法只记录四件事

```text
输入是什么？
调用了谁？
保存了什么？
返回的 outcome 决定了哪条边？
```

## 10. 03:55-04:25：验证持久化与审计

将页面 Incident ID 放入变量：

```powershell
$incidentId = "替换为页面中的 Incident ID"
```

### 10.1 Incident 状态

```powershell
docker exec faultpilot-postgres `
  psql -U postgres -d faultpilot -P pager=off `
  -c "SELECT id,status,service_name,symptom,created_at,updated_at FROM incident_run WHERE id='$incidentId';"
```

### 10.2 Event stream

```powershell
docker exec faultpilot-postgres `
  psql -U postgres -d faultpilot -P pager=off `
  -c "SELECT id,event_type,created_at FROM incident_event WHERE incident_id='$incidentId' ORDER BY id;"
```

### 10.3 Evidence

```powershell
docker exec faultpilot-postgres `
  psql -U postgres -d faultpilot -P pager=off `
  -c "SELECT evidence_type,source,summary,collected_at FROM evidence_record WHERE incident_id='$incidentId' ORDER BY collected_at;"
```

### 10.4 Agent Task

```powershell
docker exec faultpilot-postgres `
  psql -U postgres -d faultpilot -P pager=off `
  -c "SELECT agent_type,status,investigation_round,max_steps,started_at,completed_at FROM agent_task_run WHERE incident_id='$incidentId' ORDER BY started_at;"
```

### 10.5 模型调用轨迹

```powershell
docker exec faultpilot-postgres `
  psql -U postgres -d faultpilot -P pager=off `
  -c "SELECT prompt_version,status,latency_ms,created_at FROM model_call_trace WHERE incident_id='$incidentId' ORDER BY id;"
```

回答：

1. 页面刷新后数据为什么还存在？
2. Event stream 为什么可以重放？
3. 如何判断是 Supervisor、Specialist、Diagnosis 还是 Critic 模型调用失败？
4. Evidence 为什么既保存 summary，又保存 `raw_data_reference` 和 `content_hash`？

## 11. 04:25-05:05：完成两张图和一次复述

### 11.1 架构图必须包含

```text
Browser
FaultPilot Server
GLM-5
Prometheus
Arthas
order-service
PostgreSQL
Redis
inventory-service
```

箭头上标明：HTTP、PromQL、Arthas command、SQL read-only、Redis diagnostics 或 model call。

### 11.2 Incident 时序图必须包含

```text
User
IncidentController
IncidentService
IncidentOrchestrator
BaselineCollector
Supervisor
JVM Agent
DiagnosticTool
Diagnosis Agent
Critic
EvidenceGate
Repository
```

### 11.3 三分钟复述模板

```text
1. 用户提交了什么。
2. 为什么接口先返回 202。
3. 系统怎样获得结构化基线证据。
4. 为什么只调度 JVM_AGENT。
5. JVM_AGENT 怎样从 CPU 指标追到具体方法。
6. Diagnosis 和 Critic 分别做什么。
7. EvidenceGate 为什么能给出 CONFIRMED。
8. 为什么生产只读模式没有 Pending action。
```

复述时不要逐个介绍技术栈，要围绕这一次 Incident 展开。

## 12. 05:05-05:30：闭卷验收与恢复

### 12.1 闭卷问题

不看源码回答：

1. `Incident` 与 `IncidentSnapshot` 为什么分开？
2. `202 Accepted` 对应什么执行模型？
3. BaselineCollector 和 Specialist Agent 采集 Evidence 有什么区别？
4. RoutingAdvisor 和 Supervisor 为什么不能合并？
5. 为什么 AgentFinding 的自然语言不能直接成为最终证据？
6. DiagnosisProposal 为什么还要经过 Critic？
7. Critic `REVISE` 与 `FOLLOW_UP` 有什么区别？
8. EvidenceGate 是模型还是本地代码？
9. `SUPPORTED` 与 `CONFIRMED` 的证据差异是什么？
10. 如果 Diagnosis 模型超时，为什么 Incident 是 `INCONCLUSIVE` 而不是 `FAILED`？

至少正确回答 8 题，才进入第 2 天。

### 12.2 恢复 CPU 场景

优先使用本次保存的 Run ID：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenario-runs/$runId/recover"
```

如果 `$runId` 已丢失：

```powershell
Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8081/api/lab/scenarios/CPU_HOTSPOT/recover-active"
```

等待并确认：

```powershell
Start-Sleep -Seconds 10

Invoke-RestMethod `
  -Uri "http://localhost:8081/api/orders/internal/diagnostics" |
  ConvertTo-Json
```

预期 `cpuHotspot=false`、`cpuHotspotWorkers=0`。

## 13. 今日最终提交物

第一天结束时，应当拥有以下内容：

- [ ] 四模块职责表
- [ ] 核心领域对象关系表
- [ ] FaultPilot 总体架构图
- [ ] CPU Incident 时序图
- [ ] Event 与源码方法对照表
- [ ] Incident ID 与 Scenario Run ID
- [ ] 数据库查询记录
- [ ] 10 道闭卷题答案
- [ ] 3 分钟口头讲解稿
- [ ] 已恢复的干净实验环境

## 14. 与后续学习的衔接

第一天只回答“整个系统如何流动”。第二天再逐个拆解 Prometheus、Arthas、PostgreSQL、Redis、Jaeger 和 Evidence 转换逻辑。

完成后使用：

```text
进行第 1 天验收
```

并提供以下四项内容：

1. 本次 Incident ID。
2. 最终 Diagnosis。
3. 自己画出的主调用链。
4. 尚未理解的问题。
