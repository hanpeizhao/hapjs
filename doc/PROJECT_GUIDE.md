# hapjs 快应用服务框架 — 项目启动与目录说明

> 本文档说明 hapjs 项目（小米开源的"快应用"服务框架，前端技术栈开发、原生渲染的免安装应用框架）的
> 环境要求、启动/编译步骤、前端 framework.js 编译链、目录结构，以及 2026-09-27 工具链整体升级的
> 改动文件清单与原因。

---

## 1. 项目简介

快应用 = 类 Vue 的 `.ux` 页面 → 打包成 `.rpk` → 框架在 Android 上加载 rpk，
由 JS 引擎（V8）生成 DOM 树，渲染指令经 HybridBridge 映射为 Android 原生 View 树。

四层架构：

| 层 | 职责 |
|---|---|
| Framework (JS) | 页面组件、路由、数据绑定，输出 framework.js |
| JS Engine (V8) | 解释页面标签生成 DOM 树，发出 RenderAction 渲染指令 |
| HybridBridge | 接收渲染指令，调用原生能力渲染 |
| Platform Services | 三方服务：账户、支付、推送、统计等 |

---

## 2. 环境要求（2026-09-27 工具链整体升级后）

工具链已**整体升级到当前主线版本**，最新 Android Studio（2026.1.1 "Quail"，自带 JBR 21）
可直接打开、Sync、构建，无需再装 JDK 8：

| 工具 | 要求版本 | 说明 |
|---|---|---|
| JDK | **21（推荐 JBR 21，即最新 Studio 自带）** | prebuilts 插件 jar 为 JDK 21 字节码（见 3.7），JDK 17 会报 UnsupportedClassVersionError；CI 亦统一用 21 |
| Gradle | **9.6.0** | mockup 与 debug/shell 两处 wrapper 均已升级 |
| Android Gradle Plugin | **9.2.1** | 见 `mockup/platform/android/build.gradle`；上限以 IDE 为准（Studio 2026.1.1 "Quail" 最高支持 9.2.1），不要随意升级 |
| Kotlin | 1.3.61 | 仅编译期扫描用，已随升级处理 |
| Android SDK | compileSdk **36.1**（android-36.1），build-tools **36.1.0** | 见 `core/runtime/android/config.gradle` |
| NDK | **28.2.13676358**（r28c） | 编译 inspector JNI（ndkBuild）与 widgets 部分原生代码 |
| CMake | **3.22.1** | widgets 的 externalNativeBuild |
| Node.js | **12.13.0**（Gradle 自动管理） | 编译前端 framework.js；发行版 zip 须放本地 maven 仓库（见第 5 节） |

> minSdk 21（AGP 8 起强制），targetSdk 24（app 模块声明）。
> 国内网络注意：构建走腾讯镜像（NDK/CMake/Node 发行版）与 npmmirror（npm 包），
> dl.google.com / repo.maven.apache.org 直连可能超时（镜像已写进根 build.gradle）。

---

## 3. 工具链升级记录与文件修改清单（2026-09-27）

### 3.1 为什么升级

原工具链（AGP 3.4.2 + Gradle 5.2.1 + JDK 8）与最新 Android Studio 不兼容
（JBR 21 跑不了 Gradle 5.2.1，新版 Studio 对过老 AGP 有兼容下限），因此整体升级为
**AGP 9.2.1 + Gradle 9.6.0 + JDK 21/JBR 21**（AGP 版本受 IDE 支持上限约束，Studio 2026.1.1 "Quail" 最高支持 AGP 9.2.1），目标是 Quail 直接 Sync/构建。
升级遵循原则：只修不兼容点，不改业务行为；对平台已移除且无实效的 API 直接删除调用链，
不做反射 hack。

### 3.2 根配置与工程脚本

