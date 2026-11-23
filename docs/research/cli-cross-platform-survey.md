# CLI 端跨平台交叉编译调研

- 调研日期：2026-11-17。
- 范围：验证「CLI 薄壳复用 core」在三平台（Windows 10 / Ubuntu Desktop / Debian 13 Server）的可编译性与可运行性。仅调研，不产出产品源码。
- 方法：编写测试性 CLI 原型（clap 子命令 + `$EDITOR` 集成 + 直接链接 `djhx-memo-core`），本机编译 Linux 版、用 `cross`（Docker）交叉编译 Windows 版，分发到三台 KVM 虚拟机运行冒烟。
- 基线：core 0.1.10 / server 0.1.4 / 协议 2 / schema 3；本调研未修改任何项目源码与契约。
- 限制：原型只覆盖本地（未登录）空间的 state/list/new/get/export 与 `$EDITOR` 编辑入口，不验证登录、同步、冲突与设备管理；不测 TUI、不测 macOS。跨平台结论限于 x86_64 与所列系统版本。

## 1. 结论

CLI 端可以做成「第四个薄壳」：业务逻辑（本地 SQLite、同步状态机、outbox、SSE、搜索、回收站、导出）全部留在 core，CLI 只负责参数解析、`$EDITOR` 集成与凭据存储。三平台交叉编译与运行验证均通过：

| 平台 | 编译方式 | 产物 | 运行验证 |
|---|---|---|---|
| Linux x86_64 | 本机 `cargo build --release`（glibc） | 8.2 MB | debian13、ubuntu2604 全通过 |
| Windows x86_64 | `cross`（Docker，mingw-w64） | 7.7 MB PE32+ | win10 全通过 |

Linux 分发的唯一门槛是 glibc≥2.39；Windows 交叉编译需要为旧版 mingw-w64 打 3 个补丁（见第 4 节），均已固化为可复现脚本。

## 2. 虚拟机环境（KVM，NAT 内网）

| 虚拟机 | 系统 | 连接 | Rust | 外网 |
|---|---|---|---|---|
| ubuntu2604 | Ubuntu 26.04.1 LTS x86_64，4 核 3.3G | SSH（内网） | 无 | OK |
| debian13 | Debian 13 (trixie) x86_64，4 核 3.8G | SSH（内网） | 无 | OK |
| win10 | Windows 10 22H2 (19045.6466) x86_64 | WinRM/NTLM（内网） | 无 | OK |

说明：三台均未装 Rust，外网均可用；本机（Debian 13，rustc 1.100.0）完成全部编译。virsh 需用 `-c qemu:///system`（session 连接看不到虚拟机）。Windows 未开 SSH（22 关闭、ICMP 被防火墙拦），远程执行走 WinRM 5985，文件分发走宿主机 HTTP 下载。

## 3. 编译产物与动态依赖

Linux 产物 `ldd` 仅依赖 `libc/libm/libgcc_s`；`rusqlite` bundled SQLite 与 `two-face` 的 oniguruma 均静态链接进二进制，`objdump -T` 最高版本需求 `GLIBC_2.39`。因此 Linux 分发不依赖发行版是否带 SQLite/onig，唯一门槛是 glibc 版本（Debian 13=2.41、Ubuntu 26.04=2.43 均满足；Ubuntu 24.04=2.39 恰好是下限；Debian 12 / Ubuntu 22.04 及更旧不可运行）。

Windows 产物为 PE32+ x86-64 console 程序，导入的 DLL 均为 Windows 10 自带（kernel32、ntdll、ws2_32、bcrypt、crypt32、`api-ms-win-core-synch-l1-2-0`、msvcrt 等），无需随包附带运行库。

## 4. Windows 交叉编译的关键补丁（可复现）

宿主工具链：`cross` 0.2.5 + Docker，目标 `x86_64-pc-windows-gnu`。cross 镜像基于 Ubuntu 18.04，内置 mingw-w64 **GCC 7.3（2018）**，与 Rust 1.100 的 windows-gnu std 存在三处不兼容，逐一解决：

