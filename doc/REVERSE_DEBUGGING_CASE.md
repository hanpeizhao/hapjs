# 一次"横屏锁死"问题的逆向排查复盘（含字节码取证教学）

> 本文复盘 2026-09-28 排查"手机竖屏状态下打开快应用却横屏且恢复不了"问题的完整思路链，
> 每一步用了什么命令、为什么这么想、如何从 APK 里找到并读懂目标字节码。
> 适合当作 Android 逆向取证入门案例来读。

---

## 1. 案例背景

| 项目 | 内容 |
|---|---|
| 现象 | 手机明明竖着拿，打开快应用却是横屏，且旋转手机也恢复不了 |
| 关键线索 | "之前是正常的"（回归问题，不是新 bug） |
| 当天改动 | 元数据修复后重新构建、安装过平台包；用户也在 Android Studio 里操作过 |
| 结论 | 装到手机的是 **car（车机）变体** 的包，car 平台按设计硬锁横屏 |

---

## 2. 排查全景图（先看思路链，再看细节）

```
症状：横屏且锁死
  ↓ 问：谁的代码能让 Activity 横屏？→ 全局搜 setRequestedOrientation（写症状的 API）
  ↓ 找到 3 条产生路径：①rpk 页面声明方向 ②视频全屏 ③平台类型判定（isTV/isCar）
  ↓ 逐条排除：
     ① 解包 rpk 看 manifest.json → 没有 orientation 字段 → 排除
     ② 视频全屏需要用户先点视频 → 现象是"一打开就横屏" → 排除
     ③ 剩下平台判定 → isTV()/isCar() 读 BuildConfig.FLAVOR（编译期常量）
  ↓ 磁盘异常：runtime 模块只生成了 car/debug 的 BuildConfig.java（FLAVOR="car"）
  ↓ 磁盘证据只能证明"构建过 car"，不能证明"手机上跑的是 car"
  ↓ 决定性取证：把手机上已装的 APK 拉下来，反汇编字节码看常量的真实值
  ↓ dexdump 反汇编 BuildPlatform.isCar()：两个 "car" 比较 → 恒为 true → 实锤
  ↓ 时间线对账：dumpsys 安装时间 04:01:05 == 磁盘 car 产物时间戳 → 来源是 Studio Run
  ↓ 修复：重建 phoneDebug → 重装 → 再次拉包反汇编复核 FLAVOR="phone" → 闭环
```

核心方法论一句话：**从"能产生症状的 API"反推所有路径，逐条排除；不信任推断，用字节码验尸闭环。**

---

## 3. 分步详解（每步：目标 → 命令 → 结果 → 为什么）

### 第 1 步：找"谁负责设置屏幕方向"

不要猜，直接搜产生症状的 Android API：

```
搜索（等价 rg -n "setRequestedOrientation"）
```

命中 7 处，收敛到关键一处：

```java
// runtime/.../render/Display.java:1815
((Activity) context).setRequestedOrientation(provider.getScreenOrientation(mPage, mAppInfo));
```

继续搜 `getScreenOrientation`，找到接口和默认实现：

```java
// runtime/.../system/DefaultSysOpProviderImpl.java:510
public int getScreenOrientation(Page page, AppInfo info) {
    if (page.hasSetOrientation()) {
        screenOrientation = page.getOrientation();
    } else {
        screenOrientation =
                BuildPlatform.isTV() | BuildPlatform.isCar()
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE   // ← 横屏！
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }
    return screenOrientation;
}
```

**为什么这么想**：横屏是个"结果"，结果一定由某个 `setRequestedOrientation` 调用产生。
全局搜这个 API 是最短路径，避免在几十万行代码里凭感觉猜。

### 第 2 步：排除"rpk 自己声明了横屏"

`Page.getOrientation()` 从 rpk 的 manifest 里读 style 的 `orientation` 字段
（常量定义 `DisplayInfo.java:177`：`KEY_ORIENTATION = "orientation"`）。

先找到仓库里的 rpk（rpk 本质是 zip 包）：

```
Glob: **/*.rpk
→ core/framework/sys-components/xvm/dist/com.application.demo.release.1.0.0.rpk
```

PowerShell 直接把 zip 里的 manifest.json 读出来（无需解压到磁盘）：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead('<rpk路径>')
$entry = $zip.GetEntry('manifest.json')
$reader = New-Object System.IO.StreamReader($entry.Open())
$json = $reader.ReadToEnd(); $reader.Close(); $zip.Dispose()
if ($json -match 'orientation') { 'HAS' } else { 'NO orientation key' }
```

结果：`NO orientation key`，rpk 完全没声明方向 → **排除这条路径**。
顺带搜了 mockup 里有没有自定义 `SysOpProvider` 覆盖默认行为 → 没有 → **排除**。

### 第 3 步：排除"视频全屏残留"

[FullscreenHelper.java](core/runtime/android/runtime/src/main/java/org/hapjs/component/utils/FullscreenHelper.java)
会在视频进全屏时锁横屏、退出时恢复。但现象是**一打开就横屏**，用户没碰过视频 → 排除。

### 第 4 步：剩下的唯一路径——`BuildPlatform` 平台判定

```java
// runtime/.../common/compat/BuildPlatform.java
public static boolean isTV()  { return TextUtils.equals("tv",   BuildConfig.FLAVOR); }
public static boolean isCar() { return TextUtils.equals("car",  BuildConfig.FLAVOR); }
```

`BuildConfig.FLAVOR` 是 **AGP 构建期生成**的常量（phone/tv/car 三个 flavor 对应三个值）。
此时去翻磁盘上的构建产物，发现重大异常：

```powershell
Get-ChildItem <runtime模块>/build/generated -Recurse -Filter 'BuildConfig.java'
→ 只有 car\debug\org\hapjs\runtime\BuildConfig.java 一份！内容 FLAVOR = "car"