| 文件 | 改动与原因 |
|---|---|
| `mockup/platform/android/gradle/wrapper/gradle-wrapper.properties` | Gradle 5.2.1 → **9.6.0**（AGP 9 要求 Gradle 8.13+） |
| `mockup/platform/android/build.gradle` | 整体重写：AGP 9.2.1；compileSdk 改**块 DSL**（`release(36) { minorApiLevel = 1 }`，字符串 "36.1" 不被解析）；`buildConfig true` 统一开启（AGP 9 默认关闭，多模块用 buildConfigField）；删 kotlin-android-extensions / spotbugs；maven-publish 用新 DSL；node 插件换 `com.github.node-gradle` 7.1.0（原 moowork 停止维护）；仓库列表加腾讯镜像与本地 manual-repo |
| `mockup/platform/android/gradle.properties` | 加 AndroidX 开关（useAndroidX）；`nonTransitiveRClass=false`、`nonFinalResIds=false`（保持旧行为：模块引用依赖模块 R 常量、switch 使用 R.id） |
| `core/runtime/android/config.gradle` | compileSdk 29 → **36**（minor 1），buildTools 36.1.0 |
| `mockup/platform/android/build.gradle`（compileSdk 块） | 子模块共享块补 `buildToolsVersion` 显式声明（AGP 9 默认 36.0.0，本机未装会报缺失） |
| `debug/shell/android/build.gradle` | 整体重写为 AGP 9.2.1（镜像顺序同主工程；card-api/android-stub/android-hack 用 java-library；compileSdk 块 DSL + buildToolsVersion） |
| `debug/shell/android/gradle/wrapper/gradle-wrapper.properties` | Gradle → 9.6.0 |
| `debug/shell/android/local.properties` | 新增（sdk.dir 指向本机 SDK） |

### 3.3 core/runtime/android（框架核心）

| 文件 | 改动与原因 |
|---|---|
| `runtime/build.gradle` | ① soloader `force = true` → `resolutionStrategy.force`（Gradle 9 移除 ExternalModuleDependency.force）；② 前端编译链迁移到 node-gradle 7.1.0：`distBaseUrl = null`（7.x 把 distBaseUrl 当 maven 布局仓库，nodejs.org/dist 目录布局不被支持）+ NodeTask 直跑 npm-cli.js + `npm_config_prefix` 环境变量隔离（node 12 自带 npm 6 shim 会探测并跳到系统全局 npm 9，报 `Cannot find module 'node:path'`）；③ 补 `namespace`（AGP 8+ 必需）；④ defaultConfig 补 `buildConfigField VERSION_NAME`（**AGP 9 起 BuildConfig 不再自动生成 VERSION_NAME/VERSION_CODE**） |
| `runtime/.../bridge/HybridSettings.java` | 删除 `setAppCacheEnabled`/`setAppCachePath`：API 36 平台已从 WebSettings 移除这两个方法，且 Android 5.0+ WebView 早已忽略 AppCache，全仓库无调用方（死代码链） |
| `runtime/.../bridge/impl/webkit/HybridViewImpl.java` | 删除上两项的转发实现（同上原因；`setGeolocationDatabasePath` 在平台中仍存在，保留） |
| `runtime/.../common/net/UserAgentHelper.java` | 无改动：其引用的 `BuildConfig.VERSION_NAME` 由 runtime/build.gradle 补字段解决 |
| `annotation-processor/.../AnnotationProcessor.java` | ① 加 `@SupportedOptions("outputDir")`（消除 javac "选项未被识别" 警告）；② `processSuperClasses` 重写为 `javax.lang.model` 公开 API（原实现访问 javac 内部 API `Type.ClassType.supertype_field`，JDK 16+ 模块强封装禁止访问，且 `--add-exports` 与 `--release 8` 冲突不可用） |
| `annotation-processor/build.gradle` | 删除 toolsJar compileOnly 分支（JDK 9+ 无 tools.jar，`getToolsJar()` 恒为 null 的死代码） |
| `widgets/build.gradle` | cmake 块显式 `version '3.22.1'` |
| `widgets/.../view/camera/VideoRecordMode.java` | `MediaMetadataRetriever.release()` 包 try-catch IOException（**API 29+ 平台起该方法声明抛出 IOException**） |
| `features/.../video/Video.java` | 同上，3 处 `release()` 捕获 IOException（其中 2 处在 finally 块中，finally 不受前面 `catch (Exception)` 保护，必须单独捕获） |
| `inspector/src/main/jni/Application.mk` | `APP_PLATFORM` android-16 → android-21（NDK r27 起最低支持 21） |

