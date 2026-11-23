# CLI 端（调研草案）

- 状态：draft。仅记录 2026-11-17 的调研结论与子命令设计；**尚未实现**，不属于 v1 三端交付范围，也不构成任何已通过能力。跨平台编译验证见[跨平台编译调研](cli-cross-platform-survey.md)。
- 定位：CLI 是「第四个薄壳」，与桌面 Tauri Rust 后端同构，直接链接 `djhx-memo-core` 复用全部业务逻辑（本地存储、同步状态机、outbox、SSE、搜索、回收站、导出、鉴权），不重写、不在 UI 层实现同步或冲突规则。

## 1. 设计原则

子命令设计遵循下列公开指南，取其交集；本项目另有约束时以本项目 spec 为准并在文中说明：

- [Command Line Interface Guidelines (clig.dev)](https://clig.dev/)：人类优先、stdout 是数据 / stderr 是消息、exit code 语义、help 与示例、flag 优先于位置参数、危险操作确认、`--json` / `--plain` / `--quiet`、`NO_COLOR`、不读 secret 进 flag/env、不做 catch-all 子命令、不加任意缩写。
- [12 Factor CLI Apps (jdx)](https://jdx.dev/posts/2018-10-08-12-factor-cli-apps/)：同一命令不混用多类位置参数、全拼 flag 可读、秘密经 stdin/文件而非 flag/env。
- [命令行里的设计艺术 (joway)](https://blog.joway.io/posts/the-art-of-cli-design/)：遵循约定俗成（如 `-p` 是 port 不是 password）、命令结构一致（`cmd [verb] [args] [flags]`）、面向用户而非面向实现、繁琐胜过歧义、不可逆操作先讲安全。

落到本项目的取舍：

1. **动词一致、单一层级为主**：`memo <verb> [args] [flags]`；仅设备管理用「名词+动词」两级（`memo devices revoke <id>`，与 `docker container rm` 同类）。不使用 Git 式「一个动词一个语义」的混乱（`checkout -b` / `branch -D`）。
2. **面向用户命名，不暴露 core 内部名**：core 的 `create/set_deleted/purge/batch_set_deleted` 映射为用户动词 `new/delete/restore/purge`；`get` 映射为 `show`；`state` 映射为 `status`。
3. **flag 优先、全拼为主**：可读性优先，单字母 flag 只给最高频项（`-q` 等），不用大小写区分语义（拒绝 `-p`=port / `-P`=password 一类歧义）。
4. **秘密一律不走 flag/env**：登录密码只经交互式不回显提示或 stdin，不提供 `--password`。
5. **默认行为对多数用户正确，脚本能力靠显式 flag**：人类输出默认表格；`--json` 给机器；`--quiet` 给脚本。

## 2. 程序名与通用约定

- 程序名：主名 `djhx-memo`（品牌一致、避免与既有命令冲突），同时提供 `memo` 别名，二者都可用。二者指向同一二进制，由打包/安装器创建别名：Unix 用 symlink（`memo → djhx-memo`），Windows 由安装器生成 `memo.exe` 副本或 `memo.cmd`。clap 以 argv[0] 识别调用名，help、补全、man 随调用名自适应，无需运行时判断。不得提供 catch-all 子命令或任意前缀缩写。
- 全局选项（所有子命令可用，含 `memo` 本身）：

| 选项 | 含义 |
|---|---|
| `--root <PATH>` | 数据目录，覆盖平台默认；主要用于测试与便携运行，正常用户不设 |
| `--json` | 机器可读 JSON 输出（见第 5 节） |
| `-q, --quiet` | 抑制非必要的人类输出（脚本用） |
| `-v, --verbose` | 详细/诊断输出（默认关闭） |
| `--no-color` | 禁用颜色；此外遵循 `NO_COLOR` 环境变量与「非 TTY 自动无色」 |
| `-V, --version` | 版本 |
| `-h, --help` | 帮助 |

- exit code：`0` 成功；`1` 操作失败（网络、存储、冲突、鉴权等，具体原因经 stderr 输出 core 稳定错误码与文案）；`2` 用法错误（参数解析失败，clap 默认）。不把具体错误码逐个映射到自定义数字。
- 流：命令的业务数据写 **stdout**（可被管道消费）；提示、进度、错误、警告写 **stderr**。
- 帮助：`memo`、`memo --help`、`memo help`、`memo <verb> --help` 均显示帮助；`memo <verb>` 缺少必需参数时显示该子命令的简洁用法 + 一条示例，并提示 `--help`。帮助文本以示例开头，常用命令排在前面。
- 无网络时所有本地命令仍可用；网络命令（`sync/login/register/logout/devices/import`）在断网时输出明确错误并保持本地内容不变。

## 3. 子命令总览

| 子命令 | 用途 | core action |
|---|---|---|
| `status` | 当前空间、同步状态、待同步数、最近同步时间 | `state` |
| `list` | 列出笔记（`--deleted` 看回收站） | `list` |
| `search <query>` | 字面搜索（id + 标题 + 摘要） | `list`（带 query） |
| `new [content]` | 新建笔记；无 content 时读 stdin 或 `$EDITOR` | `create` |
| `show <id>` | 输出笔记正文到 stdout | `get` |
| `edit <id>` | 用 `$EDITOR` 编辑后保存 | `get` + `save` |
| `delete <id>...` | 移入回收站（可恢复，支持多 id） | `batch_set_deleted` |
| `restore <id>...` | 从回收站恢复（支持多 id） | `batch_set_deleted` |
| `purge <id>` | 彻底删除（不可恢复，需确认） | `purge` |
| `export [--deleted]` | 导出整空间为 ZIP | `export` |
| `sync` | 手动同步并等待本轮完成 | `sync`（wait=true） |
| `login` / `register` / `logout` | 账号登录 / 邀请码注册 / 注销 | 同名 |
| `devices` / `devices revoke <id>` | 设备列表 / 吊销设备 | `devices` / `revoke_device` |
| `import [--from <profile>]` | 本机/恢复空间复制到当前账号 | `import` |
| `completions <shell>` | 输出 shell 补全脚本 | —（clap_complete 生成） |

## 4. 子命令详细规范

### 4.1 status

```text
memo status
```

- 输出到 stdout：当前空间（kind/label/server）、云状态（local/pending/syncing/synced/offline/auth_required/recovery_required/error）、待同步操作数、最近成功同步时间。
- 参考 `git status` 的「suggest next」：auth_required 时提示 `memo login`；pending 时提示 `memo sync`；有本机笔记可导入时提示 `memo import`。
- `--json` 输出 core `state` 的原始结构。

### 4.2 list / search

```text
memo list [--deleted] [--json]
memo search <query> [--json]
```

- `list` 默认列出未删除笔记，按有效更新时间倒序（core 排序约定），每行 `id\t标题\t更新时间`；`--deleted` 列出回收站。
- `search` 等价于 `list` 的字面搜索，输出 `id\t标题\t更新时间\t首段摘要`；query 为 1～2 个中文字符/英文/混合的本地子串匹配，语义同[产品规范](../../spec/product-v1.md)第 7 节，不做 FTS 布尔表达式。
- 两者 `--json` 输出 core `list` 的 NoteView 数组，字段见 [core-bridge](../../spec/core-bridge.md)。

### 4.3 new

```text
memo new [content]
echo "# 标题" | memo new
memo new --edit
```

- 有 `content`：直接创建。
- 无 `content` 且 stdin 非 TTY：读 stdin 全文作为正文。
- 无 `content` 且 stdin 是 TTY：按 `--edit` 处理，打开 `$EDITOR`（`$VISUAL` > `$EDITOR` > 平台默认）。
- 创建成功输出新建笔记的 `id`（一行，可被脚本捕获）；标题由 core 从首个非空行派生。
- 正文上限、行尾归一化（LF）、首份非空白内容才持久化等规则由 core 保证，CLI 不重复实现。

### 4.4 show

```text
memo show <id>
```

- 输出正文到 stdout（可重定向/管道）；不输出任何装饰。
- 笔记不存在或已彻底删除时，错误写 stderr，exit 1。

### 4.5 edit

```text
memo edit <id>
```

- 拉取当前正文写临时文件 → 打开 `$EDITOR` → 编辑器退出后读回 → 以 core 返回的 `expected_generation` 提交 `save`。
- 编辑器退出码非 0 或内容未变时不提交，退出码 1 并说明「未保存」。
- 发生 `LOCAL_GENERATION_CONFLICT`（编辑期间被其他端同步更新）时不覆盖，保留用户编辑内容到救援文件并提示，绝不静默丢弃。
- 平台分派：Unix 用 `sh -c`，Windows 用 `cmd /c`（实现时定，不写死在原型里）。

### 4.6 delete / restore / purge

```text
memo delete <id> [<id>...]
memo restore <id> [<id>...]
memo purge <id> [--force]
```

- `delete`/`restore` 复用 core `batch_set_deleted`，逐条按原 id、原 generation 提交，输出每条结果（成功/失败与错误码），失败不中断其余条目，退出码汇总（有失败则 1）。
- `purge` 为不可逆操作（清空当前正文并保留永久删除标记，见 [permanent-deletion](../../spec/permanent-deletion.md)）：交互 TTY 下要求确认；`--force` 跳过确认供脚本；确认文案不含正文。删除/恢复/彻底删除均不删历史、不做自动清理，语义以 core 为准。

### 4.7 export

```text
memo export [--deleted] [--output <dir>]
```

- 默认导出未删除笔记为 `djhx-memo-<时间戳>.zip` 到当前目录；`--deleted` 仅导出回收站；`--output` 指定目录。
- 文件由 core 生成 ZIP 字节，CLI 负责目标选择与原子落盘（沿用桌面端 `publish_new_file` 的「已存在文件不覆盖、临时文件+hard_link 原子发布」语义，见 [core-api](../../spec/core-api.md) 与桌面实现）。
- 导出前 core 先 flush 待保存内容；导出含已提交的待同步内容。

### 4.8 sync

```text
memo sync [--json]
```

- 触发手动同步并等待本轮完成（core `sync` 的 `wait=true`），结束后输出 SyncInfo（state/pending/last_success/error）。
- 网络失败不使本地保存失效；auth_required/recovery_required 等状态经 stderr 提示下一步。

### 4.9 login / register / logout

```text
memo login [--server <url>]
memo register [--server <url>]
memo logout
```

- `login`/`register`：服务器地址（默认 `https://memo.djhx.site`）、用户名、密码、设备名交互输入；密码用不回显提示或 stdin，**不提供 `--password`**，也不从环境变量读。`register` 另需邀请码。
- 登录成功后切换到该账号空间，输出「可导入 N 条本机笔记」与 `memo import` 提示（对应 core 返回的 `import_count`）。
- `logout` 清除本地凭据、尽力远程吊销、回到本机空间；离线注销须明确说明远程吊销未完成（语义同 [auth](../../spec/auth.md)）。

### 4.10 devices

```text
memo devices [--json]
memo devices revoke <id>
```

- `devices` 列出设备（id、名称、最近活动），`--json` 输出 OpenAPI Device 数组。
- `devices revoke <id>` 吊销指定设备；吊销当前设备等同注销本地凭据（语义同 core `revoke_device`）。

### 4.11 import

```text
memo import [--from local] [--json]
```

- 把本机空间（或恢复空间，同账号同服务器）的未删除笔记复制到当前账号空间，生成新 id，幂等（同源同代次重复导入不重复）。
- 输出导入条数；`--json` 输出 `{imported}`。仅复制未删除笔记，来源保留，语义同 [product-v1](../../spec/product-v1.md) 第 3 节与 core `import`。

### 4.12 completions

```text
memo completions <shell>
```

- `shell` ∈ `bash | elvish | fish | powershell | zsh`；输出对应 shell 的补全脚本到 stdout，供用户重定向安装（如 `memo completions zsh > _memo`）。
- 由 clap_complete 从命令定义生成，子命令、flag、参数值补全与帮助一致，不手写补全脚本。
- 以 argv[0]（实际调用名）生成，`memo` 与 `djhx-memo` 两种调用名都产出正确脚本；用户按常用名安装一次即可。

## 5. 输出与 --json

- 人类输出优先、简洁；表格/列表使用制表符或对齐列，不嵌 ANSI 控制字符进被管道消费的 stdout（stdout 非 TTY 时自动关色）。
- `--json` 输出稳定结构（core 桥接的 JSON 原样或等价子集），供脚本与 `jq` 消费；`--json` 与人类格式互斥，二者都命中时以 `--json` 为准。
- `--quiet` 抑制「成功/进度/提示」类 stderr 消息，错误仍输出。
- 变化状态必告知：`new/edit/delete/restore/purge/import/sync/logout` 成功时向 stderr 输出一句话结果（如 `已移入回收站，可用 memo restore <id> 恢复`），并给出下一步建议。

## 6. 与 core 的映射

CLI 只做参数解析、`$EDITOR` 集成、凭据存储与输出格式化；generation/rev/seq 全部沿用 core 十进制字符串，`expected_generation` 冲突、冻结 outbox、删除/彻底删除屏障、同步状态机一律不重实现。凭据经 `CredentialStore` 存系统 keyring（服务名 `app.djhx.memo`，与桌面端共享）；headless 下 keyring 不可用时按 `save=false` 降级为进程内存并明确提示，不落明文 token。

## 7. 平台边界（来自跨平台调研）

- Linux：glibc≥2.39（当前 Debian 13=2.41、Ubuntu 26.04=2.43 通过）；需覆盖更旧发行版时改用 musl 静态编译，另行验证。
- Windows：`cross` 交叉编译需固化 `Cross.toml` + `pre-build.sh` 补丁（mingw-w64 7.3 缺 Rust 1.100 的 `synchronization`/`GetHostNameW` import 库），详见[调研文档](cli-cross-platform-survey.md)。`edit`/`new --edit` 的编辑器拉起需按平台分派。
- 不新增 CI/Runner；跨平台检查在本机交叉编译 + 目标虚拟机冒烟，记录结果，不宣称未运行的检查通过。

## 8. 文档：man page 与 shell 补全

### 8.1 man page

- 用 [clap_mangen](https://docs.rs/clap_mangen)（当前 0.3.4）在 build.rs 编译期从 clap Command 生成 roff 手册 `djhx-memo.1`，与 `--help` 同源，不手写 roff。版本经 `.TH` 嵌入。
- 分发：Linux 打包时装入 `/usr/share/man/man1/djhx-memo.1`（`mandb` 可索引）；Release 附件提供 `.1` 文件；别名页 `memo.1` 用 roff 的 `.so djhx-memo.1` 引用或 symlink，避免双份维护。
- Windows 无 man 系统，终端文档以 `--help` 与 web 文档为主，man page 不额外适配。

### 8.2 shell 补全

- 用 [clap_complete](https://docs.rs/clap_complete)（当前 4.6.12）生成 bash / elvish / fish / powershell / zsh 补全。
- 双通道：① 运行时子命令 `completions <shell>`（见 4.12，主入口，用户自装）；② build.rs 用 `generate_to` 在编译期生成静态补全文件，随包分发——Linux 装入 `/usr/share/bash-completion/completions/`、`/usr/share/zsh/site-functions/`、`/usr/share/fish/vendor_completions.d/` 等平台目录，Release 附件一并提供。
- 补全与 man page 都从同一份 clap 定义生成，命令/flag 增删后自动同步，不手工维护。

## 9. 尚未决定 / 暂缓

- 是否纳入 v1 三端验收、是否提供安装包（还是仅 `cargo install` / Release 二进制），以及别名在 Windows 安装器中的具体落地方式（`memo.exe` 副本还是 `memo.cmd`）。
- `$EDITOR` 在 Windows 下的默认值与 `EDITOR`/`VISUAL` 优先级。
- musl 静态编译与 gnullvm 路线是否作为正式分发方案。
