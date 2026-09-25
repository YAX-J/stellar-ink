"""脚本共用的控制台配置：保证在任何终端上都不会因为中文/符号而崩。

Windows 控制台默认是 **GBK**（cp936），而本仓库的脚本都要打中文与 `⚠️` 之类的符号 ——
`print` 到一半抛 `UnicodeEncodeError` 会让一个**已经跑完并算对了**的脚本以失败退出。
更糟的是它发生在最后几行：指标已经打出来了，看的人以为「数字对不上」，
其实只是输出编码的问题。

用法（在脚本里这样引）::

    try:  # 直接运行：scripts/ 就是 sys.path[0]
        from console import use_utf8_console
    except ModuleNotFoundError:  # 被 pytest 以 `scripts.x` 形式导入时，包名不同
        from scripts.console import use_utf8_console

**为什么不用单一路径**：脚本有两种被导入的方式 ——
`uv run python scripts/x.py`（`scripts/` 在 `sys.path[0]`）与
`from scripts.x import y`（pytest 里的用法，`scripts` 是命名空间包）。
只写一种写法，另一种就会在 import 期直接炸；这一点是被测试逼出来的
（`tests/test_golden_set.py` 就用了后一种）。

`errors="replace"` 是刻意的：宁可把个别打不出的字符显示成 `?`，
也不要让一次评测/对比因为一个装饰符号而整体失败。
"""

from __future__ import annotations

import sys


def use_utf8_console() -> None:
    """把 stdout/stderr 重新包成 UTF-8，并让无法编码的字符降级成 `?`。

    对已经支持 UTF-8 的平台是**无操作**（`reconfigure` 幂等），因此可以放心在
    每个脚本入口无条件调用。
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:  # pragma: no cover - 只在非常规流（如被替换过的对象）上出现
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):  # pragma: no cover - 流已关闭时忽略
            continue
