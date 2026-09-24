"""契约基类：Java 侧 DTO 与 Python Pydantic 共用一组 JSON（驼峰键）。

为什么统一用驼峰：Java 侧 Jackson 默认输出就是驼峰，Python 内部继续写蛇形字段、
用 ``alias_generator`` 负责边界转换，两边都不必改各自的语言习惯，
fixture 里的键名对所有语言都是同一份。

红线：``extra="forbid"`` —— 契约之外的字段必须显式报错，不能静默丢弃或吞下。
"""

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class ContractModel(BaseModel):
    """所有跨语言契约的基类。"""

    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        extra="forbid",
        str_strip_whitespace=True,
    )


class ContractRequest(ContractModel):
    """外部请求契约：Java 侧校验后再转发，Python 仍做一次防御性校验。"""


class ContractResponse(ContractModel):
    """对外响应契约：与 Java ``Response<T>`` 的 ``data`` 部分对应。"""