### 3.4 Manifest（Android 12+ 强制 exported）

| 文件 | 改动 |
|---|---|
| `mockup/.../app-impl/src/main/AndroidManifest.xml` | MainActivity 补 `android:exported="true"`（LAUNCHER 入口必须显式声明） |
| `core/.../features/src/main/AndroidManifest.xml` | CaptureActivity 补 `android:exported="false"` |
| `core/.../features/src/androidTest/AndroidManifest.xml` | RuntimeActivity 补 `android:exported="false"` |
| `core/plugins/.../exchange/src/androidTest/AndroidManifest.xml` | RuntimeActivity 补 `android:exported="false"` |

### 3.5 debug/shell（调试器工程）

| 文件 | 改动与原因 |
|---|---|
| `app-impl/build.gradle` | `lintOptions` → `lint { abortOnError false }`；compileOptions 显式 1.8；defaultConfig 补 `buildConfigField VERSION_CODE`（AGP 9 移除自动生成）；补 namespace |
| `app/build.gradle` | compileOptions 显式 1.8 |

### 3.6 环境准备（非仓库文件）

| 位置 | 内容 | 原因 |
|---|---|---|
| `%LOCALAPPDATA%\Android\Sdk\ndk\28.2.13676358` | NDK r28c（腾讯镜像下载） | inspector 无预编译 so（`src/main/libs` 为空），ndkBuild 必须真编译 |
| `%LOCALAPPDATA%\Android\Sdk\cmake\3.22.1` | CMake 3.22.1 | widgets 的 externalNativeBuild |
| `%LOCALAPPDATA%\Android\Sdk\platforms\android-36.1`、`build-tools\36.1.0` | SDK 平台 | compileSdk 36.1 |
| `~/.m2/manual-repo/org/nodejs/node/12.13.0/` | `node-12.13.0.pom` + `node-12.13.0-win-x64.zip` | node-gradle 7.x 以 maven 坐标 `org.nodejs:node:<ver>:<os>-<arch>@<ext>` 解析 Node 发行版（Windows 为 `win-x64@zip`，Linux 为 `linux-x64@tar.gz`），nodejs.org/dist 目录布局不被支持，故手工搭建本地 maven 仓库（pom packaging=zip/tar.gz + 官方发行版包，腾讯 nodejs-release 镜像/官方 dist 下载）。GitHub Actions CI 由工作流自动搭建 Linux 侧仓库（见 4.5） |

### 3.7 prebuilts 插件 jar 的自动同步机制（git 变更中出现 jar 的原因）

主工程 buildscript（`mockup/platform/android/build.gradle` 里的
`apply from: 'development/gradle-plugin/prebuilts/2.3.0/buildscript.gradle'`）从
`development/gradle-plugin/prebuilts/2.3.0/` 加载 4 个预编译 jar：
`annotation-2.3.0.jar`、`annotation-processor-2.3.0.jar`、`annotation-executor-2.3.0.jar`、
`annotation-generator-2.3.0.jar`。

这 4 个 jar **不是手工维护的固定二进制**，而有一套自动同步机制（项目原有设计）：

1. `gradle-plugin` 工程（`development/gradle-plugin`）通过其 `settings.gradle` 直接引用
   `core/runtime/android/annotation` 与 `core/runtime/android/annotation-processor` 的
   **同一份源码**；
