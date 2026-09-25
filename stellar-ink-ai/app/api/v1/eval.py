"""评测接口（仅内网可达，由 Java `ai-service` 以 ADMIN 门槛转发）。

为什么评测也走 HTTP 而不是只留命令行脚本：用户要的是「在前端点一下就能跑、能对比」。
命令行脚本（`scripts/eval_local_baseline.py`、`compare_strategies.py`）与这里的实现共用
`run_evaluation` → `run_dataset`，所以面板上看到的数字与本地跑出来的一致。

错误口径：
- 请求本身有问题（数据集不支持、策略 key 重复、上限越界）→ 400 + `AI_BAD_REQUEST`；
- 语料/数据集文件缺失 → 也是 400，但消息里说清「缺哪个文件」——这是环境问题，
  不该伪装成 500 让运维去翻栈。
"""

from fastapi import APIRouter
from fastapi.responses import JSONResponse

from app.rag.eval_runner import EvalDataset
from app.rag.eval_service import (
    DATASET_FILES,
    DEFAULT_STRATEGIES,
    default_dataset_path,
    run_evaluation,
    to_response,
)
from app.schemas.common import AiErrorCode
from app.schemas.eval import EvalRunRequest, EvalRunResponse

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


@router.post("/eval/run", summary="跑一轮检索评测（黄金集 × 多组策略）", response_model=None)
async def run_eval(request: EvalRunRequest) -> EvalRunResponse | JSONResponse:
    try:
        outcome = await run_evaluation(request)
    except ValueError as error:
        return JSONResponse(
            status_code=400,
            content={"code": AiErrorCode.BAD_REQUEST.value, "message": str(error)},
        )
    return to_response(outcome)
