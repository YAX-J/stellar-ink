"""Prompt Registry：提示词的名称、版本、变量、适用角色与**评测状态**（M8）。

为什么要有它（不是为了好看）：

1. **提示词是这套系统里改动最频繁、影响最大、留下痕迹最少的东西。**
   模型换了一版、回答风格变了，第一件事就是改提示词 —— 而改完之后，
   没有任何地方能回答「上周那批评测用的是哪一版提示词」。
   注册表把「版本」变成一个可引用的东西。
2. **变量必须是显式的。** 模板里写错的 `{xxx}` 会在调用时才炸，
   而且常常炸成一个与真实原因无关的 KeyError（E4 踩过：JSON 示例里的花括号被当成字段名）。
   `PromptSpec.variables` 把这件事提前到**启动期自检**。
3. **「评测结果」要如实留白。** 没有被评测过的提示词就写 `evaluation=None`，
   界面上显示「尚未评测」——**编一个「效果良好」比留白危险得多**。

所以三件事一起做：模板收进注册表（单一来源）、变量声明清楚（能自检）、
每次调用都能说出「用的是哪一版」。
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from string import Formatter


class PromptError(RuntimeError):
    """提示词的名字/版本/变量不对。**在启动自检或渲染时立刻抛出**，不带病运行。"""


@dataclass(frozen=True, slots=True)
class PromptSpec:
    """一个提示词的一个版本。"""

    name: str
    version: int
    template: str
    #: 模板里允许出现的变量（顺序即文档里的顺序）
    variables: tuple[str, ...]
    description: str
    #: 适用角色（chat / fast / reasoning…）：换角色可能要换提示词，写清楚才知道该不该改
    roles: tuple[str, ...] = ("chat",)
    #: 评测状态：写明「在哪份数据集上、结论是什么」；**没评测过就留空**
    evaluation: str | None = None
    #: 结构化输出的形状说明（有的提示词要求 JSON；纯文本的写 None）
    output_schema: Mapping[str, str] = field(default_factory=dict)
    #: 是否走 `str.format` 填充变量。
    #:
    #: ⚠️ **必须区分**（注册表的启动自检把这个坑顶出来过）：提示词里常有 JSON 示例，
    #: 那些花括号在 `str.format` 眼里就是字段名 —— E4 踩过一次（示例直接写进模板，
    #: 报一个与真实原因毫不相干的 KeyError）。所以「原样发送给模型」的提示词标 `False`，
    #: 自检不去解析它的花括号；只有真正要填变量的才标 `True`（并要求 JSON 花括号写成 `{{ }}`）。
    interpolated: bool = True

    def render(self, **values: object) -> str:
        """填充变量。

        **缺变量与多变量都报错**：多传的往往意味着模板改过而调用方没跟上
        （那正是「字段名写错」这类 bug 的温床）。

        非插值提示词（`interpolated=False`）原样返回 —— 它的花括号是 JSON，
        不是字段名（见该字段的说明）。
        """
        if not self.interpolated:
            if values:
                raise PromptError(
                    f"{self.key} 是原样发送的提示词，不该传变量：{'、'.join(sorted(values))}"
                )
            return self.template
        missing = [name for name in self.variables if name not in values]
        extra = [name for name in values if name not in self.variables]
        if missing:
            raise PromptError(f"{self.key} 缺少变量：{'、'.join(missing)}")
        if extra:
            raise PromptError(
                f"{self.key} 收到模板里没有的变量：{'、'.join(extra)}（模板改过而调用方没跟上？）"
            )
        return self.template.format(**values)

    @property
    def key(self) -> str:
        """`名称@版本`，日志里用它一眼看出「这次用的是哪一版」。"""
        return f"{self.name}@{self.version}"


class PromptRegistry:
    """按名称收集提示词，取用时默认拿**最高版本**。"""

    def __init__(self, specs: Iterable[PromptSpec] = ()) -> None:
        self._by_name: dict[str, dict[int, PromptSpec]] = {}
        for spec in specs:
            self.register(spec)

    def register(self, spec: PromptSpec) -> None:
        versions = self._by_name.setdefault(spec.name, {})
        if spec.version in versions:
            raise PromptError(f"{spec.key} 重复注册")
        versions[spec.version] = spec

    def get(self, name: str, version: int | None = None) -> PromptSpec:
        versions = self._by_name.get(name)
        if not versions:
            raise PromptError(
                f"没有注册名为 {name} 的提示词（已注册：{'、'.join(sorted(self._by_name))}）"
            )
        if version is None:
            return versions[max(versions)]
        if version not in versions:
            raise PromptError(f"{name} 没有第 {version} 版（现有：{sorted(versions)}）")
        return versions[version]

    def render(self, name: str, *, version: int | None = None, **values: object) -> str:
        return self.get(name, version).render(**values)

    def describe(self) -> list[dict[str, object]]:
        """给观测出口/面板用的清单（只出元数据，不出模板全文 —— 模板可能很长）。"""
        rows: list[dict[str, object]] = []
        for name in sorted(self._by_name):
            for version in sorted(self._by_name[name]):
                spec = self._by_name[name][version]
                rows.append(
                    {
                        "name": spec.name,
                        "version": spec.version,
                        "key": spec.key,
                        "description": spec.description,
                        "variables": list(spec.variables),
                        "roles": list(spec.roles),
                        # 如实留白：没有评测过的就是 null，界面显示「尚未评测」
                        "evaluation": spec.evaluation,
                        "outputSchema": dict(spec.output_schema),
                    }
                )
        return rows

    def names(self) -> list[str]:
        return sorted(self._by_name)


def declared_variables(template: str) -> set[str]:
    """模板里真正用到的字段名（供自检用：注册时声明与实际必须一致）。

    ⚠️ 用 `string.Formatter` 而不是正则：`{a!r}`、`{a:>4}`、`{{}}` 这些形态
    正则会数错，而数错的后果是「自检通过、运行期炸」。
    """
    names: set[str] = set()
    for _literal, field_name, _format_spec, _conversion in Formatter().parse(template):
        if field_name:
            names.add(field_name)
    return names


def self_check(registry: PromptRegistry) -> list[str]:
    """启动期自检：声明的变量与模板里的实际变量必须一致。返回不一致的说明（空 = 通过）。

    只检查**插值型**提示词：原样发送的那些里面的花括号是 JSON 示例，不是字段名。
    """
    problems: list[str] = []
    for name in registry.names():
        spec = registry.get(name)
        if not spec.interpolated:
            continue
        actual = declared_variables(spec.template)
        declared = set(spec.variables)
        if missing := sorted(actual - declared):
            problems.append(f"{spec.key} 模板用了未声明的变量：{'、'.join(missing)}")
        if unused := sorted(declared - actual):
            problems.append(f"{spec.key} 声明了模板里没用到的变量：{'、'.join(unused)}")
    return problems
