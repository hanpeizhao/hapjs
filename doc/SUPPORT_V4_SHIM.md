# support-v4 shim：为什么"自己写几个类"就能修复第三方库崩溃

> 本文档解释 `core/runtime/android/widgets/src/main/java/android/support/v4/` 下三个
> shim 类的由来与原理：为什么运行时缺的类可以由工程自己补上、编译期为什么不报错、
> 以及这种做法的边界与风险。对应修复的崩溃：快应用页面 `<text>` 组件创建时
> `NoClassDefFoundError: android.support.v4.util.LruCache`。

## 1. 问题背景

升级工具链后页面渲染阶段崩溃：

```
java.lang.IllegalStateException: failed to create element
  Caused by: java.lang.NoClassDefFoundError:
      com.facebook.fbui.textlayoutbuilder.TextLayoutBuilder
  Caused by: NoClassDefFoundError: Failed resolution of:
      Landroid/support/v4/util/LruCache;
```

依赖链：`widgets` 模块（`<text>` 组件）→ `textlayoutbuilder 1.4.0`（Facebook 的
StaticLayout 构建库）→ **运行时**引用 support-v4 的两个类：

| 缺失类 | 被谁引用 | 用途 |
|---|---|---|
| `android.support.v4.util.LruCache` | `TextLayoutBuilder` 的静态缓存 `sCache` | Layout 对象缓存（只用 `构造器(I)`、`get`、`put`） |
| `android.support.v4.text.TextDirectionHeuristicCompat`（接口） | `TextLayoutBuilder$Params` 字段、`StaticLayoutProxy` 参数 | 文字方向检测 |
| `android.support.v4.text.TextDirectionHeuristicsCompat` 静态常量 | `Params` 默认值（`FIRSTSTRONG_LTR`）、proxy 的映射表 | 提供 6 个方向的常量实例 |

textlayoutbuilder 的 POM 里 support-v4 是 **`provided`（可选）作用域**——发布方假设
使用方（当年的 Facebook App）classpath 里本来就有 support-v4，于是没有随包带上。
这解释了"为什么依赖树里看不到它"。

## 2. 原理：JVM 的类是"运行期按全限定名解析"的

### 2.1 编译期为什么不报错

关键在于**预编译库的字节码不参与我们工程的编译期检查**：

- `textlayoutbuilder-1.4.0.aar` 里的 `classes.jar` 是 Facebook 已经编译好的
  `.class` 文件。javac 处理它时只做**签名匹配**（方法描述符对不对得上），不会、
  也无法去校验"它引用的 `LruCache` 在最终 APK 里是否存在"。
- 我们自己的源码里没有任何一处直接 `import android.support.v4.util.LruCache`——
  引用完全藏在第三方字节码内部。javac 对第三方字节码的内部引用不做完整性检查。
- Gradle/AGP 的依赖检查（`checkDuplicateClasses` 等）只查**重复定义**（同名类出现
  在两个模块），不查**缺失引用**。

所以整条链在构建期全部绿灯，直到运行时第一次真正执行到 `new TextLayoutBuilder()`
→ 触发其静态字段 `sCache` 初始化 → ART 按全限定名
`android.support.v4.util.LruCache` 在 dex 里搜索 → 找不到 →
`NoClassDefFoundError`。**"找不到"报在运行时，而不是编译期**，这就是它诡异的地方。

### 2.2 为什么"自己写一个同包名类"能生效

JVM/ART 定位一个类只看两样东西：

1. **全限定类名**（`android.support.v4.util.LruCache`）——本质就是个字符串；
2. **ClassLoader**（APK 内所有 dex 是同一个 PathClassLoader）。

它**不关心**这个类的字节码来自哪个 jar/aar、由谁编译、放在哪个模块的源码目录里。
包名只是命名空间字符串，`android.support.v4.util` 不是被系统独占的保留路径
（受限的是 `java.*`、`android.*` **framework 深层类**的运行时调用——
`LruCache` 所在的 `android.support.*` 是当年 support 库自己的包，随 APK 分发，
本来就不是 framework 的一部分）。

因此只要 APK 的 dex 里有**一个类，其全限定名与第三方字节码引用的名字完全一致，
且公开签名（方法名 + 参数类型 + 返回类型）与调用点匹配**，运行时就能链接成功。
我们在 widgets 模块源码里写的三个类恰好满足这两点——它们和 textlayoutbuilder 的
字节码一起被打进同一个 APK，运行期无缝链接。

这就是"垫片（shim）"：不改第三方库一个字节，在其旁边补齐缺失的 API 面。

## 3. 取证过程：怎么知道要写哪些类、哪些方法

不能凭记忆实现——签名差一个返回类型就是 `NoSuchMethodError`（本项目此前在
soloader/yoga 上已经踩过一次：`loadLibrary(String)` 从 `void` 改 `boolean` 的教训）。
实际做法是**反编译字节码，精确列出 API 面**：

