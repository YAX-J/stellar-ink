"""标定只对通过独立验收的同模型、同语料生效。"""

from dataclasses import replace

import pytest

from app.providers.config_source import ProviderConfigError
from app.providers.models import ProviderConfig
from app.rag.pipeline import IndexedChunk, RetrievalConfig
from app.rag.retrieval_profile import RetrievalProfile, apply_profile, corpus_fingerprint


@pytest.fixture()
def configured(tmp_path, monkeypatch):
    embedding = ProviderConfig("embedding", "fake", "", "已标定的模型")
    chunks = [IndexedChunk("p1:c0", 1, "公开内容")]
    profile = RetrievalProfile(
        embedding_fingerprint=embedding.fingerprint(),
        corpus_fingerprint=corpus_fingerprint(chunks),
        min_score=15, min_dense_score=.5, validated=True,
    )
    path = tmp_path / "profile.json"
    path.write_text(profile.model_dump_json(), encoding="utf-8")
    monkeypatch.setenv("AI_RETRIEVAL_PROFILE_PATH", str(path))
    return embedding, chunks, path


def test_validated_profile_applies_both_thresholds(configured) -> None:
    embedding, chunks, _ = configured
    result = apply_profile(RetrievalConfig(label="qa"), chunks, embedding)
    assert result.min_score == 15
    assert result.min_dense_score == .5
    assert result.enable_rerank is False


def test_changed_model_or_corpus_never_reuses_old_thresholds(configured, caplog) -> None:
    embedding, chunks, _ = configured
    original = RetrievalConfig()
    assert apply_profile(original, chunks, replace(embedding, model="新模型")) == original
    changed = [replace(chunks[0], text="新版正文")]
    assert apply_profile(original, changed, embedding) == original
    assert "标定已失效" in caplog.text


def test_failed_acceptance_or_missing_profile_is_explicit_error(configured) -> None:
    embedding, chunks, path = configured
    path.write_text(path.read_text(encoding="utf-8").replace('true', 'false'), encoding="utf-8")
    with pytest.raises(ProviderConfigError, match="标定文件无效"):
        apply_profile(RetrievalConfig(), chunks, embedding)
    path.unlink()
    with pytest.raises(ProviderConfigError):
        apply_profile(RetrievalConfig(), chunks, embedding)
