"""单测显式使用离线语料，避免本机 .env 将测试带到真实数据库与内容服务。"""

import pytest

from app.rag import corpus


@pytest.fixture(autouse=True)
def offline_corpus(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("AI_CORPUS_SOURCE", "seed")
    monkeypatch.setenv("AI_APP_ENV", "dev")
    monkeypatch.delenv("AI_RETRIEVAL_PROFILE_PATH", raising=False)
    corpus.reset_corpus()
