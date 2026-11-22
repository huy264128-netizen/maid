# MaidLang

上游：[huy264128-netizen/maidLang](https://github.com/huy264128-netizen/maidLang)。本目录同步自提交 [5531242](https://github.com/huy264128-netizen/maidLang/tree/5531242e5cc9d1ef4b9f738c526bd8b7ab844263) 及其配套的 `src/main/kotlin` 核心文件（包含 `bytecodeFile.kt`、`nativeRegistry.kt`、`nativeSignature.kt`）。

应用的 Gradle source set 直接编译这九个纯 Kotlin 源文件。它们保留上游的默认包，可用 `import lexer`、`import parser`、`import Interpreter`、`import VM`、`import NativeRegistry`、`import BytecodeFile` 等导入。上游的桌面 Kotlin/Native 构建配置不参与 APK 构建。

宿主可通过 `parser(lexer(source)).program()` 获取 AST，以 `Interpreter` 解释，或通过 `Compiler`、`BytecodeFile` 与 `VM` 编译、序列化并执行字节码。解释器与 VM 均通过 `NativeRegistry`（以及各自的 `registerNative`）共享或隔离宿主原生函数。

本地适配说明：为兼容应用 `minSdk = 31`，`compiler.kt` 中的 `MutableList.removeLast()` 调整为 `removeAt(lastIndex)`。

更新时以同一上游版本同步所有核心文件，并更新本页的提交号与适配说明。