2. 构建该工程（`gradlew assemble`）时，根脚本 `afterEvaluate` 里的 `assemble.doLast`
   会把新编译的 `*-2.3.0.jar` 从 `build/libs/` 复制到 `prebuilts/2.3.0/` **覆盖旧 jar**；
3. 因此只要修改了 `AnnotationProcessor.java` 等注解/处理器源码并重新构建 gradle-plugin
   工程，git 变更列表里就会自动出现这 4 个 jar 的修改记录。

本次升级中 jar 变化的具体原因：

| jar | 变化原因 |
|---|---|
| `annotation-processor-2.3.0.jar` | 源码真实变更（AnnotationProcessor 重写为公开 API，见 3.3） |
| 其余 3 个 | 源码未动，仅因 JDK 8 → JDK 21 重新编译的字节码元数据差异，功能等价 |

**这些 jar 变更必须保留并提交**：旧版 `annotation-processor.jar` 在 JDK 16+ 下会因
JPMS 强封装（访问 javac 内部 API 被拒）直接失效，还原 jar 会导致构建失败。
另注意：现 jar 均为 **JDK 21 字节码（class file 65）**，构建运行时必须用 JDK 21+——
用 JDK 17 加载会报 `UnsupportedClassVersionError`，CI 环境因此统一使用 JDK 21（见 4.5）。

另：`debug/shell/android/gradle/gradle-daemon-jvm.properties` 是 Gradle 9 首次运行时
自动生成的 daemon JVM 配置（记录所用 JBR 版本），随仓库提交即可，删除后会再生成。

---

## 4. 编译与运行

### 4.1 工程组成：两个独立 Gradle 工程的关系

仓库内有**两个可打开构建的 Gradle 工程**（各有独立的 settings.gradle / wrapper / local.properties），
外加一个无需构建的插件源码工程：

| 工程 | 打开目录 | 角色 | 产物 |
|---|---|---|---|
| ★ 主工程 | `mockup/platform/android` | 快应用运行时/预览版壳 app（org.hapjs.mockup），**日常开发只需打开这一个** | `app-phone-debug.apk` |
| 调试器工程 | `debug/shell/android` | 快应用调试器 app（org.hapjs.debugger）：扫码/本地安装 rpk、发起调试、查看日志 | 调试器 APK |
| 插件工程（不构建） | `development/gradle-plugin` | 编译期注解处理插件源码；主工程用 `prebuilts/2.3.0` 预编译 jar | — |

- **主工程模块构成**（见 `mockup/platform/android/settings.gradle`）：本地模块只有
  `app`、`app-impl`，其余约 24 个模块全部经相对路径从仓库各处引用 ——
  `core/runtime/android` 的 runtime/features/widgets/card-*、`debug/engine/android/inspector`
  （JNI 调试核心也是主工程模块，需 NDK）、`platform/platform/android`、
  以及 pay/account/push/wxpay/qqaccount 等能力插件。
- **调试器工程模块构成**（见 `debug/shell/android/settings.gradle`）：本地 `app`、`app-impl`
  + 从 core 引用的 card-api/card-sdk/card-common/debug-log/android-stub/android-hack。
  它的 inspector 依赖用**预编译 aar**（`app-impl/inspector/*.aar`），不需要 NDK。
- **两者关系**：主工程是"被调试的运行时"，调试器是"调试入口工具"。
  跑快应用/看渲染效果 → 只装主工程 APK；用调试器调试 rpk 页面 → 两个都装，
  调试器拉起平台包 `org.hapjs.mockup` 运行。

### 4.2 快应用框架 app（mockup 壳工程，主线）

命令行（与 IDE 构建等价）：

```bash
cd mockup/platform/android
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew :app:assemblePhoneDebug -PPROP_APP_ABI_DEBUG=armeabi-v7a --console=plain
```

产物：`mockup/platform/android/app/build/outputs/apk/phone/debug/app-phone-debug.apk`

