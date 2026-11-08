# 混合检索标定与独立验收（2026-11-07）

检索之后的最终答案质量、上下文截断修复与SSE验收见 [最终问答记录](qa-answer-acceptance.md)。

本轮按「两路阈值标定 → 独立验收 → 新向量集合重建 → 实际问答」执行，
只切换本机开发服务；模型角色仍取面板配置，不写任何厂商或模型默认值。

## 配置与使用

当前嵌入是 `qwen3.7-text-embedding-flash`，1024 维。
问答与 Agent 共用已验收的混合检索，`candidateK=30`、`minScore=26.1`、
`minDenseScore=0.412`，不启用重排。标准五组基线保留，便于对照。

```powershell
cd stellar-ink-ai
$env:MYSQL_HOST='127.0.0.1'
uv run python scripts/calibrate_hybrid_score.py --acceptance acceptance_v1
```

脚本读取当前公开语料及真实模型，同时扫描文章范围与文章加公开笔记范围。
原 `calibrate_dense_score.py` 也已改用当前公开文章，不再固定读取种子。
阈值约束预先固定：不新增误拒、可答题命中率不下降、Recall@5 最多下降 2.5 个百分点；
只从原黄金集选参数，再用 `acceptance_v1` 验收冻结的参数。
验收集禁止用于阈值拟合，也不能与标定集有重复问题。

输出在本机忽略目录：

- `stellar-ink-ai/data/retrieval-calibration.json`：标定与独立验收报告。
- `stellar-ink-ai/data/retrieval-profile.json`：只有独立验收通过才生成的运行配置。

本机 `.env` 已设置 `AI_RETRIEVAL_PROFILE_PATH=data/retrieval-profile.json`。
标定文件绑定嵌入模型指纹与整份公开语料指纹，两者均匹配才应用。
模型或正文/标题/语料范围变化后不沿用旧阈值，记录警告并使用既有未标定基线，需重新标定。
显式配置的文件缺失、损坏或未通过验收时返回可读配置错误，不能假装已启用。

刷新 AI 实验室后可看到第六组 `hybrid+calibrated`，以及「独立验收集 v1」。
新增策略只在当前模型与语料匹配有效标定文件时出现；空请求仍使用原标准五组。
策略与数据集走原有接口、DTO 和前端 store，无需增加接口或前端依赖。

## 最终网关实测

公开文章评测使用 29 篇文章、39 块；实际问答与向量索引使用 29 篇文章加 11 篇公开笔记、92 块。

| 题集 | 策略 | Recall@5 | 无答案题拒答率 | 可答题误拒率 | 错误数 |
|---|---|---:|---:|---:|---:|
| 原黄金集 30 题 | hybrid | 0.9583 | 0% | 0% | 0 |
| 原黄金集 30 题 | hybrid+calibrated | 0.9583 | 60% | 0% | 0 |
| 独立验收集 24 题 | hybrid | 1.0000 | 0% | 0% | 0 |
| 独立验收集 24 题 | hybrid+calibrated | 1.0000 | 50% | 0% | 0 |

独立集含 12 道可答题、6 道同主题缺失具体事实题、6 道跨主题无答案题。
可答题的标注有原文检查，问题不与原黄金集重复。
该结果说明阈值改善跨主题拒答；不能保证同主题缺失事实题都被检索层挡住。
此外原集 MRR 从 1.0000 变为 0.9417、NDCG@5 从 0.9730 变为 0.9243，
是过滤候选带来的排序代价，不能只报告 Recall 或拒答率。
标定/复跑中原集拒答率出现 60%～70%、全语料 Recall@5 出现 0.9333～0.9583 的差异；
表中使用最终网关结果，不将单次较高值当成稳定保证。延迟受到嵌入缓存影响，不据此声称阈值提速。

## 向量集合与问答

旧集合 `stellar_ink_chunks` 是 2048 维，与新模型不匹配，保留原样。
新建并验证 `stellar_ink_chunks_dev_f748ac16d9f7a0d6_20261107`：
1024 维、40 篇、92 点，覆盖与模型指纹检查通过，状态 green。
本机 `.env` 的 `QDRANT_COLLECTION` 已切换到此集合；未切换测试机或生产应用。
54 题内存/Qdrant 对照的召回、拒答、误拒和错误数一致。

网关真实问答验证：

- 标签精确匹配问题：返回 `FIND_IN_SET`，带文章与公开笔记引用。
- 量子熵问题：检索拒答、无引用、零生成 Token，非流式与 SSE 均为 `refused / false`。
- 植物拉丁学名问题：模型指出摘录未提供事实，保留相关引用，非流式与 SSE 均为 `refused / false`。

本次同时修复了「模型正文已拒答，元数据仍为 stop / true」：
模型按约定以「文章里没有找到依据。」开头时，共用判定返回 `doneReason=refused`、
`evidenceSufficient=false`；不按任意关键词猜测，也不把 `length` 截断改成拒答。
这是已检索到相关段落之后的输出状态判定，不是通用的事实验证器。

本机证据：`data/qwen-index-rebuild.json`、`data/qwen-index-parity.json`、
`data/gateway-retrieval-acceptance.json`、`data/qa-refusal-acceptance.json`（均在 `stellar-ink-ai/` 下）。
最后一份包含拒答状态修复后的网关与 SSE 结果。

## 验证与回退

Python：1026 项通过、1 项跳过；ruff、mypy（94 个模块）通过。
前端：`npm run check`（自检及 Vite 构建）通过；Java 代码与契约字段未修改。

仅回退阈值：清空 `AI_RETRIEVAL_PROFILE_PATH` 并重启 Python。
需要暂时绕开 Qdrant：设 `AI_DENSE_STORE_ENABLED=false` 并重启，使用当前模型的内存通路。
不能直接把 1024 维模型切回旧 2048 维集合；旧集合保留不代表可与新模型混用。
