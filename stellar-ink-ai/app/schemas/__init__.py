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
from app.schemas.indexing import (
    IndexJob,
    IndexJobStatus,
    IndexRebuildRequest,
    IndexTaskKind,
)
from app.schemas.qa import QaAnswer, QaStreamRequest
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
    "ErrorBody",
    "IndexJob",
    "IndexJobStatus",
    "IndexRebuildRequest",
    "IndexTaskKind",
    "QaAnswer",
    "QaStreamRequest",
    "Role",
    "Usage",
    "WritingCandidate",
    "WritingSuggestRequest",
    "WritingSuggestResult",
    "WritingTask",
    "WritingTone",
]
