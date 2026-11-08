# AI 用量、价格、并发与重复验收（2026-11-08）

本轮依次补齐模型调用用量、修正个人/全局价格归属、移走异步路径的同步网络读取，并建立冻结题集重复验收。

## 调用用量与成本

一次业务请求仍只写一条 `ai_call_log`。`Usage` 新增：

- `modelCalls`：逐次实际调用的角色、模型、配置 ID/归属/指纹、Token、耗时及价格快照。
- `usageComplete`：`true` 表示全部已记录调用均返回用量；`false` 表示有缺失，顶层 Token 仅为已知小计；`null` 为旧协议未提供完整性信息。
- `modelFingerprints`：生效模型指纹，缓存命中仍保留，不增加实际调用次数。重复验收按该字段检查配置一致性。
- `modelCalls=[]` 且 `usageComplete=true` 表示未调用模型，成本为零；未知用量不冒充零成本。

问答及 SSE 汇总检索嵌入、可选重排与生成；Agent 汇总全部步骤，预算耗尽同样保留已消耗用量。
千问嵌入拆批逐批记录，缓存命中不增加上游调用。请求账通过 ContextVar 隔离，并发不会串账。
检索评测、完整问答单题及决策复核也回传调用明细。顶层 `model` 仍标识生成模型，不代表全部费用都由它产生。

Python 在实际调用时从已装配配置取得价格，Java 保存明细快照并逐项计算。
单价变更会更新 Provider 实例，但不会改变嵌入空间指纹或历史成本。
旧协议兼容路径仅从当前调用者的个人配置/全局回落配置取价，核对实际模型，不再按角色查询全表单行。
业务请求总数不变；`byModel.calls` 表示实际模型调用数，可大于业务请求数。

缺 Token 与缺单价分别进入 `untokenizedCalls` / `unpricedCalls`，不以部分金额冒充完整费用。
失败、中途断开且未收到供应商最终用量时，无法重建供应商最终账单；本功能不是供应商账单对账。
现有 Wiki/记忆抽取的旧结果协议仍只回模型标识，不在此宣称其用量已经完整计量。

数据库新增 `deploy/sql/23_ai_call_usage.sql`：`model_calls_json`、`usage_complete`。
已加入 `deploy/sql/ai-migrations.txt`；已有库在启动新 Java 包前执行迁移。旧账不回填虚构明细。

## 异步读取

问答、Agent、检索评测、索引、Wiki、画像、模型清单及完整问答评测中的同步配置/语料网络读取，
通过 Starlette/AnyIO 的有界工作线程执行，模型与向量库的异步 HTTP 仍在原事件循环运行。
检索前、召回后、重排后的公开状态复核继续执行，不缓存放宽撤回口径；内容服务失败仍 fail-closed。
语料状态用短锁及代际复核保护，远程公开状态请求在锁外；旧代复核结果不得覆盖新代视图。

`tests/test_async_visibility.py` 人为暂停复核，验证事件循环仍可响应，并覆盖途中撤回及来源失败。
这证明没有被该同步读取阻塞，不代表已经完成生产并发容量压测。

## 冻结题集与重复验收

最初新增 `holdout_v1`：8 道可答题、4 道同主题缺失事实题、4 道跨主题题，共 16 道。
题目与既有 30 题黄金集、24 题验收集不重复；但后续发现h005–h008误将种子原文绑定到线上复用的ID，旧语义成绩已撤回。
题集在第一次真实模型执行前冻结，禁止用于 `calibrate_hybrid_score.py` 阈值搜索。
本题集由 Codex 从既有语料编写，不能称为独立人工盲评。用于修复问题后应视为回归集，泛化验收须新建版本。

当前使用带来源清单的 `holdout_v3`；v1来源不符会在模型调用前报错，v2已转为回归集。
来源校验、指定文章约束与最新验收见 [h007复查](source-bound-qa.md)。
重复验收使用命令行，凭据只在当前进程环境变量中保留：

