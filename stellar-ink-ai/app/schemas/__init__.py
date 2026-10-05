"""跨语言契约：Java ``ai-service`` / ``stellar-ink-ai-client`` 与本服务的唯一事实来源。

约定：
- 字段名在 JSON 里统一驼峰（Java 侧 Jackson 默认，Python 侧由别名生成器转换）。
- 共享样例放 ``tests/fixtures/``，Java 契约测试与 Python Schema 测试**读同一组文件**
  （M0-4 的 JUnit 通过相对路径读取，路径常量见 ``tests/fixtures/README.md``）。
- 契约只描述数据结构，不含鉴权与签名（那是 M1 的 HMAC 头）。
"""

from app.schemas.base import ContractModel, ContractRequest, ContractResponse
from app.schemas.common import (
    AiErrorCode,
    Citation,
    DoneReason,
    ErrorBody,
    Role,
    Usage,
)
from app.schemas.eval import (
    EvalCaseResultRow,
    EvalModelSource,
    EvalRunRequest,
    EvalRunResponse,
    EvalStrategySpec,
    EvalStrategySummary,
)
from app.schemas.indexing import (
    IndexJob,
    IndexJobStatus,
    IndexRebuildRequest,
    IndexTaskKind,
)
from app.schemas.provider import (
    ProviderModelEntry,
    ProviderModelsRequest,
    ProviderModelsResult,
)
from app.schemas.qa import QaAnswer, QaStreamRequest
from app.schemas.qa_stream import (
    EVENT_CITATION,
    EVENT_DELTA,
    EVENT_DONE,
    EVENT_ERROR,
    EVENT_META,
    EVENT_TYPES,
    StreamEvent,
    citation_event,
    delta_event,
    done_event,
    error_event,
    heartbeat,
    meta_event,
)
from app.schemas.style import (
    MAX_STYLE_SAMPLES,
    WritingStyleProfile,
    WritingStyleRequest,
    WritingStyleResult,
)
from app.schemas.writing import (
    WritingCandidate,
    WritingSuggestRequest,
    WritingSuggestResult,
    WritingTask,
    WritingTone,
)

__all__ = [
    "AiErrorCode",
    "Citation",
    "ContractModel",
    "ContractRequest",
    "ContractResponse",
    "DoneReason",
    "EVENT_CITATION",
    "EVENT_DELTA",
    "EVENT_DONE",
    "EVENT_ERROR",
    "EVENT_META",
    "EVENT_TYPES",
    "ErrorBody",
    "EvalCaseResultRow",
    "EvalModelSource",
    "EvalRunRequest",
    "EvalRunResponse",
    "EvalStrategySpec",
    "EvalStrategySummary",
    "IndexJob",
    "IndexJobStatus",
    "IndexRebuildRequest",
    "IndexTaskKind",
    "MAX_STYLE_SAMPLES",
    "ProviderModelEntry",
    "ProviderModelsRequest",
    "ProviderModelsResult",
    "QaAnswer",
    "QaStreamRequest",
    "Role",
    "StreamEvent",
    "Usage",
    "WritingCandidate",
    "WritingStyleProfile",
    "WritingStyleRequest",
    "WritingStyleResult",
    "WritingSuggestRequest",
    "WritingSuggestResult",
    "WritingTask",
    "WritingTone",
    "citation_event",
    "delta_event",
    "done_event",
    "error_event",
    "heartbeat",
    "meta_event",
]
