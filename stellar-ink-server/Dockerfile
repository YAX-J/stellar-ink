# syntax=docker/dockerfile:1
# =============================================================================
# 星笺 STELLAR INK 后端统一镜像
# - 第一阶段：Maven 全 reactor 构建（BuildKit cache mount 复用 ~/.m2，
#   7 个服务共用本文件构建时编译只执行一次，之后各阶段仅按 JAR_PATH 拷贝各自 fat jar）
# - 第二阶段：JRE 运行时，通过 build arg JAR_PATH 区分服务
#   （jar 名由各模块 pom 的 <finalName> 决定，如 user-service/target/user-service.jar）
# =============================================================================
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先单独拷贝各模块 pom，利用层缓存预热依赖（源码变更不触发重新下载）
COPY stellar-ink-server/pom.xml ./
COPY stellar-ink-server/common-components/pom.xml common-components/
COPY stellar-ink-server/common-components/shared-model/pom.xml common-components/shared-model/
COPY stellar-ink-server/common-components/common-core/pom.xml common-components/common-core/
COPY stellar-ink-server/common-components/service-api/pom.xml common-components/service-api/
COPY stellar-ink-server/gateway-nacos-sentinel/pom.xml gateway-nacos-sentinel/
COPY stellar-ink-server/user-service/pom.xml user-service/
COPY stellar-ink-server/post-service/pom.xml post-service/
COPY stellar-ink-server/meteor-service/pom.xml meteor-service/
COPY stellar-ink-server/echo-service/pom.xml echo-service/
COPY stellar-ink-server/link-service/pom.xml link-service/
COPY stellar-ink-server/stats-service/pom.xml stats-service/
# 尽力预热依赖（go-offline 无法解析全部插件，失败不影响后续构建）
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests dependency:go-offline || true

COPY stellar-ink-server/ ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -DskipTests package

# -----------------------------------------------------------------------------
# 运行时镜像
# -----------------------------------------------------------------------------
FROM eclipse-temurin:17-jre-jammy

ARG JAR_PATH
ENV TZ=Asia/Shanghai

# curl 供 compose healthcheck 使用
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl tzdata \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=builder /build/${JAR_PATH} /app/app.jar

# JAVA_OPTS 由 docker-compose 注入（配合 mem_limit 控制堆上限）
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
