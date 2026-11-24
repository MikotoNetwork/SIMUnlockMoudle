# SIM Auto Unlock

[![release](https://github.com/MikotoNetwork/SIMUnlockMoudle/actions/workflows/build-apk.yml/badge.svg)](https://github.com/MikotoNetwork/SIMUnlockMoudle/actions/workflows/build-apk.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)]()



基于 [![API](https://img.shields.io/badge/API-102-brightgreen)](https://github.com/libxposed/api)
## 功能特性

- **开机自动解锁**：注入 `com.android.systemui`，通过系统标准 `SIM_STATE_CHANGED` 广播与开机后卡槽状态兜底检查触发解锁，不依赖特定 ROM 的私有锁屏实现（已验证适配 ColorOS 等定制系统）。
- **单卡 / 双卡独立管理**：主界面自动检测设备卡槽数量，支持为 SIM 1、SIM 2 分别设置 PIN 码；留空的卡槽不会执行自动解锁。
- **单次尝试防锁卡**：按卡槽独立记录开机解锁状态，每次开机每个卡槽严格仅尝试解锁一次，防止因 PIN 变更导致反复重试触发 PUK 锁定。
- **本地加密存储**：采用设备硬件特征 + 应用 UUID 盐值 + 16 字节随机 Salt，经 `PBKDF2WithHmacSHA256`（100,000 次迭代）派生 AES-256 密钥，通过 AES-256-GCM 加密后经 [libxposed Service](https://github.com/libxposed/service) `RemotePreferences` 同步至目标进程。
- **零敏感日志**：运行日志仅输出 Hook 状态、卡槽编号及系统返回的结果状态码，不输出任何 PIN 明文或密文。

## 环境要求

| 项目 | 要求 |
| :--- | :--- |
| **Xposed 框架** | 支持 Modern Xposed API 102 的 LSPosed |
| **模块作用域** | 系统界面（`com.android.systemui`） |
| **构建 JDK** | Java 17 |

## 使用方法

1. 从本仓库 [![release](https://github.com/MikotoNetwork/SIMUnlockMoudle/actions/workflows/build-apk.yml/badge.svg)](https://github.com/MikotoNetwork/SIMUnlockMoudle/actions/workflows/build-apk.yml) 下载并安装模块 APK。
2. 打开 **LSPosed 管理器 → 模块**，启用本模块并确认作用域已勾选 **系统界面 (`com.android.systemui`)**。
3. 打开模块主界面，根据提示输入需要自动解锁的卡槽 PIN 码（无需解锁的卡槽留空），点击 **加密保存**。
4. 重启设备，开机后模块将自动完成 SIM 卡 PIN 解锁。

### 日志排查

打开 **LSPosed 管理器 → 日志**，搜索关键词 `SimAutoUnlock`：

- `Hooked SystemUI Application.onCreate`：模块已成功注入 SystemUI。
- `SIM slot 0 unlock attempted, resultType=0`：卡槽 1 解锁成功（`0` 对应 `PIN_RESULT_TYPE_SUCCESS`；若为其他数值则表示 PIN 错误或解锁异常）。
- `SIM slot 1 skipped (no PIN configured)`：该卡槽未配置 PIN，已跳过。

## 开发与构建

### 核心目录结构

```text
app/src/main/
├── java/cn/ltingyuanfang/simunlockmoudle/
│   ├── MainActivity.java            # 配置界面，读写 RemotePreferences
│   └── core/
│       ├── SimUnlockModule.java     # libxposed API 102 入口与解锁逻辑
│       └── PinCrypto.java           # 设备特征派生密钥与 AES-256-GCM 加解密
└── resources/META-INF/xposed/
    ├── java_init.list               # 注册 cn.ltingyuanfang.simunlockmoudle.core.SimUnlockModule
    ├── scope.list                   # 声明 com.android.systemui
    └── module.prop                  # minApiVersion=102 / targetApiVersion=102
```

### 依赖配置

在 `settings.gradle(.kts)` 中确保已包含 `mavenCentral()`，并在 `app/build.gradle(.kts)` 中引入：

```kotlin
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.1")
}
```

### 混淆配置（R8 / ProGuard）

启用代码压缩时，请在 `app/proguard-rules.pro` 中保留模块类与 `libxposed` 相关类，避免入口被裁剪或跨进程通信异常：

```proguard
-dontwarn io.github.libxposed.**
-keep class cn.ltingyuanfang.simunlockmoudle.** { *; }
-keep class io.github.libxposed.** { *; }
```

### GitHub Actions 自动构建

将工作流文件放入 `.github/workflows/build-apk.yml` 后，推送代码即可自动构建 APK：

- **Release 签名构建**：在仓库 Settings → Secrets 中配置 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`；推送 `v*` 标签时会自动创建 GitHub Release。
- **Debug 构建**：未配置签名 Secrets 时，工作流会自动回退构建 Debug APK 并上传为构建产物。

## 注意事项

- 修改 SIM 卡 PIN 码后，请及时在模块主界面更新并重新保存。
- 密钥派生依赖本机硬件特征，直接将配置文件迁移至其他设备将无法解密，需在新设备上重新输入保存。
- 自行编译发布时建议更换 `PinCrypto.java` 中的 `APP_PEPPER` UUID 常量。

## 免责声明与使用限制

- **免责声明**：本模块按“现状”（AS IS）提供，仅供个人学习、研究与个人设备自用。使用本模块可能涉及系统修改、SIM 卡锁定（如因误填或更改 PIN 导致触发 PUK 锁）及其他不可预见的风险，由此产生的一切直接或间接后果均由使用者自行承担，作者不承担任何责任。
- **原版支持边界**：作者仅认可本仓库 [Releases](https://www.google.com/search?q=https://github.com/MikotoNetwork/SIMUnlockMoudle/releases) 发布的原版安装包；对于第三方搬运、重新打包或自行修改代码构建的版本所引发的任何问题，作者不提供技术支持且不承担任何责任。
- **禁止商用与贩卖**：本项目完全免费开源，严禁任何个人或组织将本项目代码、编译产物（APK）或其修改版本用于商业用途、收费分发、捆绑销售或倒卖牟利。

## 开源协议

本项目基于 [![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)开源，归属声明见 [NOTICE](https://github.com/MikotoNetwork/SIMUnlockMoudle/blob/main/NOTICE)。
