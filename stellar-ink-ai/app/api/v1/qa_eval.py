"""ADMIN 完整问答评测：逐题执行，状态与指标由 Python 统一维护。"""

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from starlette.concurrency import run_in_threadpool

from app.api.v1.assembly import ASSEMBLY_ERRORS, assembly_error
from app.api.v1.qa import build_qa_service
from app.core.internal_auth import InternalIdentity
from app.core.internal_auth_middleware import require_internal_identity
from app.rag.qa_eval import QaEvalRuns
from app.rag.qa_reports import QaReportStore
from app.schemas.qa_eval import (
    QaEvalJudgeRequest,
    QaEvalReviewRequest,
    QaEvalRunRequest,
    QaEvalSnapshot,
    QaEvalStartRequest,
)

router = APIRouter(prefix="/eval/qa", tags=["eval"])
runs = QaEvalRuns(build_qa_service, QaReportStore.from_env())


def admin(identity: InternalIdentity = Depends(require_internal_identity)) -> InternalIdentity:  # noqa: B008
    from fastapi import HTTPException

    if identity.role != "ADMIN":
        raise HTTPException(status_code=403, detail="完整问答评测需要站长角色")
    return identity


def bad_request(error: ValueError) -> JSONResponse:
    return JSONResponse(status_code=400, content={"code": "AI_BAD_REQUEST", "message": str(error)})


@router.post("/start", response_model=None)
async def start(
    request: QaEvalStartRequest,
    identity: InternalIdentity = Depends(admin),  # noqa: B008
) -> QaEvalSnapshot | JSONResponse:
    try:
        return await run_in_threadpool(runs.start, request, identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)
    except ValueError as error:
        return bad_request(error)


@router.post("/step", response_model=None)
async def step(
    request: QaEvalRunRequest,
    identity: InternalIdentity = Depends(admin),  # noqa: B008
) -> QaEvalSnapshot | JSONResponse:
    try:
        return await runs.step(request.run_id, identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)
    except ValueError as error:
        return bad_request(error)


@router.get("/runs/{run_id}", response_model=None)
async def get_run(
    run_id: str,
    identity: InternalIdentity = Depends(admin),  # noqa: B008
) -> QaEvalSnapshot | JSONResponse:
    try:
        return runs.view(run_id, identity.user_id)
    except ValueError as error:
        return bad_request(error)


@router.get("/latest", response_model=None)
async def latest(
    identity: InternalIdentity = Depends(admin),  # noqa: B008
) -> QaEvalSnapshot | JSONResponse | None:
    try:
        return await run_in_threadpool(runs.latest, identity.user_id)
    except ValueError as error:
        return bad_request(error)


@router.put("/review", response_model=None)
async def review(
    request: QaEvalReviewRequest,
    identity: InternalIdentity = Depends(admin),  # noqa: B008
) -> QaEvalSnapshot | JSONResponse:
    try:
        return await run_in_threadpool(runs.review, request, identity.user_id)
    except ValueError as error:
        return bad_request(error)


@router.post("/judge", response_model=None)
async def judge(
    request: QaEvalJudgeRequest,
    identity: InternalIdentity = Depends(admin),  # noqa: B008
) -> QaEvalSnapshot | JSONResponse:
    try:
        return await runs.judge(request, identity.user_id)
    except ASSEMBLY_ERRORS as error:
        return assembly_error(error)
    except ValueError as error:
        return bad_request(error)
