@echo off
chcp 65001 >nul

:: ========================================
::   Serial Port Plugin 开发脚本
::   用法: dev.bat [命令]
::   命令: run / rebuild / build / clean
:: ========================================

set JAVA_HOME=C:\Program Files\ojdkbuild\java-17-openjdk-17.0.3.0.6-1
set HTTP_PROXY=http://127.0.0.1:7897
set HTTPS_PROXY=http://127.0.0.1:7897

if "%1"=="" goto run
if "%1"=="run" goto run
if "%1"=="rebuild" goto rebuild
if "%1"=="build" goto build
if "%1"=="clean" goto clean
if "%1"=="help" goto help
goto help

:run
echo [启动] 编译并启动沙箱 IDE...
call gradlew.bat runIde
goto end

:rebuild
echo [热更新] 重新编译插件...
call gradlew.bat classes instrumentCode prepareSandbox
if %ERRORLEVEL% equ 0 (
    echo [成功] 编译完成，沙箱 IDE 会自动重载
) else (
    echo [错误] 编译失败
)
goto end

:build
echo [打包] 构建插件安装包...
call gradlew.bat buildPlugin
if %ERRORLEVEL% equ 0 (
    echo [成功] 插件包位置: build\distributions\
)
goto end

:clean
echo [清理] 清理构建文件...
call gradlew.bat clean
goto end

:help
echo.
echo Serial Port Plugin 开发脚本
echo ============================
echo.
echo 用法: dev.bat [命令]
echo.
echo 命令:
echo   run      启动沙箱 IDE 调试 (默认)
echo   rebuild  热更新编译
echo   build    构建插件 zip 包
echo   clean    清理构建文件
echo   help     显示帮助
echo.
goto end

:end