Android Studio（Quail）：打开 `mockup/platform/android`（不是仓库根目录）→ 等 Sync →
`Build Variants` 选 `phoneDebug` → 连接设备点 Run。Gradle JVM 保持默认（JBR 21）即可。

flavor 维度 `platform`：phone / tv / car。

### 4.3 快应用调试器（debug/shell）

```bash
cd debug/shell/android
gradlew :app:assembleDebug
```

调试器功能：扫码安装 / 本地安装 rpk / 在线更新 / 开始调试。
运行平台选择 `org.hapjs.mockup`（快应用预览版）即可调试框架。

### 4.4 常用构建参数

| 参数 | 作用 |
|---|---|
| `-PPROP_APP_ABI_DEBUG=armeabi-v7a:arm64-v8a` | 指定 ABI，显著减少编译时间（建议始终带上） |
| `-PuseSpecifiedNdk=true` | 触发 inspector 的 externalNativeBuild（需 NDK 28.2） |
| `-PrepoType=remote -PprivateRepo=...` | 用远程 maven 而非 mavenLocal |
| `-PsignMode=true -PstoreFile=... -PstorePassword=... -PkeyAlias=... -PkeyPassword=...` | release 正式签名 |
| `-PuseSysSig` | debug 使用系统签名而非默认 debug.keystore |
| `-PENABLE_INFRAS_JS_CACHE=true` | 跳过前端 framework.js 重复构建（加速二次构建） |
| `-PdebugMode=true` | 前端 infras JS 走 debug 构建（npm run native） |

### 4.5 GitHub Actions CI（云端构建）

公共环境准备抽取为本地 composite action（`.github/actions/setup-build-env/`），
所有 job 复用：JDK 21（temurin，须 ≥21 以加载 prebuilts 插件 jar，见 3.7）+ Gradle 缓存 → 安装 SDK 组件
（`platforms;android-36.1`、`build-tools;36.1.0`、`ndk;28.2.13676358`、`cmake;3.22.1`）
→ 自动搭建 Node 12.13.0 本地 maven 仓库（Linux 侧 `linux-x64@tar.gz`，从 nodejs.org
官方 dist 下载，GitHub 网络无需镜像）。CI 上无需任何手工准备——3.6 节的环境准备
全部由它完成；国内开发机仍按 3.6 手动准备（镜像差异）。

| 工作流 | 触发 | 内容 |
|---|---|---|
| [android-build.yml](../.github/workflows/android-build.yml)（日常构建） | push 到 main / PR / 手动 | 主工程 phone/tv 两 flavor 并行，各构建 debug + release（ABI 收敛为 armeabi-v7a:arm64-v8a 缩短时长）；checkstyle 静态检查；调试器 debug + release；产物以 artifact 上传 |
| [android-release.yml](../.github/workflows/android-release.yml)（发布） | 手动，输入版本号 | 构建主工程 phone release 与调试器 release（含混淆 mapping），创建 GitHub Release 并按原命名约定上传 APK + mapping（`gh` CLI 实现） |

> 原仓库的 `main.yml`（日常 CI）与 `build_and_release.yml`（Release 发布）依赖
> 2020 年的旧容器镜像（JDK 8 + NDK r17c），无法运行 Gradle 9.6，且 `main.yml`
> 触发条件与日常构建工作流重叠会导致每次 push 必失败，二者已删除，
> 功能已由上表两个工作流在新工具链上等价承接。
>
> [.gitlab-ci.yml](../.gitlab-ci.yml)（GitLab CI）与 GitHub 侧完全对齐：
> 基于 `eclipse-temurin:21-jdk` 镜像，环境准备抽为隐藏模板 `.build-env`（逻辑等价于
> composite action，Node maven 仓库真身在项目内 `.node-m2/` 并软链到 `~/.m2/` 以支持
> GitLab 缓存）。日常构建（push/MR）为 phone/tv/debugger 三 job 并行构建 debug + release
> （checkstyle + scan_build.sh 门禁在 phone job）；push tag（tag 号即版本号，如
> `git tag 1300`）触发发布 job：以 `-DappVersionTag` 构建 phone/调试器 release，
> APK + mapping 上传为 job artifacts（30 天有效）。

