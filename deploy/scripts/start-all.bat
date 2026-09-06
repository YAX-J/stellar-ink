@echo off
REM 星笺微服务一键启动（Windows）
REM 前置：1) 已执行 mvn -DskipTests package  2) 本机 MySQL（stellar_ink 库已初始化）  3) tools\nacos 已就位
REM 可选环境变量：NACOS_ADDR / MYSQL_HOST / MYSQL_PORT / MYSQL_DB / MYSQL_USER / MYSQL_PASSWORD / SA_TOKEN_JWT_SECRET

setlocal
set BASE=%~dp0..\..
set JAVAVM="C:\Program Files\Java\jdk-17.0.18\bin\java.exe"
if defined JAVA_HOME set JAVAVM="%JAVA_HOME%\bin\java.exe"

echo [1/3] 启动 Nacos 注册与配置中心（standalone）...
start "nacos" cmd /k "%BASE%\tools\nacos\bin\startup.cmd -m standalone"
timeout /t 15 /nobreak >nul

echo [2/3] 启动业务服务（user/post/meteor/echo/link/stats）...
start "user-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\user-service\target\user-service.jar
start "post-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\post-service\target\post-service.jar
start "meteor-service" cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\meteor-service\target\meteor-service.jar
start "echo-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\echo-service\target\echo-service.jar
start "link-service"   cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\link-service\target\link-service.jar
start "stats-service"  cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\stats-service\target\stats-service.jar
timeout /t 15 /nobreak >nul

echo [3/3] 启动网关（8080 对外入口）...
start "gateway" cmd /k %JAVAVM% -jar %BASE%\stellar-ink-server\gateway-nacos-sentinel\target\gateway-nacos-sentinel.jar

echo.
echo 全部启动完成：网关 http://localhost:8080  Nacos 控制台 http://localhost:8848/nacos
endlocal
