# Serial Port Tool Plugin for Android Studio

一个功能完整的 Android Studio / IntelliJ IDEA 串口通信工具插件。

## ✨ 功能特性

### 🔌 串口管理
- 自动扫描系统可用串口
- 快速连接/断开串口设备
- 支持自动重连功能
- 实时显示连接状态

### 📤 数据收发
- **ASCII 模式**: 发送和接收文本数据
- **HEX 模式**: 发送和接收十六进制数据
- 实时显示发送和接收的数据
- 支持时间戳显示

### 📺 数据显示
- 实时显示串口通信数据
- 可选时间戳显示(精确到毫秒)
- 自动滚动到最新数据
- 清空接收区功能
- 发送/接收数据区分显示

### ⚡ 快捷指令
- 保存常用串口命令
- 支持 ASCII 和 HEX 格式命令
- 一键发送常用指令
- 命令管理(添加/编辑/删除)
- 支持命令描述说明

### 📝 日志导出
- 导出完整通信日志
- 自动生成时间戳文件名
- 保存为 UTF-8 文本格式
- 包含所有发送和接收记录

### ⚙️ 串口配置
- **波特率**: 9600, 19200, 38400, 57600, 115200
- **数据位**: 8 位(默认)
- **停止位**: 1 位(默认)
- **校验位**: 无校验(默认)
- 配置自动保存

## 🚀 安装方法

### 方法 1: 从源码构建

1. **克隆项目**
```bash
git clone <repository-url>
cd serial-port-plugin
```

2. **构建插件**
```bash
./gradlew buildPlugin
```

构建完成后,插件文件位于: `build/distributions/serial-port-plugin-1.0.0.zip`

3. **安装到 Android Studio**
- 打开 Android Studio
- 进入 `Settings/Preferences` → `Plugins`
- 点击 ⚙️ 图标 → `Install Plugin from Disk...`
- 选择生成的 zip 文件
- 重启 Android Studio

### 方法 2: 从 JetBrains Marketplace 安装 (待发布)

1. 打开 Android Studio
2. 进入 `Settings/Preferences` → `Plugins`
3. 搜索 "Serial Port Tool"
4. 点击 `Install`
5. 重启 Android Studio

## 📖 使用指南

### 基本使用流程

1. **打开工具窗口**
   - 点击底部工具栏的 `Serial Port` 标签
   - 或通过菜单: `View` → `Tool Windows` → `Serial Port`

2. **连接串口**
   - 在串口下拉框中选择目标串口
   - 选择合适的波特率(默认 9600)
   - 点击 `连接` 按钮
   - 连接成功后按钮变为 `断开`

3. **发送数据**
   - 在底部输入框输入要发送的数据
   - 选择 ASCII 或 HEX 模式
   - 按回车或点击 `发送` 按钮

4. **接收数据**
   - 接收到的数据会自动显示在中间区域
   - 可以选择显示/隐藏时间戳
   - 支持自动滚动到最新数据

### 快捷指令使用

1. **添加命令**
   - 点击 `快捷指令` 按钮打开管理对话框
   - 输入命令名称和命令内容
   - 选择是否为 HEX 格式
   - 添加描述(可选)
   - 点击 `添加` 保存

2. **使用命令**
   - 在左侧列表选择已保存的命令
   - 点击 `执行` 按钮直接发送
   - 或双击命令名称快速执行

3. **管理命令**
   - 选择命令后可以 `更新` 或 `删除`
   - 支持修改命令内容和格式

### 日志导出

1. 点击 `导出日志` 按钮
2. 选择保存位置和文件名
3. 日志会以 UTF-8 格式保存
4. 包含完整的发送/接收记录和时间戳

### 自动重连

1. 勾选 `自动重连` 复选框
2. 连接串口
3. 如果连接意外断开,会自动尝试重连
4. 每 5 秒重试一次

## 🛠️ 开发说明

### 技术栈

- **语言**: Kotlin 1.9.21
- **平台**: IntelliJ Platform SDK 2023.2
- **串口库**: jSerialComm 2.10.4
- **构建工具**: Gradle 8.5

### 项目结构

```
serial-port-plugin/
├── src/main/
│   ├── kotlin/com/serialport/plugin/
│   │   ├── SerialPortService.kt          # 核心串口服务
│   │   ├── SerialPortToolWindow.kt       # 主界面UI
│   │   ├── CommandManager.kt             # 快捷指令管理
│   │   ├── CommandDialog.kt              # 指令管理对话框
│   │   └── SerialPortSettings.kt         # 配置持久化
│   └── resources/
│       └── META-INF/
│           └── plugin.xml                # 插件配置
├── build.gradle.kts                      # 构建配置
├── settings.gradle.kts                   # 项目设置
└── README.md                             # 本文档
```

### 核心类说明

#### SerialPortService
核心串口通信服务,提供:
- 串口扫描和连接管理
- 数据发送和接收
- 自动重连机制
- 事件监听和通知

#### SerialPortToolWindow
插件主界面,包含:
- 串口选择和连接控制
- 数据显示区域
- 发送输入框
- 各种选项开关

#### CommandManager
快捷指令管理器,支持:
- 命令的增删改查
- 持久化存储
- 命令导入导出

### 构建命令

```bash
# 构建插件
./gradlew buildPlugin

# 运行插件(在沙箱环境)
./gradlew runIde

# 验证插件
./gradlew verifyPlugin

# 发布到 JetBrains Marketplace
./gradlew publishPlugin
```

### 开发环境要求

- JDK 17 或更高版本
- Gradle 8.5+
- Android Studio 2023.2+ 或 IntelliJ IDEA 2023.2+

## 📋 系统要求

- **操作系统**: Windows, macOS, Linux
- **IDE**: Android Studio 2023.2+ 或 IntelliJ IDEA 2023.2+
- **JDK**: 17+

## 🐛 常见问题

### Q: 找不到串口设备?
A: 
- 确认设备已正确连接
- 检查驱动是否安装
- 点击 `刷新` 按钮重新扫描
- Linux 下可能需要添加用户到 dialout 组: `sudo usermod -a -G dialout $USER`

### Q: 连接失败?
A:
- 确认串口未被其他程序占用
- 检查波特率等参数是否正确
- 尝试断开后重新连接
- 查看错误消息提示

### Q: 接收不到数据?
A:
- 确认串口参数(波特率/数据位等)匹配
- 检查设备是否正在发送数据
- 确认连接状态正常
- 尝试重新连接

### Q: HEX 模式发送格式?
A:
- 输入格式: `01 02 03 FF` 或 `010203FF`
- 空格会被自动忽略
- 必须是有效的十六进制字符(0-9, A-F)

## 📄 许可证

MIT License

Copyright (c) 2024 Serial Port Tool Team

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 🤝 贡献

欢迎提交 Issue 和 Pull Request!

1. Fork 项目
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 开启 Pull Request

## 📧 联系方式

- 项目主页: https://github.com/serialport/plugin
- 问题反馈: https://github.com/serialport/plugin/issues
- 邮箱: support@serialport.com

## 🙏 致谢

- [jSerialComm](https://github.com/Fazecast/jSerialComm) - 优秀的跨平台串口通信库
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html) - 强大的插件开发平台
- 所有贡献者和用户的支持

---

**享受串口调试的乐趣! 🚀**