1. **rustup 下载走 IPv6 超时**：本机 `static.rust-lang.org` 解析出 IPv6 且 IPv6 不通，rustup 无 fallback。设置 `RUSTUP_DIST_SERVER=https://mirrors.tuna.tsinghua.edu.cn/rustup`（清华镜像，IPv4 可达）。
2. **SQLite bundled 缺 `GetTickCount64`**：mingw-w64 7.3 的头文件在 `_WIN32_WINNT` 过低时不声明该 API。设置 `TARGET_CFLAGS="-D_WIN32_WINNT=0x0601"`，经 `Cross.toml` 的 `build.env.passthrough` 传入容器。
3. **Rust 1.100 std 新依赖旧 mingw 缺失**：Rust 1.100 在 windows-gnu 上引入 `-lsynchronization`（WaitOnAddress/WakeByAddress 系列，来自 `api-ms-win-core-synch-l1-2-0.dll`）并改用 `GetHostNameW`（来自 `ws2_32.dll`），mingw-w64 7.3 的 import 库均未提供。用 `Cross.toml` 的 `pre-build`（docker build 阶段以 root 运行）以 `dlltool` 生成两个 import 库补丁。

关键点：`Cross.toml` 文件名首字母必须大写（cross 0.2.5 忽略小写 `cross.toml` 并告警）；`pre-build` 脚本在 docker **build** 阶段执行，写入 `/target`（docker run 阶段才挂载）不会保留，必须写入镜像内路径（如 `/usr/x86_64-w64-mingw32/lib`）。

```toml
# Cross.toml
[build.env]
passthrough = ["TARGET_CFLAGS", "CFLAGS"]
volumes = ["CONFIG_DIR=<repo>/config"]

[target.x86_64-pc-windows-gnu]
pre-build = "./pre-build.sh"
```

`volumes` 用于把仓库根 `config/` 挂载进容器：core 的 `update_catalog.rs` 用 `include_str!("../../config/update-trust.json")` 引用 core 目录之外的文件，cross 只自动挂载 path 依赖（core）本身，不挂载其引用的仓库根其他目录。

```sh
# pre-build.sh（docker build 阶段以 root 执行）
#!/bin/sh
set -eu
LIBDIR=/usr/x86_64-w64-mingw32/lib

cat > /tmp/sync.def <<'EOF'
LIBRARY api-ms-win-core-synch-l1-2-0.dll
EXPORTS
  WaitOnAddress
  WakeByAddressSingle
  WakeByAddressAll
  WakeByAddress
EOF
x86_64-w64-mingw32-dlltool -d /tmp/sync.def -l "$LIBDIR/libsynchronization.a"

cat > /tmp/hostname.def <<'EOF'
LIBRARY ws2_32.dll
EXPORTS
  GetHostNameW
EOF
x86_64-w64-mingw32-dlltool -d /tmp/hostname.def -l "$LIBDIR/libws2_32_extra.a"
```

并在构建命令中追加 `CARGO_TARGET_X86_64_PC_WINDOWS_GNU_RUSTFLAGS="-l ws2_32_extra"`。生成的 import 库仅提供动态导入桩，符号仍指向 Windows 系统 DLL，运行时无需携带任何额外文件。dlltool 会产生无害的 `corrupt .drectve` 链接告警。

## 5. 三平台冒烟结果

| 检查 | debian13 | ubuntu2604 | win10 |
|---|---|---|---|
| state（本机空间） | 通过 | 通过 | 通过 |
| new（创建笔记） | 通过 | 通过 | 通过 |
| list（列表 + 中文标题） | 通过 | 通过 | 通过 |
| get（读取正文） | 通过 | 通过 | 通过 |
| export（ZIP 导出） | 通过 | 通过 | 通过 |

中文界面文案（“本机笔记”）与笔记正文在 Windows 控制台经 WinRM 捕获均正常，未出现乱码。

## 6. 遗留与建议

- **Linux musl 静态编译**：未验证。若需覆盖 Debian 12 / Ubuntu 22.04 等 glibc<2.39 的环境，改用 `x86_64-unknown-linux-musl`（cross 的 musl 镜像）可消除 glibc 门槛，但需评估 bundled SQLite/onig 的 musl 交叉编译。
- **Windows gnullvm**：`x86_64-pc-windows-gnullvm`（LLVM lld + 更新的 mingw-w64）可能免去第 4 节补丁 3，但 cross 0.2.5 无对应镜像，未验证。
- **免 cross 的替代**：`cargo-zigbuild`（zig 单二进制，自带新 mingw-w64）理论上可一并解决补丁 2、3，本机未装 zig，未验证。
- **跨平台分发通道**：WinRM 直接传 base64 受 CreateProcess 命令行长度限制（约 32KB），7.7MB 产物需约 500 次往返；改为宿主机 HTTP 下载一次即可。真实发布走 GitHub Release 附件，不受此限制。
- **`$EDITOR` 交互**：冒烟未覆盖（远程无交互终端），Linux 侧 `edit` 子命令已实现但未在虚拟机跑；Windows 侧原型 `edit` 用 `sh -c`，正式实现需按平台分派 `cmd /c` 或直接 `Command::new($EDITOR)`。
