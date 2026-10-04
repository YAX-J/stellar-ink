"""`base_url` 的安全策略（用户级配置必须过这一关）。

**为什么这件事必须做**：`base_url` 是**服务端**拿着去发请求的地址。站长填错只是自己的事，
但一旦让读者/作者也能填，任何人都能让 Python 进程去打内网 ——
`http://169.254.169.254/latest/meta-data/`（云元数据）、`http://10.0.0.5:8080/`（内网服务）、
`http://127.0.0.1:8848/`（Nacos），报错信息还会把响应内容带回来。这就是 SSRF。

规则分两档，判据是**谁填的**：

* **站长填的（全局配置）**：允许 loopback 与内网地址 —— 自建 vLLM / Ollama 就在
  `http://127.0.0.1:8000/v1`，禁掉等于把最正当的用法堵死。但**链路本地（169.254.0.0/16）
  一律拒绝**：那是云元数据服务，任何模型端点都不该在那儿。
* **用户填的（个人配置）**：只允许公网地址，loopback / 私网 / 链路本地 / 保留段全部拒绝，
  并且**尽量解析一次域名**，解析到内网也拒绝（挡掉「公网域名指向 10.x」这类绕过）。

⚠️ **诚实的残留风险**：DNS rebinding（检查时解析到公网、请求时解析到内网）在配置期拦不住。
根治要靠在**网络层**限制 Python 的出网范围（容器 egress 策略 / 安全组），
这条策略只是「能让普通用户随便填时的第一道闸」，不是全部。
"""

from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlsplit

from app.providers.errors import InvalidBaseUrlError

#: 兼容旧名：本模块此前用 InvalidBaseUrlError（定义在 config_source，会造成 import 环）
InvalidBaseUrlError = InvalidBaseUrlError

#: 云元数据服务的地址：**任何一档都拒绝**（没有模型端点会部署在这里）
METADATA_HOSTS = frozenset({"metadata.google.internal", "metadata.goog"})

#: 一看就不该是模型端点的主机名后缀（内网惯用名）
PRIVATE_SUFFIXES = (".localhost", ".local", ".internal", ".home.arpa")


def check_base_url(url: str, *, allow_private: bool) -> None:
    """校验一个 `base_url`；不合法就抛 `InvalidBaseUrlError`（消息可直接给用户看）。

    :param allow_private: 是否允许内网/loopback（**只有站长填的全局配置才为 True**）
    """
    raw = (url or "").strip()
    if not raw:
        raise InvalidBaseUrlError(
            "接口地址不能为空", detail="填 API 根地址，例如 https://api.example.com/v1"
        )

    parts = urlsplit(raw)
    if parts.scheme not in ("http", "https"):
        raise InvalidBaseUrlError(
            f"接口地址必须以 http:// 或 https:// 开头（当前是 {parts.scheme or '空'}）"
        )
    if parts.username or parts.password:
        raise InvalidBaseUrlError(
            "接口地址里不要带用户名/密码",
            detail="凭据请填在 API Key 一栏；把凭据写进 URL 会被日志与审计记录下来",
        )
    host = (parts.hostname or "").strip().lower()
    if not host:
        raise InvalidBaseUrlError("接口地址里没有主机名")

    if host in METADATA_HOSTS:
        # 连站长也不给这个口子：它不是模型端点
        raise InvalidBaseUrlError("该地址是云元数据服务，不能作为模型端点")

    literal = _as_ip(host)
    if literal is not None:
        _check_ip(literal, host=host, allow_private=allow_private)
        return

    # 主机名形态：先看惯用名，再（在只允许公网时）解析一次
    if host == "localhost" or host.endswith(PRIVATE_SUFFIXES):
        if not allow_private:
            raise InvalidBaseUrlError(
                f"「{host}」看起来是内网地址，个人配置只能填公网地址",
                detail="要用自建推理服务请让站长在「AI 实验室」里配成全局模型",
            )
        return
    if not allow_private:
        _check_resolved(host)


def _check_ip(
    ip: ipaddress.IPv4Address | ipaddress.IPv6Address, *, host: str, allow_private: bool
) -> None:
    # 链路本地（含 169.254.169.254）任何一档都拒绝
    if ip.is_link_local:
        raise InvalidBaseUrlError(
            f"「{host}」属于链路本地地址，不能作为模型端点",
            detail="这一网段是云元数据/自动配置用的，不是模型服务",
        )
    if allow_private:
        return
    if ip.is_loopback or ip.is_private or ip.is_reserved or ip.is_multicast or ip.is_unspecified:
        raise InvalidBaseUrlError(
            f"「{host}」是内网或本机地址，个人配置只能填公网地址",
            detail="要用自建推理服务请让站长在「AI 实验室」里配成全局模型",
        )


def _check_resolved(host: str) -> None:
    """解析域名，任一结果落在内网就拒绝。

    解析失败**不拦**：DNS 还没生效、离线开发都可能解析不了，
    而那种情况下的报错应该来自真正的调用（「连不上」），不是这里的一句「地址不合法」。
    """
    try:
        infos = socket.getaddrinfo(host, None)
    except OSError:
        return
    for info in infos:
        # `getaddrinfo` 的地址槽在类型标注上是 `str | int`（有些平台给整数形式）
        address = str(info[4][0])
        literal = _as_ip(address)
        if literal is None:
            continue
        if literal.is_link_local:
            raise InvalidBaseUrlError(f"「{host}」解析到链路本地地址，不能作为模型端点")
        if (
            literal.is_loopback
            or literal.is_private
            or literal.is_reserved
            or literal.is_unspecified
        ):
            raise InvalidBaseUrlError(
                f"「{host}」解析到内网地址（{address}），个人配置只能填公网地址"
            )


def _as_ip(host: str) -> ipaddress.IPv4Address | ipaddress.IPv6Address | None:
    try:
        return ipaddress.ip_address(host)
    except ValueError:
        return None
