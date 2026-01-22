# Serial Port Plugin 开发调试指南

## 📋 环境要求

| 环境 | 要求 |
|------|------|
| JDK | 17+ |
| Gradle | 8.5 (自动下载) |
| IDE | IntelliJ IDEA / Android Studio |
| 代理 | 需要能访问 JetBrains 和 Maven 仓库 |

---

## 🚀 快速开始

### Windows

```powershell
# 双击 run.bat 或在终端执行：
.\run.bat
```

### macOS / Linux

```bash
# 添加执行权限（首次）
chmod +x run.sh

# 运行
./run.sh
```

---

## 📁 项目结构

```
serial-port-plugin/
├── src/main/
│   ├── kotlin/com/serialport/plugin/
│   │   ├── SerialPortService.kt      # 核心串口服务
│   │   ├── SerialPortToolWindow.kt   # 主界面 UI
│   │   ├── CommandManager.kt         # 快捷指令管理
│   │   ├── CommandDialog.kt          # 指令管理对话框
│   │   └── SerialPortSettings.kt     # 配置持久化
│   └── resources/
│       ├── icons/                    # 插件图标
│       └── META-INF/plugin.xml       # 插件配置
├── build.gradle.kts                  # 构建配置
├── gradle.properties                 # Gradle 属性
├── run.bat                           # Windows 启动脚本
├── run.sh                            # Unix 启动脚本
└── docs/DEVELOPMENT.md               # 本文档
```

---

## 🔧 调试方式

### 方式 1：命令行运行（快速测试）

直接运行启动脚本，会编译并启动沙箱 IDE：

```bash
# Windows
.\run.bat

# macOS / Linux
./run.sh
```

**特点**：
- ✅ 快速启动
- ✅ 测试插件功能
- ❌ 无法设置断点

### 方式 2：IDE Debug 模式（断点调试）

1. 用 IntelliJ IDEA 打开项目
2. 配置运行配置：`Run` → `Edit Configurations` → `+` → `Gradle`
   - Name: `Run Plugin`
   - Gradle project: `serial-port-plugin`
   - Tasks: `runIde`
3. 在代码中设置断点
4. 点击 🐛 Debug 按钮启动

**特点**：
- ✅ 可设置断点
- ✅ 查看变量值
- ✅ 单步执行

---

## 📝 常用 Gradle 命令

| 命令 | 说明 |
|------|------|
| `gradlew runIde` | 编译并启动沙箱 IDE |
| `gradlew clean runIde` | 清理后重新编译启动 |
| `gradlew buildPlugin` | 构建插件 zip 安装包 |
| `gradlew verifyPlugin` | 验证插件配置 |
| `gradlew test` | 运行测试 |

---

## ⚙️ 环境配置

### 1. JDK 配置

项目需要 JDK 17。可以使用：
- OpenJDK 17
- Android Studio 自带的 JBR（需完整版）

Windows 安装 OpenJDK 17：
```powershell
winget install ojdkbuild.openjdk.17.jdk
```

### 2. 代理配置

如果需要代理访问网络，修改 `gradle.properties`：

```properties
# HTTP 代理
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897

# HTTPS 代理
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
```

或在启动脚本中设置环境变量。

---

## 🐛 常见问题

### Q: 构建失败 "Port not found"

**原因**：串口名包含描述信息
**解决**：代码已修复，会自动提取纯端口名

### Q: 文件被锁定无法编译

**原因**：沙箱 IDE 还在运行
**解决**：关闭沙箱 IDE 窗口后重试

### Q: 找不到 JAVA_HOME

**解决**：
1. 安装 JDK 17
2. 设置 JAVA_HOME 环境变量
3. 或使用启动脚本（已内置 JAVA_HOME 配置）

### Q: 网络超时

**解决**：
1. 配置代理
2. 检查 VPN 是否正常
3. 尝试切换网络

---

## 📦 发布插件

### 构建安装包

```bash
gradlew buildPlugin
```

生成文件位置：`build/distributions/serial-port-plugin-1.0.0.zip`

### 本地安装

1. 打开 Android Studio / IntelliJ IDEA
2. `Settings` → `Plugins` → ⚙️ → `Install Plugin from Disk...`
3. 选择生成的 zip 文件
4. 重启 IDE

### 发布到 JetBrains Marketplace

```bash
# 需要配置 PUBLISH_TOKEN 环境变量
gradlew publishPlugin
```

---

## 🔗 相关链接

- [IntelliJ Platform SDK 文档](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [jSerialComm 文档](https://github.com/Fazecast/jSerialComm)
- [Gradle IntelliJ Plugin](https://github.com/JetBrains/gradle-intellij-plugin)
