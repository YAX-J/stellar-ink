"""HTTP 路由层：只做协议与参数校验，业务逻辑落在 ``app/rag`` / ``app/agents`` 等领域包。

``app/api/v1`` 内的 router 直接挂在应用上（Python 服务仅内网可达，
版本前缀与对外路径由 Java ``ai-service`` 决定，这里不重复一层 ``/v1``）。
"""
