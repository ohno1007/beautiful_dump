# beautiful_dump - Android APK

Material 3 应用，已 root 的 arm64 设备上一键 dump U3D 游戏。

## 构建

最简：用 **Android Studio Iguana 或更新版本**打开 `android/` 目录，等 gradle sync 完，跑 `Run > Build APK`。

命令行：

```bash
# 1) 生成 wrapper（如果还没有）
cd android
gradle wrapper --gradle-version 8.7

# 2) 构建
export ANDROID_HOME=$HOME/Android/Sdk
./gradlew :app:assembleDebug
# or release（无签名时用 debug keystore，生产环境请自配 keystore）
./gradlew :app:assembleRelease

# 3) 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

需要 SDK 组件：
- compileSdk 34 platform
- Build-Tools 34.0.0+
- NDK r26+
- CMake 3.22.1+

## 验证

1. 在已 root 的 arm64 设备打开「Beautiful Dump」
2. 顶部 root 弹窗 → 授予
3. 点「选择应用」→ 在 ModalBottomSheet 里搜你的 U3D 游戏 → 选中
4. 点「一键 Dump」
5. 结果卡片出现后点「打开文件管理器查看」
6. dump 出现在 `/sdcard/beautiful_dump/<pkg>/`：
   - `libil2cpp.so`（内存镜像）
   - `global-metadata.dat`（明文）
   - `libunity.so`（可选）
   - `maps.txt`（/proc/pid/maps 备份）

## 故障排查

| 现象 | 原因 |
|---|---|
| `需要 root 权限` | Magisk/KernelSU 未授权应用 root |
| `无法定位 PID 或 libil2cpp.so 未加载` | 游戏启动失败 / 没注入完 / 加固延迟解密 → 手动启动游戏，进到主界面后再回 APK 点 Dump |
| `metadata magic 找不到` | 游戏自定义了 magic → 在主界面填「自定义 metadata magic」 |
| 文件管理器没打开 | 系统未装文件管理器 → 兜底会弹 chooser 让你选分享 |
| `dumper not executable` | 部分 OEM 清掉 jniLibs 执行位 → DumpRunner 已 `chmod 0755` 兜底，仍失败请检查 selinux |