Get-ChildItem <app模块>/build/outputs -Recurse -File
→ 只有 manifest-merger-car-debug-report（car 变体的清单合并报告）
```

**为什么这是强线索**：我们一直用 `assemblePhoneDebug` 构建，磁盘上却躺着 car 变体的产物
→ 今天有人构建过 **carDebug**。构建产物不会撒谎。

### 第 5 步：决定性取证——验尸手机上已装的 APK

磁盘证据只能证明"构建过 car"，**不能证明手机上跑的就是 car**（可能装的是旧包）。
所以必须把手机上的包拉下来直接看：

```bash
# 1. 确认设备、拿到已装 APK 在手机上的路径
adb devices
adb shell pm path org.hapjs.mockup
# → package:/data/app/~~xxxx==/org.hapjs.mockup-yyyy==/base.apk

# 2. 拉到本地
adb pull /data/app/~~xxxx==/org.hapjs.mockup-yyyy==/base.apk %TEMP%\hapjs-apk\base.apk
```

APK 是 zip，解出里面的 dex（Java 源码 → javac → class → d8 → classes.dex）：

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead("$out\base.apk")
$zip.Entries | Where-Object { $_.FullName -like 'classes*.dex' } | ForEach-Object {
    $s = $_.Open(); $f = [System.IO.File]::Create("$out\$($_.Name)")
    $s.CopyTo($f); $f.Close(); $s.Close()
}
$zip.Dispose()   # → 解出 23 个 classesN.dex
```

### 第 6 步：在 23 个 dex 里找到目标类

dexdump 没有按类名过滤的参数，用笨但可靠的办法——逐个 dump 全文再 grep：

```powershell
$dexdump = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.1.0\dexdump.exe"
Get-ChildItem $out -Filter '*.dex' | ForEach-Object {
    $hit = & $dexdump $_.FullName 2>$null |
           Select-String -Pattern 'org/hapjs/common/compat/BuildPlatform' -SimpleMatch |
           Select-Object -First 1
    if ($hit) { $_.Name }   # → classes12.dex
}
```

**为什么先找这个类**：判断横屏的 `isTV()/isCar()` 在 BuildPlatform 里，
而它比较的常量值就藏在字节码里——看一眼方法体就知道答案。

### 第 7 步：反汇编方法体，看到实锤

```powershell
& $dexdump -d "$out\classes12.dex" 2>$null |
    Select-String -Pattern "name          : 'is(Car|TV|Phone)'" -Context 1,14
```

输出（节选，删去无关行）：

```
isCar:()Z
  0000: const-string v0, "car"
  0002: const-string v1, "car"          ← 第二个常量也是 "car"！
  0004: invoke-static {v0, v1}, Landroid/text/TextUtils;.equals:(...)Z
  0007: move-result v0
  0008: return v0                        → isCar() 恒为 true

isPhone:()Z
  0000: const-string v0, "phone"
  0002: const-string v1, "car"           ← FLAVOR 内联值是 "car"
  ...                                    → isPhone() 恒为 false
```

`TextUtils.equals("car", BuildConfig.FLAVOR)` 两个入参全是 `"car"` → 恒 true → 硬锁横屏。**实锤。**

### 第 8 步：时间线对账，确定来源

```bash
adb shell dumpsys package org.hapjs.mockup | findstr lastUpdateTime
# → lastUpdateTime=2026-09-28 04:01:05
```

与磁盘 car 产物时间戳（04:00:44–04:01:00）吻合 → 这个 car 包是 Android Studio
以 carDebug 变体 Run 安装的（Studio 的 Build Variants 默认可能选中 carDebug）。

### 第 9 步：修复 + 闭环验证

```bash
.\gradlew :app:assemblePhoneDebug          # 重建 phone 变体
adb install -r app\build\outputs\apk\phone\debug\app-phone-debug.apk
```

**修复不算完，还要验尸新包**：重复第 5–7 步拉包反汇编，
新包里 `isCar()` 变成 `"car"` vs `"phone"` → false，默认竖屏恢复。问题闭环。

---

## 4. 如何找到"想要的那个字节码文件"

### 4.1 先理解 APK 里的字节码是什么

```
Foo.java  --javac-->  Foo.class（JVM 字节码）  --d8/dx-->  classes.dex（Dalvik 字节码）
                                                      ↓ 打包进 APK
                                              classes.dex, classes2.dex ... classesN.dex
```