1. 解包 `textlayoutbuilder-1.4.0.aar` 的 `classes.jar`；
2. `javap -c` 逐类扫描 `android/support/v4` 的所有引用，得到上面的三行清单；
3. 对 `LruCache`，调用点只有：
   ```java
   new LruCache<>(1000)          // <init>(I)
   sCache.get(Object)            // V get(Object)
   sCache.put(Object, Object)    // V put(Object, Object)
   ```
4. 对 `TextDirectionHeuristicCompat`（接口）与 `TextDirectionHeuristicsCompat`
   （常量表），同样从字节码反推方法与字段清单。

## 4. 关键陷阱：staticlayout-proxy 用"引用相等"做映射

`staticlayout-proxy-1.4.0`（textlayoutbuilder 的伴生包）负责把 support 版方向
常量转成 framework 版：

```java
// 反编译自 StaticLayoutProxy.fromTextDirectionHeuristicCompat
if (h == TextDirectionHeuristicsCompat.LTR)              return TextDirectionHeuristics.LTR;
if (h == TextDirectionHeuristicsCompat.RTL)              return TextDirectionHeuristics.RTL;
if (h == TextDirectionHeuristicsCompat.FIRSTSTRONG_LTR)  return TextDirectionHeuristics.FIRSTSTRONG_LTR;
...
// 兜底 FIRSTSTRONG_LTR
```

注意是 `==`（引用相等）而不是 `equals`。这意味着 shim 的
`TextDirectionHeuristicsCompat` **必须提供 6 个独立的静态常量对象**
（`LTR` / `RTL` / `FIRSTSTRONG_LTR` / `FIRSTSTRONG_RTL` / `ANYRTL_LTR` / `LOCALE`），
让 proxy 能逐一匹配；如果只实现一个单例，所有方向都会落进兜底分支，文字方向
检测会静默错误。这就是 [TextDirectionHeuristicsCompat.java](../core/runtime/android/widgets/src/main/java/android/support/v4/text/TextDirectionHeuristicsCompat.java)
把每个常量委托给 framework 对应实现的原因——语义与路由双正确。

## 5. 为什么不能直接加 support-compat 整包

最省事的思路是给 widgets 加 `api 'com.android.support:support-compat:28.0.0'`。
尝试过，被 AGP 的 **duplicate classes 检查**拦截：

```
Duplicate class android.support.v4.app.INotificationSideChannel
    found in androidx.core:core:1.3.1 and support-compat:28.0.0
```

androidx 迁移时为了兼容旧库，`androidx.core` 里**故意保留了 4 个**
`android.support.v4.*` 过渡类（AIDL parcelizer 类）。它们与 support-compat 整包
冲突，而 AGP 9 已彻底移除 Jetifier（无法把 support 自动映射为 androidx）。
整包不可行 → 只能精准补缺 → shim 是唯一干净路径。

## 6. 三个 shim 文件的实现要点

| 文件 | 要点 |
|---|---|
| [LruCache.java](../core/runtime/android/widgets/src/main/java/android/support/v4/util/LruCache.java) | AOSP 语义完整实现（访问序 LinkedHashMap、容量淘汰、`synchronized` 线程安全——`sCache` 是静态缓存，UI 线程与 GlyphWarmer 的 Handler 线程会并发访问） |
| [TextDirectionHeuristicCompat.java](../core/runtime/android/widgets/src/main/java/android/support/v4/text/TextDirectionHeuristicCompat.java) | 纯接口，两个 `isRtl` 重载签名对齐 support-v4 27/28 |
| [TextDirectionHeuristicsCompat.java](../core/runtime/android/widgets/src/main/java/android/support/v4/text/TextDirectionHeuristicsCompat.java) | 6 个常量分别委托 framework `android.text.TextDirectionHeuristics` 对应项（API 18+，minSdk 21 满足） |

## 7. 风险边界与守则

这种做法有效，但有明确的适用边界：

1. **签名必须从字节码取证**，绝不凭记忆——返回类型、参数顺序差一点就是
   `NoSuchMethodError` / `AbstractMethodError`；
2. **行为语义要对齐**（尤其静态字段"引用相等"这类隐式契约）；实现里已用注释
   标注了每个陷阱的出处；
3. **只适用于纯 Java/无 native 的缺失类**——缺的是 `.so` 或 framework 私有
   native 方法时垫不了；
4. **与上游同步时需复查**：若未来 textlayoutbuilder 升级，其内部对 support-v4
   的引用面可能扩大，届时需重新 javap 取证增补；
5. 文件头部与 `widgets/build.gradle` 的注释都写明了 shim 的存在原因——防止
   后来者误以为这些是正常业务代码而随意重构掉。

一句话总结：**Java 运行时只认"名字 + 签名"，不认"出处"**。编译期没人检查第三方
库的内部引用，运行期缺什么补什么——只要签名对齐、语义一致，同包名自实现就是
合法且可维护的垫片。
