#!/bin/bash

# ========================================
#   Serial Port Plugin 开发脚本
#   用法: ./dev.sh [命令]
#   命令: run / rebuild / build / clean
# ========================================

# 设置 JDK
if [[ "$OSTYPE" == "darwin"* ]]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
elif [[ "$OSTYPE" == "linux-gnu"* ]]; then
    export JAVA_HOME="/usr/lib/jvm/java-17-openjdk"
fi

# 设置代理
export HTTP_PROXY="http://127.0.0.1:7897"
export HTTPS_PROXY="http://127.0.0.1:7897"

# 确保 gradlew 有执行权限
chmod +x ./gradlew 2>/dev/null

case "${1:-run}" in
    run)
        echo "[启动] 编译并启动沙箱 IDE..."
        ./gradlew runIde
        ;;
    rebuild)
        echo "[热更新] 重新编译插件..."
        ./gradlew classes instrumentCode prepareSandbox
        if [ $? -eq 0 ]; then
            echo "[成功] 编译完成，沙箱 IDE 会自动重载"
        else
            echo "[错误] 编译失败"
        fi
        ;;
    build)
        echo "[打包] 构建插件安装包..."
        ./gradlew buildPlugin
        if [ $? -eq 0 ]; then
            echo "[成功] 插件包位置: build/distributions/"
        fi
        ;;
    clean)
        echo "[清理] 清理构建文件..."
        ./gradlew clean
        ;;
    help|*)
        echo ""
        echo "Serial Port Plugin 开发脚本"
        echo "============================"
        echo ""
        echo "用法: ./dev.sh [命令]"
        echo ""
        echo "命令:"
        echo "  run      启动沙箱 IDE 调试 (默认)"
        echo "  rebuild  热更新编译"
        echo "  build    构建插件 zip 包"
        echo "  clean    清理构建文件"
        echo "  help     显示帮助"
        echo ""
        ;;
esac
