# Android APK 打包说明

本仓库在原始 [qsdbl/model-gateway](https://github.com/qsdbl/model-gateway) 源码基础上，
增加了把网关打包成 Android APK 的启动器与构建脚本。

- 产物：`apk/ModelGateway-1.0.0.apk`（约 20 MB）
- 启动器源码：`apk/android-src/`
- 构建脚本：`apk/build.sh`

## APK 是什么

APK 是一个启动器 + 内嵌 Linux 运行时：

```
APK
├── assets/rootfs.tar.gz   Alpine 3.20 + musl + Python 3.12 + fastapi/uvicorn/segno
├── assets/app.tar.gz      本仓库的 gateway/ 与 admin/
├── assets/proot           proot 主程序
├── assets/proot-loader    --link2symlink 所需的 loader 可执行文件
└── lib/arm64-v8a/         libproot.so / libtalloc.so / libandroid-shmem.so
```

首次启动时把 rootfs 解压到应用私有目录，再用 proot 以 root 身份在
客体内拉起 `uvicorn gateway.app:app`，监听 `127.0.0.1:8080`。
控制页提供启动/停止、设置后台口令与 API Key、改端口、打开后台、查看日志。

## 为什么用 proot 而不是重写

网关是 15k 行 Python（FastAPI + SQLite + 多上游适配器）。用 Java 重写会
改变行为，且上游协议（WorkBuddy / Qoder 的签名、扫码、签到）细节很多，
逐条复刻不现实。内嵌 CPython 能保持与上游仓库行为一致。

## 已知限制

- **仅 arm64**（`arm64-v8a`），这是车机与主流 Android 设备的 ABI。
- **首次启动需要 1–2 分钟**解压 rootfs，之后启动是秒级。
- **proot 依赖 Android 允许 exec**。Android 10+ 禁止从应用私有 data 目录执行，
  所以 proot 以 `libproot.so` 形式随 APK 安装（`nativeLibraryDir` 可执行）。
- **未在真机验证**。本机环境本身运行在 proot 内，无法嵌套执行 proot，
  因此 proot 拉起客体这一步无法在此实测；其余环节（解包、Python 运行时、
  网关启动、健康检查）均已验证通过。
- 上游 WorkBuddy / Qoder 的 CLI 依赖（`codebuddy`、`qoderclicn`）**未**打进
  rootfs。需要扫码登录或真实推理时，要先在客体内安装它们，或改用后台的
  「粘贴凭据」方式导入 `.info`。当前默认 `GATEWAY_UPSTREAM=mock`。

## 重新构建

```bash
# 依赖：本机 aapt2 / android.jar / build-tools（d8、zipalign、apksigner）
./apk/build.sh
```

脚本会重新打包 `gateway/`、`admin/` 与 rootfs，编译 Java，产出并签名 APK。
签名密钥 `build/mg.jks`（口令 `mgpass123`），仅用于自用分发，请按需替换。

## 在自己手机上安装

1. 下载 `apk/ModelGateway-1.0.0.apk`
2. 允许安装未知来源
3. 打开 App → 点「设置口令」填后台登录口令 → 点「启动」
4. 就绪后点「打开后台」，用刚设的口令登录
5. 客户端接 `http://<手机IP>:8080/v1`（需把监听改为 `0.0.0.0` 或做端口转发）
