"""`base_url` 安全策略（用户级配置的 SSRF 闸门）。

`base_url` 是**服务端拿去发请求**的地址 —— 只让站长填时它只是配置，
一旦读者/作者也能填，任何人都能让 Python 进程去打内网（云元数据、Nacos、内网服务），
而报错还会把响应内容带回来。这份用例盯的就是那条线：

* 站长（全局配置）：**允许** loopback/私网（自建 vLLM 就在 `127.0.0.1:8000`）；
* 用户（个人配置）：只允许公网；
* **两档都拒绝链路本地**（169.254.0.0/16）—— 那是云元数据，不是模型端点。
"""

from __future__ import annotations

import pytest

from app.providers.errors import InvalidBaseUrlError
from app.providers.url_policy import check_base_url


@pytest.mark.parametrize(
    "url",
    [
        "https://dashscope.aliyuncs.com/compatible-mode/v1",
        "https://api.deepseek.com/v1",
        # 公网 IP 字面量。⚠️ 别用 203.0.113.x / 198.51.100.x：那是文档保留段，
        # `ipaddress.is_private` 对它为 True —— 拿它当「公网」写用例会得到一条假结论
        "http://1.1.1.1:8000/v1",
    ],
)
def test_public_addresses_pass_for_everyone(url: str) -> None:
    check_base_url(url, allow_private=False)
    check_base_url(url, allow_private=True)


@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1:8000/v1",
        "http://localhost:11434/v1",
        "http://10.0.0.5:8000/v1",
        "http://192.168.1.20/v1",
        "http://172.16.0.9/v1",
        "http://[::1]:8000/v1",
        "http://ollama.internal/v1",
        "http://box.local/v1",
    ],
)
def test_private_addresses_are_allowed_only_for_the_admin(url: str) -> None:
    """自建推理就在本机/内网：站长填必须放行，否则最正当的用法被堵死。"""
    check_base_url(url, allow_private=True)

    with pytest.raises(InvalidBaseUrlError):
        check_base_url(url, allow_private=False)


@pytest.mark.parametrize(
    "url",
    [
        "http://169.254.169.254/latest/meta-data/",
        "http://169.254.0.1/v1",
        "http://[fe80::1]:8000/v1",
    ],
)
def test_link_local_is_rejected_even_for_the_admin(url: str) -> None:
    """链路本地一律拒绝：那是云元数据/自动配置网段，没有模型端点会在这儿。"""
    for allow_private in (True, False):
        with pytest.raises(InvalidBaseUrlError):
            check_base_url(url, allow_private=allow_private)


def test_cloud_metadata_hostname_is_rejected() -> None:
    with pytest.raises(InvalidBaseUrlError):
        check_base_url("http://metadata.google.internal/v1", allow_private=True)


@pytest.mark.parametrize("url", ["ftp://api.example.com/v1", "api.example.com/v1", "://x"])
def test_scheme_must_be_http_or_https(url: str) -> None:
    with pytest.raises(InvalidBaseUrlError):
        check_base_url(url, allow_private=True)


def test_credentials_in_url_are_rejected() -> None:
    """把凭据写进 URL 会被日志与审计记录下来 —— 我们宁可让他填到 Key 那一栏。"""
    with pytest.raises(InvalidBaseUrlError, match="不要带用户名"):
        check_base_url("https://user:pass@api.example.com/v1", allow_private=True)


def test_empty_and_hostless_urls_are_rejected() -> None:
    for url in ("", "   ", "https://"):
        with pytest.raises(InvalidBaseUrlError):
            check_base_url(url, allow_private=True)


def test_unresolvable_hostname_is_not_blocked(monkeypatch: pytest.MonkeyPatch) -> None:
    """解析不了**不拦**：DNS 没生效/离线开发都可能这样。

    那种情况下用户该看到的是「连不上」，而不是一句莫名其妙的「地址不合法」。
    """
    import socket

    def boom(*_args: object, **_kwargs: object) -> object:
        raise OSError("DNS 挂了")

    monkeypatch.setattr(socket, "getaddrinfo", boom)

    check_base_url("https://not-resolvable.example/v1", allow_private=False)


def test_hostname_resolving_to_private_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    """公网域名指向 10.x 是最常见的一种绕过：解析一次就能挡住。"""
    import socket

    def fake_getaddrinfo(*_args: object, **_kwargs: object) -> list[tuple[object, ...]]:
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", ("10.1.2.3", 0))]

    monkeypatch.setattr(socket, "getaddrinfo", fake_getaddrinfo)

    with pytest.raises(InvalidBaseUrlError, match="解析到内网"):
        check_base_url("https://sneaky.example.com/v1", allow_private=False)
    # 站长填的照旧放行（他自己的内网服务就是这种形态）
    check_base_url("https://sneaky.example.com/v1", allow_private=True)