### 4.6 产物去向：artifact 与 Release 是两回事

决定产物去向的不是"手动执行还是自动执行"，而是**工作流脚本里执行了哪些命令**：
触发条件只控制"脚本什么时候跑"，脚本跑起来后执行了什么，产物就去哪里。

| 去向 | 性质 | 谁能写入 | 有效期 |
|---|---|---|---|
| **artifact**（运行产物） | CI 平台的临时存储，从 Actions 运行页 / GitLab 管道页下载 | `upload-artifact`（GitHub）或 `artifacts:`（GitLab）声明即存 | GitHub 默认 90 天，GitLab 按声明（日常 1 周 / 发布 30 天），到期自动删除 |
| **Release**（发布区） | 仓库 Releases 页的正式版本条目，附件永久保留 | 只有脚本里显式执行发布命令才会出现 | 永久 |

日常构建工作流里只有 `gradlew assemble` + `upload-artifact`，**没有任何发布
命令，所以永远不会进 Releases 页**。发布工作流（android-release.yml）多出两条
关键命令——它们等价于你亲手在网页上执行 Releases → Draft a new release →
拖入 APK → Publish：

- `gh release create "$VERSION_TAG"`（L47）：在 Releases 区新建版本条目；
- `gh release upload "$VERSION_TAG" *.apk mapping*`（L62）：把 APK/mapping
  上传为该条目的附件。

`gh` 是 GitHub 官方命令行工具，工作流只是替你自动执行这套网页操作。因此：

- 出正式版：Actions → Android Release → Run workflow → 填版本号（如 `1300`）；
- 若希望 push tag 自动发布（无需网页手动触发），把 android-release.yml 的
  `workflow_dispatch` 触发改为 `push: tags: ['*']` 即可（GitLab 侧已是 tag 触发）。

---

## 5. 前端 framework.js 编译链（回答"要不要编译前端文件、放到哪里"）

**结论：不需要手动编译。** `core/framework` 目录（前端框架 JS 源码）的编译已接入 Gradle
任务链，构建 `:runtime`（即任何 APK 构建的前置依赖）时自动执行，产物自动落到
`core/runtime/android/runtime/src/main/assets/js/` 并打进 runtime aar → APK。

自动链路（定义在 `core/runtime/android/runtime/build.gradle`）：

```
:runtime:nodeSetup
    从本地 maven 仓库 ~/.m2/manual-repo 解析并解压 node 12.13.0 到
    core/runtime/android/.gradle/node/node-v12.13.0-win-x64/
  ↓
:runtime:npmInstallInfras
    NodeTask 直跑发行版内 npm-cli.js（工作目录 core/framework），
    registry 用 npmmirror；npm_config_prefix 指向空目录隔离系统全局 npm
  ↓
:runtime:buildInfrasJS
    npm run native:release（debugMode=true 时为 npm run native），rollup 打包
  ↓
:runtime:copyInfrasJS
    把 core/framework/dist/release/ 下的
      infras.js、bundles/*.js、dsls/dsl-xvm.js、app/*/*.js
    复制到 core/runtime/android/runtime/src/main/assets/js/
  ↓
:runtime aar → :app APK
```

需要手动介入的场景：

- **换网络/清缓存后**：删除 `core/runtime/android/.gradle/node/` 可强制重新下载 Node；
  npm 依赖装失败可进 `core/framework` 手动 `npm install --registry=https://registry.npmmirror.com`。
- **想手动跑一次前端构建**：在 `core/framework` 下
  `npm run native:release`（release）或 `npm run native`（debug），产物在
  `core/framework/dist/release/`（或 `dist/debug/`），正常情况下无需自己拷贝，
  `copyInfrasJS` 会做。
