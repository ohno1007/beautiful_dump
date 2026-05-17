# beautiful_dump

更优雅、更广泛、抗混淆的 U3D / IL2CPP dump 工具链。

为 **ACE 比赛 / CTF 多版本逆向** 场景而设计 —— 每次构建函数名都被随机化（Beebyte、CodeStage 类混淆器），目标是从原始 dump 出发，**恢复一套跨版本稳定的可读 SDK**。

## 设计

不重复造轮子，编排成熟开源工具，再补一层「跨版本签名匹配 + SDK 导出」：

```
                ┌──────────────────────────┐
   Android      │  Zygisk-Il2CppDumper     │  ← 优选：开机自动 dump
   (rooted)     │  └ or Frida memdump.js   │  ← 备选：运行时 dump
                └────────────┬─────────────┘
                             ▼
                   libil2cpp.so (mem image)
                   global-metadata.dat (decrypted)
                             │
                             ▼
                ┌──────────────────────────┐
                │  SoFixer / LIEF          │  → libil2cpp.fixed.so
                └────────────┬─────────────┘
                             ▼
                ┌──────────────────────────┐
                │  Il2CppDumper (Perfare)  │  → dump.cs / script.json / DummyDll
                └────────────┬─────────────┘
                             ▼
                ┌──────────────────────────┐
                │  bd.signature  (本项目)  │  → 与名字无关的特征向量
                │  bd.matcher    (本项目)  │  → 跨版本名字恢复
                │  bd.sdk_export (本项目)  │  → sdk.h / IDA / Frida
                └──────────────────────────┘
```

依赖的开源项目（在 `setup.sh` 中自动拉取）：

| 项目 | 用途 |
|---|---|
| [Il2CppDumper](https://github.com/Perfare/Il2CppDumper) | metadata 解析、DummyDll 生成 |
| [Zygisk-Il2CppDumper](https://github.com/Perfare/Zygisk-Il2CppDumper) | Zygisk 模块，自动 dump |
| [SoFixer](https://github.com/F8LEFT/SoFixer) | 内存 dump → 可解析 ELF |
| [Frida](https://frida.re) | 运行时注入 dumper |
| [LIEF](https://lief.re) | Python ELF 重构后备 |

## 安装

```bash
git clone <this-repo> && cd beautiful_dump
./setup.sh              # 装 python deps + 拉 Il2CppDumper / SoFixer / Zygisk module
# 还需要：
#  - .NET 6 Runtime  (sudo apt install dotnet-runtime-6.0)
#  - adb 能识别已 root 的 arm64 设备
#  - 设备上 /data/local/tmp/frida-server 可执行（Frida 路径）
#    或 设备已安装 Magisk + 已 flash Zygisk-Il2CppDumper（Zygisk 路径）
```

## 用法

### 一键流（推荐）

```bash
./beautiful_dump.py all -p com.example.unitygame --spawn
```

输出在 `./out/com_example_unitygame/`：

```
out/com_example_unitygame/
├── libil2cpp.so           # 内存 dump
├── libil2cpp.fixed.so     # ELF 修复后
├── global-metadata.dat    # 解密后的 metadata
├── dump_manifest.json     # 模块基址 / metadata 版本等
├── il2cpp_out/            # Il2CppDumper 输出
│   ├── dump.cs
│   ├── script.json
│   ├── stringliteral.json
│   ├── DummyDll/
│   └── il2cpp.h
├── signatures.json        # 抗混淆特征向量
└── sdk/                   # ★ 可读 SDK
    ├── sdk.h              # C 头文件
    ├── sdk_ida.py         # IDAPython 重命名脚本
    └── sdk_frida.js       # Frida 运行时解析器
```

### 跨版本去混淆

dump 两个版本，做名字映射：

```bash
./beautiful_dump.py all -p com.example.unitygame -o out/v1
./beautiful_dump.py all -p com.example.unitygame -o out/v2   # 装了新版本后
./beautiful_dump.py match out/v1/signatures.json out/v2/signatures.json -o mapping.json
```

`mapping.json` 给出 v1 中已分析的可读名 → v2 中等价方法的地址，即使 v2 把所有方法名都重新随机化了一次。

### 分步执行

每一步都可以单独跑：

```bash
./beautiful_dump.py dump   -p com.example.unitygame --spawn
./beautiful_dump.py fix    out/com_example_unitygame/libil2cpp.so
./beautiful_dump.py parse  out/com_example_unitygame/libil2cpp.fixed.so out/com_example_unitygame/global-metadata.dat
./beautiful_dump.py sig    out/com_example_unitygame/libil2cpp.fixed.so out/com_example_unitygame/il2cpp_out
./beautiful_dump.py sdk    out/com_example_unitygame/signatures.json -o out/com_example_unitygame/sdk
```

## 抗混淆原理

混淆器会重命名 class / method / field，但**有一些特征是它动不了的**，这些就是签名匹配的锚点：

| 锚点 | 为什么稳定 |
|---|---|
| **字符串字面量** | "Login successful"、url、proto 名等大概率不变 |
| **UnityEngine.* 调用** | 引擎类型 / 方法名是 SDK 公开 API，不能改 |
| **返回类型 / 参数类型** | 类型签名不变（除非整体重构） |
| **arm64 prologue hash** | 同样的逻辑 + 同 LLVM 版本，前若干条指令一致（call/branch 立即数已置零） |
| **类的命名空间 + 继承结构** | `MonoBehaviour` 子类继承链稳定 |

`bd/signature.py` 提取这些特征，`bd/matcher.py` 用加权评分 + bucket 优化做匹配。每条匹配的 `reasons` 字段记录命中的锚点，便于人工 review。

## 自定义 metadata magic

部分游戏会改 `0xFAB11BAF` magic。如果 `dump` 时找不到 metadata：

```bash
# 自定义 magic，hex 小端字节序
./beautiful_dump.py dump -p com.example.game --magic 'de ad be ef'
```

如果连 magic 都被加密在 page 里 —— 内存 dump 之后用 `binwalk` 或人工搜索 metadata 表项的特征（如 string table）然后用 `--magic` 重 dump，或者直接 `--engine zygisk` 让 Zygisk 模块在 il2cpp 初始化完成后从内部指针取。

## 已知限制

- 仅支持 **arm64 ELF + IL2CPP 16~31 版本**（Unity 5.3 ~ 2023+）。
- 对 il2cpp 之外的加固（VMP、混合 native code）无效 —— 那是另一个工具的事。
- DummyDll 解析需要 `dnfile`，没装也能跑（精度降低）。

## License

MIT
