# LANChat - 局域网 P2P 即时通讯系统

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java: 17+](https://img.shields.io/badge/Java-17+-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)

LANChat 是一个基于 P2P 架构的局域网即时通讯系统。它不需要中心服务器，通过局域网内的节点自动发现和点对点通信技术，实现安全、高效的消息传输和文件互传。

## 🌟 主要功能

- **节点自动发现**：利用 UDP 广播协议，自动识别局域网内的其他在线用户。
- **即时消息传输**：基于 TCP 协议实现稳定可靠的点对点私聊和群聊。
- **逻辑时钟同步**：采用 Lamport Clock 解决分布式环境下的消息偏序问题，确保消息顺序一致。
- **离线消息同步**：支持群组消息的增量同步，确保在重新上线后能获取错过的对话。
- **安全文件互传**：支持大文件（最高 2GB）的分片上传、SHA-256 完整性校验。
- **本地持久化**：使用嵌入式 SQLite 数据库存储聊天记录、用户信息和文件元数据。
- **现代化 Web UI**：内置简洁易用的 Web 界面，支持 Markdown 渲染和文件一键下载。

## 🛠️ 技术栈

- **后端**: Java 17, SparkJava (Web 框架), Gson (JSON 处理)
- **数据库**: SQLite (通过 JDBC)
- **网络协议**: TCP (消息传输), UDP Broadcast (节点发现)
- **前端**: 原生 JavaScript, HTML5, CSS3 (Glassmorphism 设计风格)
- **构建工具**: Maven

## 🚀 安装指南

### 前置要求

- **Java Development Kit (JDK) 17** 或更高版本
- **Apache Maven 3.6** 或更高版本

### 安装步骤

1. **克隆仓库**
   ```bash
   git clone https://github.com/your-username/InstantMessenger.git
   cd InstantMessenger
   ```

2. **构建项目**
   使用 Maven 打包项目，生成包含所有依赖的 JAR 文件：
   ```bash
   mvn clean package
   ```
   构建完成后，可执行文件位于 `target/lanchat-core-1.0-SNAPSHOT.jar`。

## 📖 使用说明

### 启动应用

您可以直接使用 `dist` 目录下的启动脚本，或通过命令行手动启动：

**命令行启动示例：**
```bash
# 启动第一个节点 (NodeA)
java -jar target/lanchat-core-1.0-SNAPSHOT.jar --p2pPort 19001 --webPort 18081 --name NodeA --db lanchat_A.db

# 启动第二个节点 (NodeB)
java -jar target/lanchat-core-1.0-SNAPSHOT.jar --p2pPort 19002 --webPort 18082 --name NodeB --db lanchat_B.db
```

### 访问 Web 界面

打开浏览器访问以下地址即可开始聊天：
- NodeA: `http://localhost:18081`
- NodeB: `http://localhost:18082`

### 基本用法示例

1. **发现好友**：启动后，应用会自动扫描局域网。几秒钟内，在线的好友将出现在左侧“在线用户”列表中。
2. **私聊**：点击用户头像即可开始私密对话。消息会实时同步，并显示“已送达”状态。
3. **创建群组**：点击“新建群组”按钮，输入群名称，创建后将生成的 `Room ID` 发送给好友。
4. **加入群组**：点击“加入群组”，输入 `Room ID` 和邀请人的 `IP:Port` 即可进入群聊。
5. **文件互传**：在侧边栏“文件互传”区域，直接将文件拖入或点击上传，对方可在聊天窗口或文件库中一键下载。

## 🤝 贡献指南

我们非常欢迎社区的贡献！如果您有任何改进建议或发现了 Bug，请按照以下流程操作：

1. **Fork** 本项目。
2. **创建您的特性分支** (`git checkout -b feature/AmazingFeature`)。
3. **提交您的更改** (`git commit -m 'Add some AmazingFeature'`)。
4. **推送到分支** (`git push origin feature/AmazingFeature`)。
5. **开启一个 Pull Request**。

请确保您的代码符合项目的 Java 编码规范，并附带必要的单元测试。

## 📄 许可证信息

本项目采用 **MIT 许可证**。详情请参阅 [LICENSE](file:///Users/huwenkai/source/InstantMessenger/LICENSE) 文件。

---
*注：本项目主要用于局域网环境。在使用文件传输功能时，请确保网络连接稳定以获得最佳体验。*