- **只想重打 APK 不重编前端**：加 `-PENABLE_INFRAS_JS_CACHE=true`。
- **注意**：V8 引擎本身（jsenv aar）是预编译产物，位于
  `core/runtime/android/runtime/jsenv-libs/`，不在前端编译链内。

---

## 6. 目录结构说明

```
hapjs
├── doc/                     项目文档（本文档 + GIT_PROXY.md git 代理原理 + SUPPORT_V4_SHIM.md support-v4 垫片原理）
├── .github/                 GitHub 配置
│   ├── workflows/           构建与发布工作流（android-build.yml / android-release.yml，见 4.5）
│   └── actions/             本地 composite action（公共构建环境准备，供工作流复用）
├── core/                    ★ 框架核心（可独立运行 rpk 的 runtime）
│   ├── framework/           前端框架 + 编译器，npm/rollup 构建输出 framework.js（见第 5 节）
│   ├── runtime/android/     Android 端运行时，输出 aar
│   │   ├── runtime/         核心模块（启动、桥接、渲染、包管理）
│   │   │   ├── org/hapjs/runtime/RuntimeActivity      启动入口 Activity
│   │   │   ├── org/hapjs/bridge/ExtensionManager      JS↔Native 桥（Feature/Widget 注册分发）
│   │   │   ├── org/hapjs/bridge/HybridSettings        WebView 配置抽象（AppCache 接口已随升级移除）
│   │   │   ├── org/hapjs/render/action/RenderActionParser  渲染指令解析
│   │   │   ├── org/hapjs/render/vdom/VDomActionApplier     指令→原生 View 树
│   │   │   └── org/hapjs/cache/PackageInstaller       rpk 安装接口（FilePackageInstaller 为文件实现）
│   │   ├── features/        系统接口原生实现（35+）：Network/Request/Fetch、Geolocation、
│   │   │                    Bluetooth、Wifi、Storage、Sensor、Media/Record、Clipboard、
│   │   │                    Notification、Share、Shortcut、Vibrator、Zip 等
│   │   ├── widgets/         原生组件实现：div/text/image/view、list/swiper/tab/refresh、
│   │   │                    map、canvas、video、input、picker、camera、Web 等
│   │   ├── annotation-processor/  编译期注解处理器（生成 feature/widget 元数据 JSON）
│   │   ├── config.gradle    SDK 版本定义（compileSdk 36.1 等）
│   │   └── version.gradle   版本号定义
│   ├── adapters/            适配层
│   └── plugins/features/    可复用的服务类接口，各输出独立 aar
├── debug/                   调试模块（不参与框架编译打包）
│   ├── engine/              调试核心：V8 Inspector + Chrome DevTools 协议
│   │   └── android/inspector  JNI 实现 java_v8_inspector.cpp（ndkBuild，需 NDK 28.2）
│   └── shell/               快应用调试器 app（org.hapjs.debugger，独立 Gradle 工程）
├── development/
│   └── gradle-plugin/       编译期注解处理插件：prebuilts/2.3.0 提供预编译 jar，
│                            主工程默认直接使用预编译版，无需构建此工程
├── external/public/         Inspector 等 V8 native 模块编译所需 JSEnv 头文件
├── mockup/                  app 壳工程（输出 apk）
│   ├── platform/android/    ★ 主工程入口（gradle/Studio 打开此目录），phone/tv/car 三 flavor
│   └── plugins/             三方接口插件：service.account（账户）、service.pay（支付）、
│                            service.push（推送）等，按 FeatureExtensionAnnotation 注册
├── platform/                平台化机制
│   ├── platform/android/    分发/启动/进程管理：
│   │                        DistributionManager（rpk 分发安装）、LauncherManager（启动分发）、
│   │                        LauncherActivity（激活入口）、PackageFilesValidator（包完整性校验）
│   └── plugins/             平台三方插件
├── img/                     README 架构图
├── Dockerfile               CI 编译环境定义（历史遗留：仍为 openjdk:8 + NDK r17c，未随升级更新）
└── Jenkinsfile / .gitlab-ci.yml / scan_build.sh   CI 流水线与静态扫描脚本（历史遗留，同上）
```