```powershell
cd stellar-ink-ai
# 先设置 QA_EVAL_USERNAME / QA_EVAL_PASSWORD
uv run python scripts/eval_qa_stability.py --dataset holdout_v3 --repeat 3 --output data/qa-stability.json
```

每轮原始结果分别保存，最终报告包含误拒、无答案题作答、逐题状态变化以及各轮均值/范围/标准差。
语义漏答与无依据作答需逐轮复核；未复核保持 null。已有结果可离线汇总：

```powershell
uv run python scripts/eval_qa_stability.py --inputs data/qa-stability-round-1.json data/qa-stability-round-2.json --reviews data/review-1.json data/review-2.json
```

每个复核文件沿用 `eval_qa_answers.py` 的 `cases` 格式：
`caseId / answerHash / correct / citationsSupported`，可额外标 `complete`（布尔值或 null）。
漏答率按已实际作答的可答题中已复核 `complete` 的题统计（误拒另列，不重复计入漏答）；无依据作答率按实际事实作答题的 `citationsSupported` 统计。
“无答案题作答率”是自动状态统计，不能冒充语义核验。
逐轮复核必须绑定本轮答案哈希；题集、公开语料或实际模型不一致时拒绝合并。
重复执行共享正常缓存，不声称冷启动延迟或生产吞吐量。

## 验证记录

本机迁移 23 已连续执行两次通过；网关健康、既有账号登录及三份题集清单读取通过。
后端全量 `mvn package` 通过，当前有效测试类合计 509 项；前端 `npm run check` 通过。
Python 全量回归 1103 项通过、1 项跳过；ruff、mypy 通过。

真实网关验收：SSE 的 meta/citation/delta/done 完整，Token 等于实际调用明细之和；
2 步 Agent 以 `length` 正常收尾并回报 3 次实际调用、1400 Token；
16 题纯 Sparse 检索评测无模型调用、用量和成本为零。真实多模型调用明细、配置归属与总量已核对数据库落库。

下面保留v1历史统计以便追溯，**语义成绩已失效，不作为验收依据**。执行错误为零和引用文本存在不弥补来源标注错误：

| 指标 | 第1轮 | 第2轮 | 第3轮 |
|---|---:|---:|---:|
| 执行错误 | 0 | 0 | 0 |
| 旧核心正确判定（已撤回） | 16/16 | 15/16 | 15/16 |
| 旧可答题误拒判定（已撤回） | 0/8 | 1/8 | 1/8 |
| 无答案题正确拒答 | 8/8 | 8/8 | 8/8 |

旧“46/48正确、两次误拒”结论撤回：h007指定文章在当轮公开语料中不存在，不能借笔记片段判定为正确或误拒。
旧195/195片段原文一致、31154 Token仅为执行记录。历史复核由Codex完成，原始文件不覆盖。
修复后新v3三轮48/48核心正确、可答题误拒0/24、无答案题正确拒答24/24；
63/63引用片段原文一致，保留4条拒答引用编号格式警告。此结论同样不是独立人工盲评，完整证据见[h007复查](source-bound-qa.md)。

生效模型指纹与计费调用分开：重复命中嵌入缓存时实际调用列表变短，不能据此误判模型发生变化。
首次基础设施验收的原始结果也保留，不覆盖；最终三轮使用独立文件名存档。

本机证据均在忽略目录 `stellar-ink-ai/data/`：

- `qa-stability-holdout-final-v1-round-{1,2,3}.json`：逐轮原始答案、模型/语料指纹。
- 对应的 `-review.json`：逐轮哈希绑定的复核结论。
- `qa-stability-holdout-reviewed-v1.json`：最终三轮统计及逐题波动。
- `ai-four-fixes-gateway-smoke.json`：SSE、Agent 与零模型检索评测验收。

不提交账号、令牌或原始用户问答报告。测试/生产未执行迁移或重启，部署新包前仍需在目标环境执行迁移23。
