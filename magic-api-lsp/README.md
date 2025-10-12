# Magic API LSP 和调试功能

本模块为 Magic API 提供了完整的语言服务器协议 (LSP) 和调试适配器协议 (DAP) 支持，可以与 VS Code 等现代编辑器集成。

## 功能特性

### LSP 功能
- ✅ **代码补全** - 智能代码补全，支持关键字、函数、变量等
- ✅ **悬浮提示** - 鼠标悬浮显示变量类型和函数签名
- ✅ **语法验证** - 实时语法检查和错误提示
- ✅ **跳转定义** - 快速跳转到函数和变量定义
- ✅ **文档符号** - 显示文档结构和符号列表
- ✅ **诊断信息** - 编译错误和警告提示

### 调试功能
- ✅ **断点设置** - 支持行断点和条件断点
- ✅ **变量查看** - 查看局部变量和全局变量
- ✅ **调用栈** - 显示完整的函数调用栈
- ✅ **步进调试** - 支持单步执行、步入、步出
- ✅ **表达式评估** - 在调试时评估表达式
- ✅ **暂停/继续** - 控制脚本执行流程

## 项目结构

```
magic-api-lsp/
├── src/main/java/org/ssssssss/magicapi/lsp/
│   ├── MagicLanguageServer.java          # LSP 服务器主类
│   ├── MagicTextDocumentService.java     # 文档服务实现
│   ├── MagicWorkspaceService.java        # 工作区服务实现
│   ├── debug/
│   │   ├── MagicDebugAdapter.java        # 调试适配器实现
│   │   └── MagicDebugServer.java         # 调试服务器
│   └── test/
│       ├── LSPFunctionalityTest.java     # LSP 功能测试
│       ├── DebugFunctionalityTest.java   # 调试功能测试
│       └── IntegrationTest.java          # 集成测试
├── pom.xml                               # Maven 配置
└── README.md                             # 本文档
```

## 快速开始

### 1. 环境要求

- Java 11 或更高版本
- Maven 3.6+ (可选，用于构建)

### 2. 启动 LSP 服务器

```bash
# 方式1: 使用标准输入输出
java -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.MagicLanguageServer

# 方式2: 使用指定端口
java -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.MagicLanguageServer --port=8080
```

### 3. 启动调试服务器

```bash
# 启动调试服务器（端口 4711）
java -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.debug.MagicDebugServer
```

## VS Code 集成

### 1. 安装扩展

在 VS Code 中安装 Magic API 扩展，或者手动配置语言服务器。

### 2. 配置 settings.json

```json
{
  "magic-api.lsp.enabled": true,
  "magic-api.lsp.serverPath": "path/to/magic-api-lsp.jar",
  "magic-api.debug.enabled": true,
  "magic-api.debug.port": 4711
}
```

### 3. 调试配置 launch.json

```json
{
  "version": "0.2.0",
  "configurations": [
    {
      "name": "Debug Magic Script",
      "type": "magic-api",
      "request": "launch",
      "program": "${file}",
      "workspaceRoot": "${workspaceFolder}"
    }
  ]
}
```

## 测试

### 运行所有测试

```bash
# Windows
run_tests.bat

# Linux/Mac
./run_tests.sh
```

### 单独运行测试

```bash
# LSP 功能测试
java -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.test.LSPFunctionalityTest

# 调试功能测试
java -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.test.DebugFunctionalityTest

# 集成测试
java -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.test.IntegrationTest
```

## 使用示例

### 1. 代码补全示例

```javascript
// 输入 "var x = " 时会提示可用的函数和变量
var x = db.select  // 自动补全 db.selectOne, db.selectList 等

// 输入 "return " 时会提示可用的变量
return x  // 自动补全当前作用域的变量
```

### 2. 调试示例

```javascript
// 设置断点的脚本示例
function fibonacci(n) {
    if (n <= 1) {  // 在此行设置断点
        return n;
    }
    return fibonacci(n - 1) + fibonacci(n - 2);
}

var result = fibonacci(10);  // 在此行设置断点
return result;
```

### 3. 错误检测示例

```javascript
// 语法错误会被实时检测
var x = 10
var y =   // 错误：缺少值

// 类型错误也会被检测
var result = x.unknownMethod();  // 警告：未知方法
```

## API 文档

### LSP 服务器 API

#### MagicLanguageServer

主要的语言服务器类，实现了 LSP 协议。

```java
public class MagicLanguageServer implements LanguageServer {
    // 初始化服务器
    public CompletableFuture<InitializeResult> initialize(InitializeParams params);
    
    // 获取文档服务
    public TextDocumentService getTextDocumentService();
    
    // 获取工作区服务
    public WorkspaceService getWorkspaceService();
}
```

#### MagicTextDocumentService

处理文档相关的操作。

```java
public class MagicTextDocumentService implements TextDocumentService {
    // 代码补全
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params);
    
    // 悬浮提示
    public CompletableFuture<Hover> hover(HoverParams params);
    
    // 跳转定义
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params);
}
```

### 调试适配器 API

#### MagicDebugAdapter

实现调试适配器协议的主要类。

```java
public class MagicDebugAdapter implements IDebugProtocolServer {
    // 设置断点
    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args);
    
    // 启动调试会话
    public CompletableFuture<Void> launch(LaunchRequestArguments args);
    
    // 获取变量
    public CompletableFuture<VariablesResponse> variables(VariablesArguments args);
    
    // 评估表达式
    public CompletableFuture<EvaluateResponse> evaluate(EvaluateArguments args);
}
```

## 配置选项

### LSP 配置

| 选项 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `completion.enabled` | boolean | true | 启用代码补全 |
| `hover.enabled` | boolean | true | 启用悬浮提示 |
| `validation.enabled` | boolean | true | 启用语法验证 |
| `definition.enabled` | boolean | true | 启用跳转定义 |

### 调试配置

| 选项 | 类型 | 默认值 | 描述 |
|------|------|--------|------|
| `debug.port` | number | 4711 | 调试服务器端口 |
| `debug.timeout` | number | 30000 | 连接超时时间(ms) |
| `debug.logLevel` | string | "info" | 日志级别 |

## 故障排除

### 常见问题

1. **LSP 服务器无法启动**
   - 检查 Java 版本是否为 11+
   - 确认 classpath 包含所有必要的依赖

2. **代码补全不工作**
   - 检查文档是否正确打开
   - 确认 LSP 客户端支持补全功能

3. **调试器连接失败**
   - 检查调试端口是否被占用
   - 确认防火墙设置允许连接

4. **断点不生效**
   - 确认脚本已正确编译
   - 检查断点设置的行号是否有效

### 日志调试

启用详细日志：

```bash
java -Dlogging.level.org.ssssssss.magicapi.lsp=DEBUG -cp "target/classes:target/lib/*" org.ssssssss.magicapi.lsp.MagicLanguageServer
```

## 贡献指南

1. Fork 项目
2. 创建功能分支
3. 提交更改
4. 推送到分支
5. 创建 Pull Request

## 许可证

本项目采用 Apache 2.0 许可证。详见 LICENSE 文件。

## 更新日志

### v1.0.0 (2024-01-XX)
- ✅ 实现完整的 LSP 功能
- ✅ 实现完整的调试功能
- ✅ 添加 VS Code 集成支持
- ✅ 添加完整的测试套件

## 联系方式

如有问题或建议，请通过以下方式联系：

- 提交 Issue: [GitHub Issues](https://github.com/ssssssss-team/magic-api/issues)
- 邮件: support@magic-api.org
- 文档: [官方文档](https://magic-api.org/docs)