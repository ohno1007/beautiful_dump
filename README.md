# beautiful_dump

更优雅,更广泛,抗混淆通杀的 Unity IL2CPP dump 后处理。

把 Il2CppInspector 导出的 metadata JSON 喂进来, 输出一份重命名后的 dump.cs
和一份 `obf 名 → 还原名 + 置信度` 的 JSON 符号映射, 跨版本通杀仅做名字
重命名 (BeeByte / Obfuscar / 自定义重命名步骤) 的混淆器。

* 不依赖任何运行时, 单文件静态 ELF。
* 中文交互式向导, 回车采用默认值。
* 内置 Unity 通用 + FPS 专用结构签名。
* 给每条建议附带置信度和来源, 方便人工复核。

## 工作原理

```
Il2CppInspector JSON
       │
       ▼
┌──────────────────────────────────────────────────────────────┐
│ 1. 锚定: 锁住 UnityEngine/System 等已知 SDK + 看起来干净的名字 │
│ 2. 消息: MonoBehaviour 子类按签名匹配 OnTriggerEnter 等回调   │
│ 3. 签名: 按 base/接口/字段类型/方法签名匹配通用 + FPS 专用规则 │
│ 4. 传播: 字段类型 → 字段名, 角色 → 方法名 (低置信度补刀)      │
└──────────────────────────────────────────────────────────────┘
       │
       ▼
   重命名 dump.cs  +  symbols.json
```

针对的混淆形态:

| 混淆形态                          | 支持度                                           |
|-----------------------------------|--------------------------------------------------|
| 类/方法/字段名乱码                | 主要目标, 通杀                                   |
| 编译器合成名 (`<>c__DisplayClass`) | 自动跳过, 不会误改                                |
| 字符串加密                        | 不直接处理, 先脱壳/dump 解密后字符串再喂进来     |
| control-flow flattening           | 不影响本工具 (我们不看方法体)                    |
| metadata.dat 加密                 | 先用 Il2CppInspector / Il2CppDumper 还原 metadata |

## 快速开始

### 在 Android 上跑 (Termux / 已 root)

```bash
# 1. 把 ELF push 到设备
adb push dist/beautiful-dump-android-arm64 /data/local/tmp/
adb shell chmod +x /data/local/tmp/beautiful-dump-android-arm64

# 2. 把 Il2CppInspector 导出的 metadata JSON 也 push 过去
adb push metadata.json /data/local/tmp/

# 3. 进入 shell 跑工具 (默认进入中文交互式向导)
adb shell
$ cd /data/local/tmp
$ ./beautiful-dump-android-arm64
```

或在 Termux 里直接:

```bash
pkg install root-repo  # 可选, 如果需要 root
./beautiful-dump-android-arm64
```

### 在 PC 上跑

```bash
make host                       # 编译当前主机架构的二进制
./dist/beautiful-dump-host      # 进入交互向导
```

### 交互式向导

```
+----------------------------------------------------------+
|   beautiful_dump  ·  Unity IL2CPP 抗混淆通杀 dump 后处理 |
+----------------------------------------------------------+
  接下来会逐项询问参数, 回车采用 [默认值]
  支持 ~/ 路径, 相对路径会基于当前目录解析
  中途 Ctrl+C 可随时退出

=== 第 1 步 / 共 4 步: 选择输入 ===
› Il2CppInspector 导出的 metadata JSON 文件路径 [默认: metadata.json]:
=== 第 2 步 / 共 4 步: 选择输出 ===
› 输出: 重命名后的 dump.cs [默认: metadata.renamed.cs]:
› 输出: 符号映射表 (JSON) [默认: metadata.symbols.json]:
=== 第 3 步 / 共 4 步: 选择启用的恢复阶段 ===
› 启用 Unity 消息函数恢复 (Update / OnTriggerEnter / ...)? (Y/n) [默认: Y]:
› 启用结构签名匹配 (Player / Weapon / Health / ...)? (Y/n) [默认: Y]:
› 启用引用传播 (字段类型 → 字段名)? (Y/n) [默认: Y]:
=== 第 4 步 / 共 4 步: 确认 ===
  输入:        metadata.json
  输出 dump:    metadata.renamed.cs
  ...
› 以上设置是否正确? (Y/n) [默认: Y]:
```

### 非交互模式 (CI / 脚本)

```bash
./beautiful-dump-android-arm64 \
  --non-interactive \
  --out-dump out.cs \
  --out-map out.json \
  metadata.json
```

可选开关 `--no-messages` / `--no-signatures` / `--no-propagation` 关闭单个阶段。

## 输入格式

工具接受 Il2CppInspector 的 JSON model。最小可用 schema:

```json
{
  "Types": [
    {
      "Name": "Aa",
      "Namespace": "",
      "BaseType": "UnityEngine.MonoBehaviour",
      "Fields": [
        {"Name": "Il", "Type": "UnityEngine.CharacterController", "IsPublic": false}
      ],
      "Methods": [
        {"Name": "ll", "ReturnType": "System.Void", "Parameters": []},
        {"Name": "lI1", "ReturnType": "System.Void", "Parameters": [
          {"Name": "x", "Type": "UnityEngine.Collider"}
        ]}
      ]
    }
  ]
}
```

兼容字段名大小写差异 (Name/name, FullName/full_name, ...), 也接受根直接是数组的形态。

## 输出样例

`obf 名 → 还原名` 注释保留供人工复核:

```cs
class PlayerController : UnityEngine.MonoBehaviour {  /* obf: Aa (conf=0.85 signature:PlayerController) */
    private UnityEngine.CharacterController characterController;  /* obf: Il ... */
    private UnityEngine.Camera playerCamera;                      /* obf: I1 ... */
    private System.Single moveSpeed;                              /* obf: Il1 ... */
    private System.Void OnTriggerEnter(UnityEngine.Collider x) { }/* obf: lI1 ... */
}
```

`symbols.json`:

```json
{
  "types": [
    {
      "type": {
        "original": "Aa",
        "renamed": "PlayerController",
        "confidence": 0.85,
        "reason": "signature:PlayerController",
        "role": "Player"
      },
      "fields": [
        {"original": "Il", "renamed": "characterController",
         "confidence": 0.80, "type": "UnityEngine.CharacterController", "reason": "signature:PlayerController"}
      ],
      "methods": [...]
    }
  ]
}
```

## 内置签名

* **Unity 通用**: GameManager, UIButtonHandler, AudioController, AnimatorController,
  ObjectPool, SceneLoader, Photon/Mirror NetworkBehaviour
* **FPS 专用**: PlayerController (CharacterController-based), RigidbodyPlayer,
  MouseLook, Health, Weapon, Projectile, Recoil, AmmoHUD, EnemyAI

## 编译

```bash
make            # 默认: arm64 Android ELF
make android    # 同上
make host       # 主机架构
make test       # 跑单元测试
```

需要 Go 1.22+。无 cgo, 无外部依赖。

## 限制 & 不会做的事

* 不解密字符串或脱壳: 先用现成工具搞定 metadata, 再喂进来。
* 不分析方法体: 控制流混淆不影响本工具, 但也意味着拿不到 string 引用做更精
  准的命名 (后续版本会加可选的 strings.json 输入)。
* 是启发式工具: 任何重命名都是建议, `/* obf: ... */` 注释保留原名供复核。

## Roadmap

* `--strings` 输入: 用 `Debug.Log`/资源名字符串做名字提示。
* IDA / Ghidra 重命名脚本输出。
* Frida hook 模板生成。
* Mono 后端 (Assembly-CSharp.dll) 适配器。