---

## 7. 核心工作流（一次页面渲染）

1. **启动**：外部 intent（扫码/图标）→ `LauncherManager` → `RuntimeActivity`。
2. **加载包**：读取已安装 rpk 的 `manifest.json` 与 `app.ux` 公共逻辑。
3. **执行 JS**：V8 运行 framework.js + 页面 js，生成 DOM 树。
4. **渲染**：DOM 变化序列化为 RenderAction → HybridBridge → `RenderActionParser` 解析 →
   `VDomActionApplier` 映射为原生 View 树，挂到 Activity 的 ContentView。
5. **能力调用**：页面调 `system.fetch` 等 API → `ExtensionManager` 路由到对应 Feature 原生实现，回调结果。

---

## 8. 常见问题

- **Sync/构建报找不到 node 12.13.0？** 本地 maven 仓库缺发行版：确认
  `~/.m2/manual-repo/org/nodejs/node/12.13.0/` 下有 `node-12.13.0.pom` 与
  `node-12.13.0-win-x64.zip`（见 3.6）。
- **构建报 `Could not find org.hapjs:jsenv:1.2.8`？** 本地 maven 仓库缺 jsenv 引擎封装：
  确认 `~/.m2/manual-repo/org/hapjs/jsenv/1.2.8/` 下有 `jsenv-1.2.8.pom`、
  `jsenv-1.2.8.aar`（及 no-v8symbols 变体）。AGP 9 禁止 library 项目以 fileTree/files
  方式引本地 .aar（bundleAar 的 hasLocalAarDeps 检查），:runtime 已改为
  `org.hapjs:jsenv:1.2.8@aar` 坐标解析；pom+aar 从仓库源
  `core/runtime/android/runtime/jsenv-libs/` 复制即可（CI 由 composite action /
  .gitlab-ci.yml 自动准备）。不要用普通 `files()` 引 aar 替代——那是本次迁移专门消除的写法。
- **npm install / npm run 报 `Cannot find module 'node:path'`？** 系统 PATH 里的全局 npm
  与 node 12 不兼容；工程已通过 NodeTask + `npm_config_prefix` 隔离，若手动操作请勿直接
  用系统 `npm` 跑 `core/framework`，用发行版内的 npm-cli.js。
- **报 `BuildConfig.VERSION_NAME/VERSION_CODE` 找不到？** AGP 9 不再自动生成，模块已在
  build.gradle 显式 buildConfigField 补齐；新增模块同样引用时需自行补字段。
- **依赖拉不下来？** 国内网络走腾讯/npmmirror 镜像（已写入根 build.gradle）；
  `mavenLocal()` 依赖需先执行各 aar 的 `publishToMavenLocal`。
- **inspector 编译失败？** 确认 NDK 28.2.13676358 与 `Application.mk` 的
  `APP_PLATFORM := android-21`（NDK r27+ 最低 21）。
- **framework.js 从哪来？** 见第 5 节：`core/framework` 经 node 12 + rollup 自动构建，
  `copyInfrasJS` 任务拷入 `core/runtime/android/runtime/src/main/assets/js/`。
- **git 变更里出现 jar 文件？** `prebuilts/2.3.0` 下的 4 个注解插件 jar 会在构建
  gradle-plugin 工程时自动重新生成并覆盖（见 3.7），属预期行为，连同源码一起提交。
- **旧版构建参数还生效吗？** `PROP_APP_ABI_DEBUG`、`signMode`、`useSysSig`、
  `useSpecifiedNdk`、`ENABLE_INFRAS_JS_CACHE`、`debugMode` 均保留并生效（见 4.4）。
