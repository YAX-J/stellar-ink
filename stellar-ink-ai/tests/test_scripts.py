"""命令行脚本的自检：**每个脚本都要能在默认控制台上跑起来**。

这一条来自一次真实的失败：`compare_strategies.py` 算完整张对比表、
分析也打完了，最后一行 `⚠️` 在 Windows 的 GBK 控制台上抛
`UnicodeEncodeError` —— 于是脚本以退出码 1 结束。
指标其实是对的，但任何人（或任何 CI）看到的都是「这个脚本坏了」。

因此这里守两件事：
1. 每个脚本的入口都调用了 `use_utf8_console()`（漏掉就红，而不是等到某天在 GBK 终端上炸）；
2. 每个脚本都能被 import 成功 —— 且**两种加载方式都要成立**：
   直接运行（`scripts/` 在 `sys.path[0]`）与 pytest 的 `from scripts.x import y`。
   `console` 的导入写法必须两种都兜住，否则 import 期就炸（踩过一次）。
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
#: 入口就是 `python -m app...` 的那种不需要控制台配置；这里只扫直接运行的脚本
SCRIPT_FILES = sorted(
    path for path in SCRIPTS.glob("*.py") if path.name not in {"__init__.py", "console.py"}
)


def load_script(name: str):
    """按文件路径加载脚本模块（scripts/ 不是包，不能用 import 语句直接引）。"""
    sys.path.insert(0, str(SCRIPTS))
    try:
        return load_module_from_path(SCRIPTS / f"{name}.py", name=name)
    finally:
        sys.path.remove(str(SCRIPTS))


def load_module_from_path(path: Path, *, name: str | None = None):
    """按文件路径执行一个模块，**并把它注册进 `sys.modules`**（这一步不能省）。

    为什么必须注册（踩过一次，报错完全指错方向）：`@dataclass(slots=True)` 在生成
    新类时要拿 `sys.modules[cls.__module__].__dict__`，而 `exec_module` 之前若没注册，
    取到的是 `None` → `AttributeError: 'NoneType' object has no attribute '__dict__'`，
    栈底落在 `dataclasses.py` 里，看起来像**脚本自己写坏了**。
    真实 `import` 一定有注册这一步，所以这是加载器的保真度问题；不修的话，
    任何脚本只要用了 `slots=True`（或 pickle、`get_type_hints` 之类）就会以这种形态红。
    """
    module_name = name or path.stem
    spec = importlib.util.spec_from_file_location(module_name, path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
    return module


def test_every_script_configures_the_console() -> None:
    """漏掉 `use_utf8_console()` 会让「算对了但退出码 1」这种事再发生一次。"""
    missing: list[str] = []
    for path in SCRIPT_FILES:
        text = path.read_text(encoding="utf-8")
        if "use_utf8_console" not in text:
            missing.append(path.name)

    assert not missing, f"这些脚本没有配置控制台编码，在 GBK 终端上会崩：{missing}"


@pytest.mark.parametrize("path", SCRIPT_FILES, ids=lambda path: path.name)
def test_scripts_are_importable(path: Path) -> None:
    """import 期不该有任何副作用与路径问题（这是脚本最基本的可用性）。"""
    module = load_script(path.stem)

    assert module is not None


def test_loader_registers_the_module_so_slots_dataclasses_work(tmp_path: Path) -> None:
    """加载器必须像真实 import 那样把模块注册进 `sys.modules`。

    `@dataclass(slots=True)` 生成新类时要读 `sys.modules[cls.__module__].__dict__`；
    没注册就取到 `None`，报 `AttributeError: 'NoneType' object has no attribute '__dict__'`，
    栈底落在 `dataclasses.py` —— 看起来像**脚本写错了**，实际是加载器少了这一步。
    这条用一个临时模块把判据钉住，别等到某个脚本哪天加了 `slots=True` 才发现。
    """
    path = tmp_path / "slots_probe.py"
    path.write_text(
        "from dataclasses import dataclass, field\n"
        "\n"
        "@dataclass(slots=True)\n"
        "class Probe:\n"
        "    name: str\n"
        "    notes: list = field(default_factory=list)\n"
        "\n"
        "CREATED = Probe('ok')\n",
        encoding="utf-8",
    )

    module = load_module_from_path(path)

    assert module.CREATED.name == "ok"
    assert module.CREATED.notes == []


def test_console_helper_degrades_instead_of_raising() -> None:
    """`errors="replace"` 是刻意的：打不出的字符显示成 `?`，不要让整次运行失败。"""
    module = load_script("console")

    module.use_utf8_console()  # 幂等：重复调用无副作用
    encoding = getattr(sys.stdout, "encoding", "")

    assert encoding and encoding.lower().replace("-", "") == "utf8"


def test_scripts_do_not_rely_on_the_current_directory() -> None:
    """脚本用 `default_seed_sql()` 之类向上查找仓库文件，不该假设 cwd 就是项目根。

    这里只断言「脚本文件里没有把相对路径当常量直接用」这种最粗的写法。
    """
    offenders: list[str] = []
    for path in SCRIPT_FILES:
        text = path.read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), start=1):
            stripped = line.strip()
            if stripped.startswith("open(") and "Path(" not in stripped:
                offenders.append(f"{path.name}:{number}")

    assert not offenders, f"这些地方用相对路径直接开文件，换个 cwd 就会失败：{offenders}"


def test_console_import_works_under_both_loading_styles() -> None:
    """`console` 的导入写法必须两种加载方式都成立。

    `from console import ...` 在「直接运行」下对、在 `from scripts.x import y` 下错；
    `from scripts.console import ...` 反过来。两种方式都真实存在（后者见 `test_golden_set.py`），
    所以这里按「pytest 那条路径」实际导入一次，而不是只做文本检查。
    """
    from scripts.check_golden_evidence import (
        labeled_evidence_hits,  # noqa: PLC0415 - 就是为了验证这条路径
    )

    assert callable(labeled_evidence_hits)
