# beautiful_dump

更优雅、更广泛、抗混淆的 U3D / IL2CPP dump 工具链。

为 **ACE 比赛 / CTF 多版本逆向** 场景而设计 —— 每次构建函数名都被随机化（Beebyte / CodeStage 类混淆器），目标是从原始 dump 出发，**恢复一套跨版本稳定的可读 SDK**。

## 两块交付物

```
┌────────────────────────┐        ┌─────────────────────────────┐
│  android/  (APK)       │  →     │  bd/  (host-side, Python)    │
│  设备上一键 dump       │        │  跨版本签名匹配 + SDK 导出   │
└────────────────────────┘        └─────────────────────────────┘
```

| 用途 | 工具 |
|---|---|
| Android 上选目标应用 → 一键 dump | **APK**（MD3 Compose UI + root + native dumper） |
| 主机上把 dump 变成可读 SDK | **Python CLI**（编排 Il2CppDumper / SoFixer，做匹配） |

## APK（设备侧）

Material 3 应用，已 root 的 arm64 Android 设备运行。

### 功能

- 列出已安装应用（自动识别疑似 Unity 应用并打 `Unity` 标签）
- ModalBottomSheet 选择框 + 实时搜索
- 一键 dump：自动拉起目标 → 等 `libil2cpp.so` 加载 → root 内存 dump → 输出到 `/sdcard/beautiful_dump/<pkg>/`
- 结果卡片显示 PID / 文件 / 大小 / metadata 地址
- 点「打开文件管理器查看」自动跳转到系统文件管理器（多 intent 兜底：原生、Samsung MyFiles、MIUI Explorer、Files by Google、chooser）

### 构建

```bash
cd android
./gradlew :app:assembleRelease         # 需要 Android Studio Iguana+ 或 AGP 8.5+
# 输出 app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

依赖：
- Android SDK 34
- NDK r26+ （`cmake` 通过 SDK Manager 安装）
- 设备：arm64 + Magisk / KernelSU root

第一次运行会向 Magisk 申请 root，授予即可。

### 工作原理

```
APK Kotlin (libsu)
       │
       │ Shell.cmd("$nativeLibDir/libbd_dumper.so $pid $outdir")
       ▼
libbd_dumper.so   ← 内置 ARM64 ELF，编译时设了 -emain，运行时作为 binary exec
       │
       │ /proc/<pid>/mem
       ▼
libil2cpp.so + global-metadata.dat（解密后）
       │
       ▼
/sdcard/beautiful_dump/<pkg>/
```

把 native dumper 编成 `.so` 是为了让 Android 自动放进 `nativeLibraryDir` 并保留可执行位（Termux 同款套路），不走 `dlopen`，直接当 ELF 跑。

### 自定义 magic

如果游戏改了 metadata magic（`0xFAB11BAF`），在主界面的「自定义 metadata magic」框里填新值（如 `deadbeef`），再按 Dump。

## Python 主机端

用来把 APK 产的 dump 进一步处理成可读 SDK。

### 一键流

```bash
./setup.sh                # 自动拉 Il2CppDumper / SoFixer / dnfile / lief
# 把 APK dump 出来的目录 pull 到主机
adb pull /sdcard/beautiful_dump/com_example_unitygame ./out/v1

./beautiful_dump.py fix    out/v1/libil2cpp.so
./beautiful_dump.py parse  out/v1/libil2cpp.fixed.so out/v1/global-metadata.dat
./beautiful_dump.py sig    out/v1/libil2cpp.fixed.so out/v1/il2cpp_out
./beautiful_dump.py sdk    out/v1/il2cpp_out/signatures.json -o out/v1/sdk
```

输出 `out/v1/sdk/` 含：

- `sdk.h` —— C 头文件，含稳定名→RVA 的 `#define`
- `sdk_ida.py` —— IDAPython，一键给 IDB 改名
- `sdk_frida.js` —— Frida 运行时按稳定名解析地址

### 跨版本去混淆

```bash
./beautiful_dump.py sig out/v1/libil2cpp.fixed.so out/v1/il2cpp_out  # 已生成
./beautiful_dump.py sig out/v2/libil2cpp.fixed.so out/v2/il2cpp_out
./beautiful_dump.py match out/v1/il2cpp_out/signatures.json out/v2/il2cpp_out/signatures.json
```

`matches.json` 即可读名映射 —— v1 的稳定名 → v2 中等价方法的地址，即使 v2 把所有名字重新随机化了。

### 抗混淆锚点

混淆器改不了的东西就是签名锚点：

| 锚点 | 为什么稳定 |
|---|---|
| 字符串字面量 | "Login successful"、url、proto 名等大概率不变 |
| `UnityEngine.*` 调用 | 引擎 API 不能改 |
| 返回 / 参数类型 | 类型签名稳定 |
| arm64 prologue hash | 同 LLVM 版本相同逻辑前 32 字节一致（call/branch 立即数已置零） |
| 命名空间 + 继承链 | `MonoBehaviour` 子类继承关系稳定 |

`bd/signature.py` 提取，`bd/matcher.py` 加权评分匹配，命中的锚点在 `reasons` 字段里。

## 项目结构

```
beautiful_dump/
├── android/                       APK 源码
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── cpp/dumper.cpp     native dumper (arm64)
│   │       ├── kotlin/com/beautifuldump/
│   │       │   ├── MainActivity.kt
│   │       │   ├── MainViewModel.kt
│   │       │   ├── data/AppRepository.kt
│   │       │   ├── dump/DumpRunner.kt
│   │       │   ├── ui/Theme.kt
│   │       │   └── util/FileManagerIntent.kt
│   │       └── res/
│   ├── build.gradle.kts
│   └── settings.gradle.kts
│
├── bd/                            Python 分析后端
│   ├── dump.py                    Frida 备选 dumper（主机端）
│   ├── elf_fix.py                 SoFixer + LIEF 后备
│   ├── il2cpp_dumper.py
│   ├── signature.py               抗混淆特征向量
│   ├── matcher.py                 跨版本匹配
│   ├── sdk_export.py              SDK 输出
│   └── zygisk_dump.py             Zygisk-Il2CppDumper 编排
│
├── frida_scripts/memdump.js       Frida 备选 dump 脚本
├── beautiful_dump.py              Python CLI 入口
├── setup.sh                       拉取 OSS 依赖
└── requirements.txt
```

## 已知限制

- arm64 ELF + IL2CPP 16~31（Unity 5.3 ~ 2023+）。
- APK 内的 dumper 不处理 `libil2cpp.so` 之外的 VMP / 混合 native 加固。
- APK 假设 Magisk / KernelSU 提供的 `su` 在 PATH。
- 设备首次安装后请确认 jniLibs 提取出来的 `libbd_dumper.so` 有执行位（部分 OEM 会清掉，DumpRunner 会 `chmod 0755` 兜底）。

## License

MIT