APK 里没有 .class，只有 dex。dex 里每个类的全名形如
`Lorg/hapjs/common/compat/BuildPlatform;`（L 开头、; 结尾、/ 分包）。

### 4.2 定位类的四种手段（由快到慢）

| 手段 | 适用场景 | 本案例用法 |
|---|---|---|
| ① 类名全局搜源码，先知道类的全限定名 | 任何场景的第一步 | 先在源码里确认目标类是 `org.hapjs.common.compat.BuildPlatform` |
| ② `dexdump 全量输出 + grep 类名` | 不想装额外工具（dexdump 随 build-tools 自带） | 逐 dex 循环 Select-String → 定位到 classes12.dex |
| ③ Android Studio **Build → Analyze APK** | 日常最舒服，图形化浏览类树、直接看 smali/反编译 | 推荐优先用 |
| ④ jadx / apktool / baksmali | 需要整体反编译回 Java 或拆 smali | 深度分析时用 |

**经验**：知道"类的全限定名"是搜索的锚点，而锚点来自**源码阅读**——
先在源码里搞清"这个行为由哪个类负责"，再去字节码里找它验证。

---

## 5. 如何读懂 dexdump 的字节码

### 5.1 输出结构

`dexdump -d classes.dex` 按"类 → 字段 → 方法 → 指令"分层输出：

```
Class descriptor  : 'Lorg/hapjs/common/compat/BuildPlatform;'   ← 类名
  Static fields  -                                               ← 静态字段（常量直接给值）
      name: 'CAR'   type: 'Ljava/lang/String;'   value: "car"
  Direct methods -                                               ← 方法列表
      name: 'isCar'  type: '()Z'
        insns size : 7 16-bit code units                         ← 方法体指令
          0000: const-string v0, "car"
          ...
```

### 5.2 读指令的核心：寄存器 + 操作码

Dalvik 是基于寄存器的指令集，`v0 v1` 是寄存器（相当于局部变量槽）。
本案例那条链只需 6 个操作码：

| 指令 | 含义 |
|---|---|
| `const-string v0, "car"` | 把字符串常量 "car" 装入寄存器 v0 |
| `invoke-static {v0, v1}, Landroid/text/TextUtils;.equals:(...)Z` | 静态调用 equals(v0, v1)，Z 表示返回 boolean |
| `move-result v0` | 把返回值取回 v0 |
| `return v0` | 返回 v0 |
| `sget v0, LFoo;.field:I` / `sput` | 读/写静态字段 |
| `if-eqz v0, :label` | v0 为 0（false）则跳转 |

### 5.3 类型描述符速查

| 符号 | 类型 |
|---|---|
| `Z` / `I` / `J` / `V` | boolean / int / long / void |
| `Ljava/lang/String;` | 对象类型（L 开头 ; 结尾） |
| `[I` / `[Ljava/lang/String;` | 数组 |

方法签名 `getScreenOrientation(Lorg/hapjs/render/Page;Lorg/hapjs/model/AppInfo;)I`
= 入参两个对象、返回 int。

### 5.4 点睛之笔：为什么看方法体里的字符串就能定案

这是整个取证最关键的一步推理：

```java
// 源码
public static boolean isCar() {
    return TextUtils.equals(CAR, BuildConfig.FLAVOR);
}
```

- `CAR = "car"` 是 BuildPlatform 自己的常量 → 字节码里第一个 `const-string "car"`
- `BuildConfig.FLAVOR` 是 `public static final String` → **javac 编译期常量折叠**，
  它的值会被直接内联进调用处字节码 → 方法体里第二个 `const-string` 就是 FLAVOR 的真实值

所以：**方法体里第二个字符串 = 编译那个包时用的 flavor**。
旧包里是 `"car"`，重建后的新包里是 `"phone"`——不需要任何运行时日志，静态验尸即定案。

> 同理可推广：想知道任何"编译期注入"的值（BuildConfig 字段、@SuppressLint 之外的
> 常量替换、switch 字符串），都可以在字节码里直接看到。

---

## 6. 可迁移的排查心法

1. **从症状的"产生 API"反向搜**，不凭直觉猜：横屏 → setRequestedOrientation；
   崩溃 → 异常类名；无响应 → 搜卡点生命周期。
2. **枚举所有产生路径，逐条排除**，剩下的那条就是答案的方向。
3. **"之前正常"= 回归**：一定有东西变了。先查"环境差异"（装了什么包、点了什么按钮、
   选了什么变体），再怀疑代码。
4. **磁盘产物异常是最便宜的强线索**：构建目录里多出来的、缺掉的、时间戳对不上的文件，
   值得第一时间看。
5. **磁盘证据 ≠ 设备事实**：构建过 car 不等于手机跑的是 car。
   决定性结论必须从"实际运行的载体"上取证（拉包验尸）。
6. **修复后必须闭环验证**：重复取证动作，确认新载体里的值真的变了。
7. **善用编译期常量内联**：BuildConfig.FLAVOR、VERSION_NAME 这类 `static final` 值
   都能从字节码里直接读出，比加日志、重新打包验证快得多。
```
