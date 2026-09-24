"""模型供应商层：把「用哪个模型」变成配置，而不是写死在业务代码里。

目录职责（与 docs/ai/fast-track-plan.md §2 对齐）：

- `models.py`  跨 Provider 的数据结构（消息、用量、生成结果、向量、重排结果）
- `errors.py`  可分类的调用错误（超时 / 限流 / 上游错误 / 无该能力），供上层决定降级
- `base.py`    三类模型接口（Chat / Embedding / Rerank）—— **刻意不合成一个万能类**
- `openai_compatible.py`  一套 OpenAI 兼容实现，覆盖绝大多数国内云与自建推理服务
- `fake.py`    确定性假实现：让全部单测与评测流程在没有密钥的情况下也能跑通
- `registry.py` 按「逻辑角色」装配与路由（chat / fast / reasoning / embedding / rerank）

边界：本层只管「怎么把请求发出去、怎么把结果读回来」；
Prompt、切块、检索融合、引用组装等 AI 逻辑在 `app/rag` 与 `app/agents`，不在这里。
"""

from app.providers.base import ChatModel, EmbeddingModel, RerankModel
from app.providers.errors import (
    ProviderAuthError,
    ProviderError,
    ProviderRateLimitError,
    ProviderTimeoutError,
    ProviderUnavailableError,
    UnsupportedCapabilityError,
)
from app.providers.fake import FakeProvider
from app.providers.models import (
    ChatMessage,
    ChatResponse,
    EmbeddingResponse,
    MessageRole,
    ProviderCapabilities,
    ProviderConfig,
    RerankResponse,
    TokenUsage,
)
from app.providers.openai_compatible import OpenAICompatibleProvider
from app.providers.registry import CHAT_ROLES, ProviderRegistry, capability_of

__all__ = [
    "CHAT_ROLES",
    "ChatMessage",
    "ChatModel",
    "ChatResponse",
    "EmbeddingModel",
    "EmbeddingResponse",
    "FakeProvider",
    "MessageRole",
    "OpenAICompatibleProvider",
    "ProviderAuthError",
    "ProviderCapabilities",
    "ProviderConfig",
    "ProviderError",
    "ProviderRateLimitError",
    "ProviderRegistry",
    "ProviderTimeoutError",
    "ProviderUnavailableError",
    "RerankModel",
    "RerankResponse",
    "TokenUsage",
    "UnsupportedCapabilityError",
    "capability_of",
]
