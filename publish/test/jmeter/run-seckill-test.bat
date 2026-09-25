@echo off
REM ============================================================
REM 犇牛商城商城 - 秒杀 3000 并发压测（一键）
REM
REM 前置条件：
REM   1. 中间件已就绪：MySQL/Redis/RabbitMQ/Nacos 在 ${MW_HOST}
REM   2. seckill-service(8085) 与 gateway(8080) 已启动并注册到 Nacos
REM   3. JMeter 已安装（默认路径见下方 JMETER_HOME，可按实际改）
REM
REM 说明：真正的压测逻辑都在 Python 脚本里，
REM   * 本批处理只在 Git-Bash 之外的 cmd 环境提供入口；
REM   * JMeter 用 Python 拉起而不是直接调 jmeter.bat ——
REM     Git-Bash 下 `/n` `/t` 会被 MSYS 当路径改写，导致参数失效。
REM ============================================================
setlocal

set JMETER_HOME=${JMETER_HOME}
set PYTHON=${HOME}\.workbuddy\binaries\python\versions\3.13.12\python.exe
set VOUCHER_ID=1
set STOCK=3000

REM 若 JMeter 路径不同，改这里；也支持环境变量覆盖
if not "%JMETER_HOME%"=="" set JMETER_HOME=%JMETER_HOME%

cd /d "%~dp0\..\.."

echo ============================================================
echo [1/3] 复位压测数据（Redis 库存/已下单集合 + MySQL 订单与库存）
echo ============================================================
"%PYTHON%" test\jmeter\prepare-seckill-loadtest.py --stock %STOCK% --voucher-id %VOUCHER_ID%
if errorlevel 1 (
    echo [ERROR] 数据复位失败，已中止
    pause
    exit /b 1
)

echo ============================================================
echo [2/3] 执行 3000 并发压测（JMeter headless）
echo ============================================================
set JMETER_HOME=%JMETER_HOME%
"%PYTHON%" test\jmeter\run-jmeter.py --jmx seckill-3000-concurrent.jmx --tag full3000

echo ============================================================
echo [3/3] 验收：零超卖 + 一人一单 + 库存精确扣减
echo ============================================================
"%PYTHON%" test\jmeter\verify-seckill-loadtest.py ^
    --jtl test\jmeter\out\full3000\result.jtl --expect %STOCK% --stock %STOCK%

echo.
echo HTML 报告: test\jmeter\out\full3000\html\index.html
pause
