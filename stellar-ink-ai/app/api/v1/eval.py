"""评测接口（仅内网可达，由 Java `ai-service` 以 ADMIN 门槛转发）。

为什么评测也走 HTTP 而不是只留命令行脚本：用户要的是「在前端点一下就能跑、能对比」。
命令行脚本（`scripts/eval_local_baseline.py`、`compare_strategies.py`）与这里的实现共用
`run_evaluation` → `run_dataset`，所以面板上看到的数字与本地跑出来的一致。

模型：**面板配置是唯一来源**。要跑 Dense 就得先配 embedding 角色、要跑 Rerank 就得先配
rerank 角色 —— 缺哪个在**跑之前**就说清（一轮评测要先嵌入整个语料，让人白等几十秒
再报错是不体面的）。纯稀疏策略不需要任何模型，因此不配也能跑。

错误口径：
- 请求本身有问题（数据集不支持、策略 key 重复、上限越界）→ 400 + `AI_BAD_REQUEST`；
- 语料/数据集文件缺失 → 也是 400，但消息里说清「缺哪个文件」——这是环境问题，
  不该伪装成 500 让运维去翻栈；
- 模型调用失败 → 由 `main.py` 的全局处理器转 502/429，这里不重复一遍。
"""

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error
from app.providers import runtime
from app.rag.eval_runner import EvalDataset
from app.rag.eval_service import (
    DATASET_FILES,
    DEFAULT_STRATEGIES,
    EvalModels,
    default_dataset_path,
    required_roles,
    run_evaluation,
    seed_corpus,
    strategy_specs,
    to_response,
)
from app.schemas.common import AiErrorCode
from app.schemas.eval import EvalModelSource, EvalRunRequest, EvalRunResponse

router = APIRouter(tags=["eval"])


@router.get("/eval/datasets", summary="列出可评测的数据集")
async def list_datasets() -> list[dict[str, object]]:
    """面板的下拉框用它填充；顺带把「多少题、多少无答案」摆出来。"""
    summaries: list[dict[str, object]] = []
    for name in sorted(DATASET_FILES):
        dataset = EvalDataset.load(default_dataset_path(name))
        summaries.append({"id": name, **dataset.summary()})
    return summaries


@router.get("/eval/strategies", summary="列出默认的标准策略组")
async def list_default_strategies() -> list[dict[str, object]]:
    """标准五组与命令行脚本一致：面板先按它跑一遍，再让用户按需改开关。"""
    return [{"key": spec.key, **spec.model_dump(by_alias=True)} for spec in DEFAULT_STRATEGIES]


def panel_models(request: EvalRunRequest) -> EvalModels:
    """按请求里实际要用到的角色取模型（**先预检再取**，缺哪个一次说清）。

    `source` 有三种取值，这不是啰嗦而是防误读：同样是「Dense 列 0.9」，
    真实模型、离线伪向量、以及「这一列根本没跑」是完全不同的结论，
    而表格长得一模一样。
    """
    roles = required_roles(strategy_specs(request))
    runtime.require_roles(*roles)

    registry = runtime.registry()
    configs = [config for role in roles if (config := registry.config_of(role)) is not None]
    if not roles:
        source = EvalModelSource.NONE
    elif all(config.provider == "fake" for config in configs):
        source = EvalModelSource.FAKE
    else:
        source = EvalModelSource.PANEL
    return EvalModels(
        embedder=registry.embedding_model() if "embedding" in roles else None,
        reranker=registry.rerank_model() if "rerank" in roles else None,
        source=source,
        names=tuple(config.model for config in configs),
    )


@router.post("/eval/run", summary="跑一轮检索评测（黄金集 × 多组策略）", response_model=None)
async def run_eval(request: EvalRunRequest) -> EvalRunResponse | JSONResponse:
    try:
        models = panel_models(request)
        outcome = await run_evaluation(request, corpus=seed_corpus(), models=models)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)
    except ValueError as error:
        return JSONResponse(
            status_code=400,
            content={"code": AiErrorCode.BAD_REQUEST.value, "message": str(error)},
        )
    return to_response(outcome)
