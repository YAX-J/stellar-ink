@echo off
REM 星笺微服务一键启动（Windows）
REM 前置：1) 已执行 mvn -DskipTests package  2) 本机 MySQL 可用（生产 profile 需要）
REM 可选环境变量：NACOS_ADDR / MYSQL_HOST / MYSQL_PORT / MYSQL_DB / MYSQL_USER / MYSQL_PASSWORD

setlocal
set BASE=%~dp0..\..
set JAVAVM="C:\Program Files\Java\jdk-17.0.18\bin\java.exe"
if defined JAVA_HOME set JAVAVM="%JAVA_HOME%\bin\java.exe"

echo [1/3] 启动 Nacos 注册中心（standalone）...
start "nacos" cmd /k "%BASE%\tools\nacos\bin\startup.cmd -m standalone"
timeout /t 15 /nobreak >nul

echo [2/3] 启动业务服务（user/post/meteor/echo/link/stats）...
start "user-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-service-user\target\stellar-ink-service-user.jar
start "post-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-service-post\target\stellar-ink-service-post.jar
start "meteor-service" cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-service-meteor\target\stellar-ink-service-meteor.jar
start "echo-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-service-echo\target\stellar-ink-service-echo.jar
start "link-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-service-link\target\stellar-ink-service-link.jar
start "stats-service"  cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-service-stats\target\stellar-ink-service-stats.jar
timeout /t 12 /nobreak >nul

echo [3/3] 启动网关（8080 对外入口）...
start "gateway" cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stellar-ink-gateway\target\stellar-ink-gateway.jar

echo.
echo 全部启动完成：网关 http://localhost:8080  Nacos 控制台 http://localhost:8848/nacos
endlocal
