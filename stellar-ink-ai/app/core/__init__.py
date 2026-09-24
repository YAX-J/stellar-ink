"""核心基础设施：配置、日志、traceId、错误模型。

分层约定见 docs/ai/implementation-roadmap.md §4：``core`` 只放与业务无关的地基，
不 import 任何 ``rag`` / ``agents`` / ``providers`` 模块，避免反向依赖。
"""
