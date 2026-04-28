# Searchable Encryption (Java)

一个面向教学与实验的“可搜索加密”桌面系统，使用 Java Swing + TLS Socket + MySQL 实现完整链路：

- 用户认证：注册 / 登录 / 会话管理
- 文档上传：文本或文件加密后入库
- 关键词检索：通过 trapdoor 在服务端执行密文匹配
- 文档管理：列表、下载、删除、重建索引
- 客户端体验：异步任务、进度提示、窗口动态缩放

## 项目目标

该项目重点展示三件事：

- 如何将“加密存储 + 可搜索”串成可运行系统，而不仅是算法片段
- 如何在客户端和服务端之间设计统一消息协议并保持错误可观测
- 如何在桌面 UI 中保证耗时任务不卡界面（SwingWorker + Busy 状态管理）

## 技术栈

- 语言与构建：Java 17, Maven
- 客户端：Swing, FlatLaf
- 服务端通信：TLS Socket + Java 序列化对象流
- 存储：MySQL 8.x
- 文档解析：PDFBox, Apache POI
- 测试：JUnit

## 系统架构

整体为“单客户端进程 + 单服务端进程 + MySQL”结构：

- `AdminClientApp` 负责客户端主窗口、登录流程、Tab 装配与全局状态协调
- `Server` 负责 TLS 监听、会话鉴权、请求分发与数据读写
- `DocumentServiceClient` 封装协议发送与响应读取
- `DocumentOperationService` 处理上传/索引重建等业务编排
- `EncryptedDataRepository` / `UserRepository` 负责数据库访问

> 客户端默认连接 `127.0.0.1:12345`。若本地未检测到服务端，客户端会尝试在同一 JVM 内启动嵌入式服务端。

## 核心流程

### 1. 登录与会话

- 客户端发送 `REGISTER` / `LOGIN`
- 服务端返回 `SessionInfo`
- 后续请求在连接上下文中校验会话有效性并刷新过期时间

### 2. 上传文档

- 客户端提取文本与关键词（支持手工关键词 + 自动关键词）
- 内容进行对称加密，关键词转换为可检索密文集合（PEKS）
- 发送 `UPLOAD`，服务端写入 `documents` 与 `keyword_index`

### 3. 关键词搜索

- 客户端将查询词转换为 trapdoor，发送 `SEARCH`
- 服务端在关键词索引中匹配并返回命中文档
- 客户端展示结果并支持下载解密

### 4. 文档运维

- `LIST_DOCUMENTS` 拉取列表
- `DOWNLOAD_DOCUMENT` 下载后在客户端解密并保存
- `DELETE_DOCUMENT` 删除文档及索引
- 重建索引流程在客户端执行后重新上传

## 协议设计

客户端和服务端通过统一的 `NetworkMessage` 通信：

- `type`: 消息类型（如 `LOGIN` / `UPLOAD` / `SEARCH`）
- `payload`: 请求体或响应体
- 服务端统一返回 `RESPONSE`，其中载荷是 `ServerResponse`

这种设计的优点是：协议简单、扩展成本低、客户端错误处理统一。

## 数据模型与表结构

主要实体：

- `EncryptedData`：文档 ID、文件元数据、加密正文、加密关键词元数据、PEKS 密文列表
- `SessionInfo`：会话 ID、用户名、过期时间

服务端启动时会自动初始化数据库对象：

- `users`
- `documents`
- `keyword_index`

并执行兼容性字段补齐与索引创建，便于旧版本平滑升级。

## 目录说明

- `src/main/java/com/bdic/admin`
  - 客户端入口、服务端入口、UI 控制器与业务编排
- `src/main/java/com/bdic/crypto`
  - 对称加密、关键词相关加密、密码工具
- `src/main/java/com/bdic/db`
  - 数据库初始化与仓储层
- `src/main/java/com/bdic/model`
  - 网络消息、请求响应 DTO、文档模型
- `src/main/java/com/bdic/net`
  - TLS Socket 创建与配置
- `src/main/java/com/bdic/text`
  - 文本提取与关键词提取
- `src/test/java/com/bdic`
  - 关键模块单元测试

## 运行方式

### 环境要求

- JDK 17
- Maven 3.8+
- MySQL 8.x

### 1) 克隆并编译

```bash
git clone https://github.com/daaaaaataaaaaa/searchable-encryption.git
cd searchable-encryption
mvn clean compile
```

### 2) 运行服务端（可选）

如果你想独立启动服务端：

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.bdic.admin.Server
```

### 3) 运行客户端

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.5.0:java -Dexec.mainClass=com.bdic.admin.AdminClientApp
```

也可直接在 IDE 里运行 `AdminClientApp.main()`。

## 数据库配置

`DatabaseManager` 支持三层读取优先级：系统属性 > 环境变量 > 默认值。

可配置项：

- `se.db.host` / `SE_DB_HOST`
- `se.db.port` / `SE_DB_PORT`
- `se.db.name` / `SE_DB_NAME`
- `se.db.user` / `SE_DB_USER`
- `se.db.password` / `SE_DB_PASSWORD`

示例（PowerShell）：

```powershell
$env:SE_DB_HOST="127.0.0.1"
$env:SE_DB_PORT="3306"
$env:SE_DB_NAME="searchable_encryption"
$env:SE_DB_USER="root"
$env:SE_DB_PASSWORD="your_password"
```

## 当前实现特点

- 客户端各页操作采用异步执行，避免 UI 卡死
- 上传/批量任务有进度提示与 Busy 互斥控制
- UI 支持随窗口尺寸动态缩放（字体、按钮间距、布局间距）
- 错误消息链路已统一，服务端异常会尽量返回可读提示

## 已知边界

- 使用 Java 序列化对象流，适合作为学习与内网实验方案
- 大文件上传受数据库 `max_allowed_packet` 等配置影响
- 默认配置中包含示例连接参数，生产环境需替换并加固

## 开发命令

- 编译：`mvn compile`
- 测试：`mvn test`
- 打包：`mvn package`

## 安全与提交建议

- 不要提交 `client-keys/`、`target/`、证书与本地密钥文件（已在 `.gitignore` 中处理）
- 若历史提交中出现过密钥/证书，建议尽快清理 Git 历史并轮换密钥
