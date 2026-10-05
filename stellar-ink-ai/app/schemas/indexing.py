"""索引任务契约（M3 起实现，M4 起改为 Outbox 增量事件驱动）。

M0 只冻结任务对象与状态语义：ADMIN 触发全量/按文章重建，任务状态可查询。
真正写 Qdrant 的逻辑在 M3，M0 不得出现任何向量库依赖。
"""

from datetime import datetime
from enum import StrEnum
from typing import Literal

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse


class IndexTaskKind(StrEnum):
    """索引任务类型：MVP 只有全量与单篇重建（roadmap §7 数据一致性）。"""

    FULL_REBUILD = "full_rebuild"
    POST_REBUILD = "post_rebuild"


class IndexJobStatus(StrEnum):
    """任务状态：``PARTIAL`` 表示部分文章失败但仍可查询结果。"""

    PENDING = "pending"
    RUNNING = "running"
    SUCCEEDED = "succeeded"
    PARTIAL = "partial"
    FAILED = "failed"


class IndexRebuildRequest(ContractRequest):
    """``POST /ai/admin/index/rebuild`` 的请求体。"""

    kind: IndexTaskKind = Field(
        default=IndexTaskKind.FULL_REBUILD,
        description="任务类型；单篇重建必须同时给出 post_id",
    )

    post_id: int | None = Field(
        default=None,
        ge=1,
        description="``post_rebuild`` 时的目标文档 ID（配合 ``content_kind`` 定位）",
    )

    content_kind: Literal["post", "note"] | None = Field(
        default=None,
        description="``post_rebuild`` 时的内容种类：文章（``post``）或技术笔记（``note``）。"
        "文档标识是 ``content_kind + post_id`` —— 文章 3 与笔记 3 是两个文档，"
        "只给数字 id 会一次命中两篇。全量重建忽略本字段。"
        "**缺省与显式 null 都按 ``post``**：Java 侧的 DTO 字段默认是 null，"
        "而 Jackson 默认会把 null 一起发出来 —— 这里不收 null 的话，"
        "「全量重建」会因为一个它根本不用的字段被 422 掉。",
    )

    reason: str | None = Field(
        default=None,
        max_length=200,
        description="触发原因（写入审计日志，便于回溯是谁在什么时候重建）",
    )


class IndexJob(ContractResponse):
    """索引任务状态；``GET /ai/admin/jobs/{id}`` 的返回体。"""

    job_id: str = Field(min_length=1, max_length=64, description="任务 ID")

    kind: IndexTaskKind = Field(description="任务类型")

    status: IndexJobStatus = Field(description="任务状态")

    total_posts: int = Field(default=0, ge=0, description="待处理文章总数")

    processed_posts: int = Field(default=0, ge=0, description="已处理文章数")

    failed_posts: int = Field(default=0, ge=0, description="失败文章数")

    message: str | None = Field(default=None, description="失败或部分失败时的中文说明")

    created_at: datetime | None = Field(default=None, description="创建时间（ISO 8601）")

    finished_at: datetime | None = Field(default=None, description="结束时间（ISO 8601）")
