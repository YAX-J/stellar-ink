"""契约共享类型：引用、用量、错误与可选值。"""

from enum import StrEnum
from typing import Annotated

from pydantic import Field

from app.schemas.base import ContractModel

#: 统一上限：所有对外的文本字段都受长度约束，避免无界输入
MAX_QUESTION_LENGTH = 500
MAX_TITLE_LENGTH = 200
MAX_PARAGRAPH_LENGTH = 10_000

ShortText = Annotated[str, Field(min_length=1, max_length=MAX_TITLE_LENGTH)]


class Role(StrEnum):
    """与 Java ``shared-model`` 的 ``Role`` 枚举取值一致（仅用于日志与配额，不做鉴权）。"""

    READER = "READER"
    AUTHOR = "AUTHOR"
    ADMIN = "ADMIN"


class DoneReason(StrEnum):
    """生成结束原因；``REFUSED`` 表示证据不足时的明确拒答。"""

    STOP = "stop"
    LENGTH = "length"
    REFUSED = "refused"
    CANCELLED = "cancelled"
    ERROR = "error"


class AiErrorCode(StrEnum):
    """可展示给用户的错误码；不含堆栈与密钥信息。"""

    BAD_REQUEST = "AI_BAD_REQUEST"
    UNAUTHORIZED = "AI_UNAUTHORIZED"
    FORBIDDEN = "AI_FORBIDDEN"
    RATE_LIMITED = "AI_RATE_LIMITED"
    UPSTREAM_UNAVAILABLE = "AI_UPSTREAM_UNAVAILABLE"
    TIMEOUT = "AI_TIMEOUT"
    INTERNAL = "AI_INTERNAL"


class Citation(ContractModel):
    """引用：必须能定位回原文，M3 起由检索结果填充。"""

    post_id: int = Field(description="引用文章 ID")
    title: str = Field(description="引用文章标题")
    chunk_index: int = Field(ge=0, description="段落序号（从 0 开始）")
    snippet: str = Field(min_length=1, description="引用的原文片段")
    score: float | None = Field(default=None, description="检索/重排得分，可空")


class Usage(ContractModel):
    """用量：M2 起由 Provider 回填；M0 的 Fake Adapter 也必须给出确定值。"""

    prompt_tokens: int = Field(default=0, ge=0, description="输入 Token")
    completion_tokens: int = Field(default=0, ge=0, description="输出 Token")
    total_tokens: int = Field(default=0, ge=0, description="总 Token")
    latency_ms: int = Field(default=0, ge=0, description="端到端耗时（毫秒）")
    model: str | None = Field(default=None, description="实际使用的模型标识")


class ErrorBody(ContractModel):
    """错误体：与 Java ``Response`` 的 code/msg/traceId 语义对齐。"""

    code: AiErrorCode = Field(description="可展示错误码")
    message: str = Field(min_length=1, description="可展示的中文提示")
    trace_id: str | None = Field(default=None, description="链路追踪 ID")
