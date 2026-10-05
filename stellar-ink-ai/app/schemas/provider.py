"""模型清单的契约（`POST /provider/models` → `POST /ai/me/providers/models`）。

面板里的「添加模型」表单此前要手打模型名。这一对契约让它可以**从供应商实时拉出来挑**，
而清单完全由供应商返回决定 —— 契约里没有、也不允许有厂商或模型名的默认值。

四处刻意如此：

- **响应里没有密钥字段**（连掩码都没有）：`apiKey` 只在**请求**里出现一次，用完即弃。
  回一个掩码看着更「贴心」，但它会让「我填的 Key 对不对」变成一个需要推理的问题，
  而它的风险是实打实的 —— 少回一个字段，就少一条泄露路径。
- `source` = **实际请求的 base_url**（绝不含密钥）：让用户核对自己填得对不对。
  「拉到了 5 个模型」这句话只有在知道「从哪儿拉的」之后才有意义。
- `truncated` 必须**如实**：条数被上限截断时为 true，界面据此说「还有更多，请手填完整名字」，
  而不是让用户以为「我这家就这几个模型」。
- `fake` 协议也走这份契约，但 `source` 是字面量 `fake`（不是 URL）：我们没有向任何地址发过请求，
  写成地址等于替它说「那个地址上有这些模型」。
"""

from pydantic import Field

from app.schemas.base import ContractRequest, ContractResponse


class ProviderModelsRequest(ContractRequest):
    """``POST /provider/models`` 的请求体。

    `apiKey` 留空表示**用该用户已保存的该角色密钥**（两处都没有 → 400 + 可读提示）：
    用户刚在面板里存过 Key，再让他为了拉一次清单重填一遍，只会催生「把 Key 贴在别处」的坏习惯。

    ⚠️ `baseUrl` 可空**只**为了 `fake`：Java 侧把「没给地址」归一成 `null` 再转发
    （面板把协议选成 fake 时地址栏可以是空的）。非 fake 协议给 `null`/空串会被
    `url_policy` 拒成 **400**（「接口地址不能为空」）—— 这里不替它编一个默认地址。
    """

    provider: str = Field(
        min_length=1,
        max_length=64,
        description="协议实现：openai_compatible / fake（未知值 → 400，并列出可选值）",
    )

    base_url: str | None = Field(
        default=None,
        max_length=255,
        description=(
            "API 根地址（`/models` 由代码拼）；fake 不需要，可留空。"
            "只允许公网地址（防 SSRF）"
        ),
    )

    api_key: str | None = Field(
        default=None,
        max_length=512,
        description="明文 API Key，只用于这一次拉取；留空表示用已保存的该角色密钥",
    )

    role: str = Field(
        default="chat",
        max_length=32,
        description="从哪个角色的已保存配置里取密钥（默认 chat）",
    )


class ProviderModelEntry(ContractResponse):
    """供应商返回的一个模型条目。

    **只留 `id`**（外加可选的 `created`）：这是形状未知的外部输入，
    多取一个字段就多一份「供应商改了字段我们就崩」的风险，而界面只需要一个可选的标识。
    """

    id: str = Field(min_length=1, description="模型标识，原样透传（不做任何改写）")
    created: int | None = Field(
        default=None, description="供应商给出的创建时间戳（多数服务不返回，为空是正常形态）"
    )


class ProviderModelsResult(ContractResponse):
    """一次拉取的清单。

    ⚠️ 清单为**空**是合法结果（供应商确实没列出来）：它与「拉取失败」是两件事，
    失败会走 4xx/5xx 的错误体，不会用一个空清单冒充成功。
    """

    models: list[ProviderModelEntry] = Field(
        default_factory=list, description="模型清单（按 id 去重、按上限截断后的结果）"
    )
    truncated: bool = Field(
        default=False, description="是否因条数上限被截断（true 时界面应提示还需手填完整名字）"
    )
    source: str = Field(
        min_length=1,
        description="实际请求的 base_url（不含密钥，供用户核对）；协议为 fake 时是字面量 fake",
    )
