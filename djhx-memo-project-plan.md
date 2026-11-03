# djhx-memo 项目规划文档

> 版本：v0.1（草案）
> 状态：待确认，确认后拆分为 `spec/` 下的正式文档
> 适用对象：项目负责人、所有参与开发的 AI 与人类协作者

---

## 目录

1. [项目概述](#1-项目概述)
2. [设计原则](#2-设计原则)
3. [平台与技术栈](#3-平台与技术栈)
4. [总体架构](#4-总体架构)
5. [仓库结构（Monorepo）](#5-仓库结构monorepo)
6. [同步设计](#6-同步设计)
7. [数据模型](#7-数据模型)
8. [编辑器与客户端功能](#8-编辑器与客户端功能)
9. [服务端与账号安全](#9-服务端与账号安全)
10. [质量保障与多 AI 协作规范](#10-质量保障与多-ai-协作规范)
11. [文档规范](#11-文档规范)
12. [里程碑与路线图](#12-里程碑与路线图)
13. [待决策事项](#13-待决策事项)
14. [附录](#14-附录)

---

## 1. 项目概述

### 1.1 一句话定义

**djhx-memo** 是一个跨平台、多端云同步的纯文本（Markdown）备忘录：随时随地打开即写，联网后自动同步，体验目标是接近 Telegram 的"打开就是最新"。

### 1.2 背景与动机

- 手机自带备忘录体验好、支持 Markdown，但跨平台同步依赖第三方，很麻烦。
- 现实中常用 IM（微信、Telegram）互相转发来"同步"笔记，体验差、内容易丢、难检索。
- 目标是拥有一个自己可控、数据存放在自己服务器上的同步备忘录。

### 1.3 目标用户

仅供项目负责人本人和少数朋友使用。因此：

- 只需要简单账号体系实现数据隔离，不做开放注册、不做多租户计费。
- 不追求大规模并发，但**数据可靠性是底线**。

### 1.4 范围

**包含（In scope）**

- 纯文本笔记的创建、编辑、删除、搜索
- Markdown 源码编辑（语法高亮）与预览切换
- 多端离线可用（本地优先），联网后自动同步到云端
- 多设备、多账号（账号间数据隔离）
- 数据导出为 `.md` 文件

**不包含（Non-goals，v1）**

- 富文本、所见即所得编辑
- 图片、附件、音视频
- 多人实时协同编辑、笔记共享
- 双链、知识图谱、数据库视图等 PKM 功能
- Apple 平台（iOS / macOS）：暂不实现，原因是暂无苹果设备可供开发测试（见 [3.3](#33-apple-平台暂缓说明)）

---

## 2. 设计原则

1. **同步优先**：功能极少，同步的正确性就是产品本身。
2. **本地优先（Local-first）**：所有读写先落本地 SQLite，断网可用，打开秒开。
3. **永不丢数据**：冲突时保留败者副本；删除用软删除；schema 变更走迁移并有测试；始终可导出。
4. **契约先行**：`spec/` 是唯一事实来源，任何实现与 spec 不一致，以 spec 为准（或先修改 spec）。
5. **逻辑只写一份**：同步引擎、冲突处理、本地存储只在 Rust 核心中实现一次，各端 UI 层保持薄。
6. **原生与性能优先**：在不显著增加维护面的前提下，优先使用原生技术栈。
7. **机器当裁判**：质量靠 CI 与自动化测试强制保证，不依赖"让 AI 自觉写好"。
8. **无历史包袱**：不为兼容旧方案做妥协，但数据格式一旦发布即需通过迁移演进。

---

## 3. 平台与技术栈

### 3.1 技术选型总表

| 组件 | 目录 | 技术栈 | 说明 |
|---|---|---|---|
| 协议与契约 | `spec/` | Markdown + OpenAPI + JSON Schema | 唯一事实来源，含同步场景测试用例 |
| 核心库 | `core/` | **Rust** + SQLite（rusqlite） | 本地存储、同步引擎、冲突处理；UniFFI 绑定 |
| 服务端 | `server/` | **Go** + SQLite（WAL 模式） | 认证、业务 API、同步、实时通知 |
| Android | `android/` | **Kotlin + Jetpack Compose** | 通过 UniFFI 调用 `core` |
| 桌面端（Windows / Linux） | `desktop/` | **Tauri**（Rust 后端 + Web 前端） | 后端直接链接 `core`；macOS 可顺带构建，非目标 |
| Web 端 | `web/` | **TypeScript + Svelte**（可替换为 AI 更熟悉的框架） | 与桌面端共享前端组件 |
| 编辑器组件 | `web/` 共享 | **CodeMirror 6** | 用于桌面端与 Web 端，不自行从零实现 |
| 本地搜索 | `core/` | SQLite **FTS5**，trigram 分词 | 解决中文分词问题 |
| 备份 | 服务器 | **Litestream** | 服务端 SQLite 持续备份 |

### 3.2 选型理由摘要

- **核心库选 Rust 而非 C++**：内存安全由编译器保证，对多 AI 协作尤为重要；UniFFI 可直接生成 Kotlin 绑定，避免手写 JNI；Cargo 工具链统一，交叉编译成熟。同步场景瓶颈在网络与磁盘，语言性能差异可忽略。
- **服务端选 Go**：单一二进制，部署到 Debian 12 仅需拷贝文件与 systemd；标准库完备；SQLite WAL 模式足以支撑个人与小规模使用。
- **桌面端选 Tauri**：Windows 与 Linux 共用一套代码，后端为 Rust 可直接依赖 `core`；前端与 Web 端共享组件，三个端只需维护一套 Web UI。代价是 UI 层为 WebView 而非原生控件，对纯文本笔记场景影响很小。
- **Android 原生**：使用 Kotlin + Compose，保证输入法、系统集成与性能。

### 3.3 Apple 平台暂缓说明

- iOS / macOS **v1 不实现**，不创建 `apple/` 目录的实际代码。
- 架构上保持可扩展：`core` 通过 UniFFI 同时支持 Swift 绑定，日后加入 SwiftUI 客户端无需改动核心。
- `spec/` 与 `core/` 的设计不得引入任何阻碍未来 Apple 支持的假设（例如不依赖 Windows/Linux 专有路径或 API）。
- 在 CI 中暂不包含 Apple 构建任务；需要时再补充。

### 3.4 Web 端的离线策略

Web 端没有原生 SQLite，有两种做法：

| 方案 | 做法 | 评价 |
|---|---|---|
| A | Rust 核心编译为 WASM，SQLite WASM + OPFS 持久化 | 行为与其他端完全一致；配置复杂度较高 |
| B | Web 端只做在线客户端，直接调用服务端 API | 实现最简单；断网不可用 |

**建议**：v1 采用方案 B（Web 定位为"借用他人电脑临时使用"），待核心稳定后再评估方案 A。此项列入[待决策事项](#13-待决策事项)。

---

## 4. 总体架构

```
                         ┌──────────────────────────┐
                         │        server (Go)       │
                         │  Auth │ Sync API │ Events│
                         │      SQLite (WAL)        │
                         └────────────┬─────────────┘
                                      │ HTTPS / WebSocket
        ┌─────────────────────────────┼─────────────────────────────┐
        │                             │                             │
┌───────┴────────┐          ┌─────────┴─────────┐          ┌────────┴────────┐
│ Android (Kotlin│          │ Desktop (Tauri)   │          │ Web (TS)        │
│ + Compose)     │          │ Windows / Linux   │          │ v1: 在线客户端   │
│  UI 薄壳        │          │  UI: Web 前端      │          │  共享前端组件     │
└───────┬────────┘          └─────────┬─────────┘          └────────┬────────┘
        │ UniFFI                      │ 直接链接                      │ （未来 WASM）
        └──────────────┬──────────────┘                             │
                ┌──────┴───────┐                                    │
                │  core (Rust) │◄───────────────────────────────────┘
                │ 本地 SQLite   │
                │ 同步引擎      │
                │ 冲突处理      │
                └──────────────┘
```

**分层职责**

- **UI 层**（各端）：展示、输入、平台集成。**不包含任何同步或冲突逻辑。**
- **core**：业务规则的唯一实现，对外暴露简洁的接口（增删改查笔记、触发同步、订阅变更）。
- **server**：账号、鉴权、权威副本、变更序号分配、实时通知。

---

## 5. 仓库结构（Monorepo）

初期采用**单一仓库**，原因：AI 能在同一工作区看到契约、核心与各端代码；跨模块改动可一次提交、一次 CI 验证；无需版本发布依赖；规范只维护一份。

### 5.1 目录结构

```
djhx-memo/
├── README.md                  # 项目简介、快速开始
├── AGENTS.md                  # 全局协作规范（所有 AI 必读）
├── CONTRIBUTING.md            # 提交、分支、评审流程
├── justfile                   # 统一命令入口
├── .editorconfig
├── .gitignore
├── .github/
│   └── workflows/             # 按目录触发的 CI
├── docs/
│   ├── architecture.md        # 架构说明
│   ├── adr/                   # 架构决策记录（ADR）
│   └── dev-setup.md           # 环境搭建
├── spec/
│   ├── README.md
│   ├── sync-protocol.md       # 同步协议规范
│   ├── data-model.md          # 数据模型规范
│   ├── error-codes.md         # 错误码
│   ├── openapi.yaml           # API 定义
│   └── conformance/           # 与语言无关的同步场景测试用例
├── core/                      # Rust 核心
│   ├── AGENTS.md
│   ├── Cargo.toml
│   └── src/
├── server/                    # Go 服务端
│   ├── AGENTS.md
│   ├── go.mod
│   └── ...
├── android/                   # Kotlin + Compose
│   ├── AGENTS.md
│   └── ...
├── desktop/                   # Tauri（Windows + Linux）
│   ├── AGENTS.md
│   └── ...
├── web/                       # TypeScript 前端（与 desktop 共享组件）
│   ├── AGENTS.md
│   └── ...
└── apple/                     # 预留，v1 不实现（仅保留 README 说明）
```

> 若桌面端与 Web 端共享前端组件，可在 `web/` 下拆出 `packages/ui`、`packages/editor` 等共享包，由 `desktop/` 引用。具体布局在 M5 阶段确定。

### 5.2 统一命令入口

根目录 `justfile` 提供统一命令，AI 不必记忆各端细节：

```
just setup          # 安装依赖与工具
just fmt            # 全仓格式化
just lint           # 全仓静态检查
just test-core      # 核心库测试
just test-server    # 服务端测试
just test-conformance  # 一致性测试套件
just test           # 全部测试
just ci             # 本地模拟 CI 全流程
```

### 5.3 命名规范

| 对象 | 规范 | 示例 |
|---|---|---|
| 仓库 | 小写连字符 | `djhx-memo` |
| 目录 | 小写，单词用连字符 | `desktop`、`sync-protocol` |
| Rust crate | `djhx-memo-` 前缀，下划线风格由 Cargo 决定 | `djhx-memo-core` |
| Go module | `github.com/<owner>/djhx-memo/server` | — |
| Android 包名 | `app.djhx.memo` | 可按实际域名调整 |
| 分支 | `<类型>/<模块>-<简述>` | `feat/core-pull-cursor` |
| 提交信息 | Conventional Commits | `feat(core): implement tombstone apply` |

### 5.4 何时拆分仓库

仅当出现以下情况之一时再拆：某个端要开源或交给外部维护；发布节奏与其他端完全不同；仓库规模导致工具明显变慢。届时沿目录边界用 `git filter-repo` 或 `git subtree split` 拆分，保留历史。

---

## 6. 同步设计

> 本章为设计要点，**正式协议以 `spec/sync-protocol.md` 为准**，由 M0 阶段细化。

### 6.1 总体模型

- 每个客户端持有本地 SQLite，所有操作先写本地。
- 服务端是权威副本，为每次变更分配**单调递增的序号**（`seq`）。
- 客户端记录"已同步到的 `seq`"（游标），每次仅拉取增量。
- **不使用客户端时间判断新旧**，设备时钟不可信；时间戳仅用于展示。

### 6.2 关键约定

| 主题 | 约定 |
|---|---|
| ID | 笔记 ID 使用 **ULID/UUID**，由客户端生成，禁止自增 ID |
| 删除 | **软删除**（墓碑，`deleted = true`），不得物理删除后再同步 |
| 变更序号 | 由服务端分配，全局（按账号）单调递增 |
| 版本校验 | 客户端推送时携带 `base_rev`（其编辑所基于的版本）；服务端据此判断是否冲突 |
| 冲突策略 | 整条笔记"最后到达服务端者胜"，**败者内容保存为冲突副本**（独立笔记，标题标注冲突与来源设备），绝不静默丢弃 |
| 不使用 CRDT | 个人场景下过度设计，不引入 |
| 幂等 | 推送请求携带客户端生成的操作 ID，服务端据此去重，保证重试安全 |
| 实时通知 | WebSocket（或 SSE）**只推送"有更新"信号**，客户端收到后走增量拉取 |

### 6.3 同步流程（草案）

```
推送（Push）
  客户端 → 服务端：本地待同步变更列表（含 note_id、base_rev、内容、deleted、op_id）
  服务端：逐条校验
        - base_rev 与服务端当前版本一致 → 接受，分配新 rev 与 seq
        - 不一致 → 接受服务端版本为准，将客户端内容保存为冲突副本（新笔记）
        - op_id 已处理 → 幂等返回
  服务端 → 客户端：每条结果（accepted / conflict + 副本 ID）

拉取（Pull）
  客户端 → 服务端：cursor（上次同步到的 seq）
  服务端 → 客户端：seq > cursor 的变更列表 + 新 cursor + has_more
  客户端：在单个本地事务中应用变更并推进 cursor
```

### 6.4 接口草案

| 接口 | 说明 |
|---|---|
| `POST /v1/auth/register` | 注册（受邀请码或管理员开关控制） |
| `POST /v1/auth/login` | 登录，为设备签发 token |
| `POST /v1/auth/logout` | 注销当前设备 |
| `GET  /v1/devices` | 列出设备 |
| `DELETE /v1/devices/{id}` | 吊销设备 token |
| `POST /v1/sync/push` | 推送本地变更 |
| `GET  /v1/sync/pull?cursor=` | 拉取增量 |
| `GET  /v1/events`（WebSocket/SSE） | 更新通知 |
| `GET  /v1/export` | 导出全部笔记 |

### 6.5 边界场景（必须有一致性测试覆盖）

1. 设备 A 离线编辑，设备 B 在线编辑同一条，A 联网后合并。
2. 设备 A 删除，设备 B 同时编辑同一条。
3. 离线新建大量笔记后一次性同步。
4. 推送请求中途断网后重试（幂等）。
5. 拉取分页（`has_more`）中途中断后恢复。
6. 新设备首次全量同步。
7. 设备被吊销 token 后的行为。
8. 同一笔记被连续多次编辑后的合并与序号推进。

---

## 7. 数据模型

> 正式定义以 `spec/data-model.md` 为准，以下为草案。

### 7.1 服务端

```sql
CREATE TABLE users (
  id            TEXT PRIMARY KEY,         -- ULID
  username      TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,            -- argon2id
  created_at    INTEGER NOT NULL
);

CREATE TABLE devices (
  id          TEXT PRIMARY KEY,           -- ULID
  user_id     TEXT NOT NULL REFERENCES users(id),
  name        TEXT NOT NULL,
  token_hash  TEXT NOT NULL,              -- 仅存哈希
  created_at  INTEGER NOT NULL,
  last_seen_at INTEGER,
  revoked_at  INTEGER
);

CREATE TABLE notes (
  id          TEXT PRIMARY KEY,           -- ULID，客户端生成
  user_id     TEXT NOT NULL REFERENCES users(id),
  content     TEXT NOT NULL,              -- Markdown 纯文本
  rev         INTEGER NOT NULL,           -- 该笔记的版本号
  seq         INTEGER NOT NULL,           -- 变更序号（按用户单调递增）
  deleted     INTEGER NOT NULL DEFAULT 0, -- 墓碑
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);
CREATE INDEX idx_notes_user_seq ON notes(user_id, seq);

CREATE TABLE processed_ops (            -- 幂等去重
  user_id TEXT NOT NULL,
  op_id   TEXT NOT NULL,
  result  TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, op_id)
);
```

### 7.2 客户端（core）

```sql
CREATE TABLE notes (
  id          TEXT PRIMARY KEY,
  content     TEXT NOT NULL,
  rev         INTEGER NOT NULL DEFAULT 0,   -- 最近一次已知的服务端版本
  deleted     INTEGER NOT NULL DEFAULT 0,
  dirty       INTEGER NOT NULL DEFAULT 0,   -- 有未推送的本地修改
  created_at  INTEGER NOT NULL,
  updated_at  INTEGER NOT NULL
);

CREATE TABLE sync_state (
  key   TEXT PRIMARY KEY,                   -- 例如 'cursor'
  value TEXT NOT NULL
);

CREATE VIRTUAL TABLE notes_fts USING fts5(
  content, content='notes', content_rowid='rowid', tokenize='trigram'
);
```

### 7.3 预留与演进

- 附件表、标签表、加密字段均**暂不创建**，但数据模型与迁移机制不得阻碍日后新增。
- 所有 schema 变更**必须**通过带版本号的迁移脚本，并附迁移测试（含从旧版本升级的用例）。

---

## 8. 编辑器与客户端功能

### 8.1 v1 功能清单

| 功能 | 说明 |
|---|---|
| 笔记列表 | 按更新时间倒序，显示首行为标题、摘要 |
| 新建 / 编辑 / 删除 | 打开应用即可新建；删除为软删除，可考虑"回收站"（v1 可选） |
| Markdown 源码编辑 | 语法高亮；不做所见即所得 |
| 预览切换 | 一键切换渲染预览 |
| 勾选框交互 | `- [ ]` / `- [x]` 点击即可切换，是把备忘录变成待办的关键交互，需单独打磨 |
| 搜索 | SQLite FTS5 + trigram，支持中文 |
| 同步状态 | 显示同步中 / 已同步 / 离线 / 失败，失败可重试 |
| 冲突副本 | 冲突副本以明显标识显示，便于用户合并 |
| 导出 | 一键导出为 `.md` 文件集合（本地与服务端均可） |
| 账号与设备管理 | 登录、查看设备、吊销设备 |

### 8.2 各端注意事项

- **Android**：输入法兼容（中文）、后台同步（WorkManager）、系统分享入口（接收其他应用分享的文本直接存为笔记）。
- **桌面端**：全局快捷键唤起、托盘常驻；Linux 需实际测试 fcitx / ibus 输入法与 Wayland 下的渲染。
- **Web**：编辑器使用 CodeMirror 6，移动端浏览器输入法需实测。

### 8.3 体验指标（建议）

- 冷启动到可输入：Android < 1 秒，桌面 < 1 秒（设备正常条件下）。
- 本地编辑到落盘：用户无感知，使用防抖保存，且在应用退到后台、关闭前强制落盘。
- 联网状态下，一端保存后另一端 5 秒内可见（以实时通知触发拉取）。

---

## 9. 服务端与账号安全

### 9.1 部署

- 环境：Debian 12 自有服务器。
- 形式：Go 编译为单一二进制，`systemd` 托管；反向代理（Caddy 或 Nginx）提供 HTTPS 与 WebSocket 转发。
- 数据库：SQLite，启用 WAL 模式；使用 Litestream 持续备份到异地存储。
- 配置：通过环境变量或配置文件，敏感信息不入库、不入仓库。

### 9.2 认证与授权

- 密码使用 **argon2id** 哈希。
- 每台设备独立 token，仅存储 token 哈希，可单独吊销。
- 所有数据接口按 `user_id` 隔离，**服务端必须在每个查询中强制带用户条件**，并有测试覆盖越权访问。
- 注册默认关闭或需邀请码，避免被陌生人使用。
- 全程 HTTPS；登录接口做限流与失败次数限制。

### 9.3 端到端加密（决策项）

- v1 **不启用** E2EE（服务器为自有、用户为熟人），但数据模型预留可加密字段的可能。
- 如日后启用，将影响服务端搜索（无法检索密文）与数据模型，需单独出 ADR 评估。

### 9.4 运维

- 日志结构化输出，包含请求 ID，不记录笔记正文与密码、token。
- 提供健康检查接口。
- 备份恢复流程需**定期演练**并记录在 `docs/`。

---

## 10. 质量保障与多 AI 协作规范

> 项目将由 3–6 个不同的 AI 参与完成。核心原则：**不靠"让 AI 写得好"，而靠"让写得不好的代码进不来"。**

### 10.1 契约先行

- `spec/` 是唯一事实来源，包含协议、数据模型、错误码、OpenAPI、一致性测试用例。
- 任何影响接口或行为的改动，**必须先改 spec，再改实现**，且在同一个 PR 中提交。
- 每个实现需在 PR 描述中引用对应的 spec 条目。

### 10.2 一致性测试套件（最重要）

- `spec/conformance/` 存放与语言无关的同步场景（以数据文件描述输入、操作序列、期望结果）。
- `server` 与 `core` 都必须通过同一套用例；新增同步行为必须先补用例。
- 第 [6.5](#65-边界场景必须有一致性测试覆盖) 节所列场景为首批必备用例。

### 10.3 CI 强制门禁

| 模块 | 格式化 | 静态检查 | 测试 |
|---|---|---|---|
| core (Rust) | `rustfmt` | `clippy -D warnings` | `cargo test` + 一致性测试 |
| server (Go) | `gofmt` | `golangci-lint` | `go test -race` + 一致性测试 |
| android (Kotlin) | `ktlint` | `detekt` | 单元测试 + 必要的仪器测试 |
| desktop / web (TS) | `prettier` | `eslint`、`tsc --noEmit` | 单元测试 + 编辑器关键交互测试 |
| spec | — | OpenAPI lint、JSON Schema 校验 | 文档链接检查 |

- **CI 不通过，不得合并**，与代码由谁（人或 AI）编写无关。
- CI 按目录触发（`paths` 过滤），避免无关模块拖慢流水线。
- 增设安全与依赖检查：`cargo audit`、`govulncheck`、依赖更新机器人。

### 10.4 分工与分支

- **按模块边界分工，而非按功能**：一个 AI 同一时间负责一个目录，不并行修改同一文件。
- 每个 AI 使用独立分支，经 PR 合并到主干；主干保护，禁止直接推送。
- 跨模块改动（尤其是 spec 变化）需先在 PR 中说明影响面，并通知相关模块负责人。

### 10.5 任务粒度

- 给 AI 的任务应**小而具体且可验证**，例如："实现墓碑的拉取与应用，并通过 conformance 用例 `tombstone-*`"，而不是"实现同步功能"。
- 每个任务应有明确的验收标准（通过哪些测试、满足哪条 spec）。

### 10.6 交叉评审

- 每个 PR 至少由**另一个 AI 或人**评审，不得自写自审自合。
- 以下三类属于高风险区域，**必须**评审：
  1. 同步逻辑与冲突处理
  2. 数据库迁移与 schema 变更
  3. 认证、鉴权与权限隔离
- 评审清单见[附录 B](#附录-b-pr-评审清单)。

### 10.7 禁止事项（写入每个 `AGENTS.md`）

- 不使用客户端本地时间判断笔记新旧。
- 不物理删除笔记（仅软删除；清理由明确的后台任务负责，需 spec 定义）。
- 不使用自增 ID 作为跨设备的笔记标识。
- 不在 UI 层实现同步或冲突逻辑。
- 不在日志、错误信息中输出笔记正文、密码或 token。
- 不直接修改已发布的迁移脚本，只追加新迁移。
- 不引入未经 ADR 说明的新依赖或新框架。
- 不以"让测试通过"为目的修改或删除既有测试，除非 spec 同步变更。

### 10.8 数据安全与迁移

- Schema 变更只通过版本化迁移脚本，必须包含"从上一版本升级"的测试。
- 始终提供"一键导出为 `.md`"作为最后安全网，并纳入测试。
- 在真实数据上升级前，先备份。

### 10.9 依赖与安全

- 依赖尽量少而成熟；新增依赖需在 PR 中说明理由、许可证与维护状态。
- 依赖版本锁定（`Cargo.lock`、`go.sum`、`package-lock.json` / 对应锁文件）。
- 密钥与凭据不入库，使用环境变量或密钥管理。

---

## 11. 文档规范

文档质量与代码质量同等重要，且是多 AI 协作的基础设施。

### 11.1 文档分层

| 文档 | 位置 | 作用 | 读者 |
|---|---|---|---|
| `README.md` | 根目录及各模块 | 项目/模块简介、构建与运行 | 所有人 |
| `AGENTS.md` | 根目录及各模块 | AI 协作规范与禁止事项 | 所有 AI |
| `spec/*` | `spec/` | 协议、数据模型、错误码、API | 实现者与评审者 |
| `docs/architecture.md` | `docs/` | 架构与模块关系 | 新加入者 |
| `docs/adr/*` | `docs/adr/` | 架构决策记录 | 决策者与后续维护者 |
| 代码注释与 API 文档 | 源码内 | 解释"为什么"，导出接口的用法 | 开发者 |
| `CHANGELOG.md` | 根目录 | 版本变更记录 | 使用者 |

### 11.2 编写规则

1. **文档与代码同一 PR 更新**：改行为必须改文档，评审时检查。
2. **一处定义，多处引用**：同一事实只在一处写明，其他位置链接，避免漂移。
3. **写"为什么"而不只是"是什么"**：重要取舍记录在 ADR 中。
4. **示例可运行**：文档中的命令与代码示例应能直接执行，并在 CI 中尽可能验证。
5. **术语统一**：在 `spec/` 维护术语表（如 note、rev、seq、cursor、tombstone、conflict copy），各处使用一致名称。
6. **使用简体中文为主、关键术语保留英文**；代码标识符与提交信息使用英文。

### 11.3 ADR 模板

```markdown
# ADR-XXXX：标题

- 状态：提议 / 已接受 / 已废弃 / 被 ADR-YYYY 取代
- 日期：YYYY-MM-DD

## 背景
为什么需要做这个决定？

## 决策
我们决定……

## 备选方案
- 方案 A：……（为何不选）
- 方案 B：……（为何不选）

## 影响
正面、负面，以及后续需要做的事。
```

### 11.4 代码文档要求

- Rust：公开接口必须有 `///` 文档注释；开启 `#![warn(missing_docs)]`。
- Go：导出的类型与函数必须有以名称开头的注释。
- Kotlin / TypeScript：公共 API 使用 KDoc / TSDoc。
- 复杂算法与同步状态机在文件头给出流程说明或指向 spec 条目。

---

## 12. 里程碑与路线图

| 阶段 | 内容 | 产出 / 验收 |
|---|---|---|
| **M0：契约与脚手架** | 建立 monorepo；编写 `spec/`（协议、数据模型、错误码、OpenAPI）；首批 conformance 用例；`AGENTS.md`、CI、`justfile` | spec 评审通过；CI 能在空实现上跑通 lint 与测试框架 |
| **M1：服务端** | Go 服务端：账号、设备 token、笔记存储、push/pull、实时通知、导出 | 通过全部 server 侧 conformance 用例；有越权访问测试 |
| **M2：核心库** | Rust `core`：本地存储、同步引擎、冲突副本、FTS5 搜索、导出；UniFFI 绑定 | 通过全部 core 侧 conformance 用例；与 server 联调通过 |
| **M3：Android** | Compose UI、编辑器、同步状态、系统分享入口、后台同步 | 日常可用；与服务端多设备联调 |
| **M4：桌面端** | Tauri（Windows + Linux），CodeMirror 编辑器，托盘与快捷键 | Windows、Linux 均可用；Linux 输入法实测通过 |
| **M5：Web 端** | 共享前端组件；v1 在线客户端 | 浏览器可登录并编辑；与其他端同步一致 |
| **M6：加固与发布** | 备份恢复演练、性能与稳定性测试、文档完善、发布流程 | 恢复演练记录；`CHANGELOG`、使用文档齐全 |
| **未来** | Web 离线（WASM）、Apple 端、附件、E2EE 评估 | 另行立项 |

**推进顺序原则**：先 spec，再 server 与 core 并行（各自通过 conformance），联调通过后才开始各端 UI。这样多 AI 并行时地基是稳的。

---

## 13. 待决策事项

| # | 事项 | 当前倾向 | 需要决策的时间 |
|---|---|---|---|
| 1 | Web 端是否支持离线（WASM + OPFS） | v1 仅在线，后续评估 | M5 前 |
| 2 | 是否启用端到端加密 | v1 不启用，模型预留 | M0 内确认 |
| 3 | 前端框架（Svelte / React） | Svelte，或选 AI 最熟悉者 | M4 前 |
| 4 | 是否做"回收站" | 建议做，成本低、安全性高 | M1 前 |
| 5 | 注册方式（邀请码 / 管理员创建） | 邀请码或管理员创建 | M1 前 |
| 6 | 冲突副本的命名与展示规则 | 标题前缀标注，保留原笔记 | M0 内确认 |
| 7 | 软删除笔记的最终清理策略 | 保留 N 天后由后台任务清理，需 spec 定义 | M1 前 |
| 8 | 实时通道选型（WebSocket / SSE） | WebSocket；Android 后台需结合 WorkManager | M1 前 |
| 9 | 单条笔记的大小上限 | 需在 spec 中规定（如 1 MB），超限报错码 | M0 内确认 |

---

## 14. 附录

### 附录 A：根目录 `AGENTS.md` 模板

```markdown
# AGENTS.md — djhx-memo 全局协作规范

## 项目简介
djhx-memo 是跨平台、多端云同步的 Markdown 备忘录。详见 README.md 与 docs/architecture.md。

## 必读文档
1. spec/ —— 唯一事实来源，实现与其冲突时以 spec 为准
2. 本文件与你所在目录的 AGENTS.md

## 工作方式
- 只修改任务指定的目录；跨目录改动需在 PR 中说明
- 改行为先改 spec，且在同一 PR 中提交
- 任务完成前必须运行 `just fmt && just lint && just test`
- 提交遵循 Conventional Commits：feat / fix / docs / refactor / test / chore

## 禁止事项
- 不用客户端时间判断新旧
- 不物理删除笔记
- 不用自增 ID 作为跨设备笔记标识
- 不在 UI 层实现同步或冲突逻辑
- 不在日志中输出笔记正文、密码、token
- 不修改已发布的迁移脚本
- 不引入未经说明的新依赖
- 不为让测试通过而修改或删除既有测试

## 交付标准
- CI 全绿
- 公共接口有文档
- 行为变化有对应测试与文档
- 高风险区域（同步、迁移、鉴权）必须等待交叉评审
```

### 附录 B：PR 评审清单

**通用**
- [ ] 是否引用了对应的 spec 条目？spec 与实现一致吗？
- [ ] 是否有对应测试？测试是否真正覆盖了新行为（而非仅为通过）？
- [ ] CI 是否全绿？
- [ ] 文档是否同步更新？
- [ ] 是否引入了新依赖？理由是否充分？

**同步相关**
- [ ] 是否使用了客户端时间判断新旧？
- [ ] 删除是否为软删除？墓碑能否正确同步到其他设备？
- [ ] 游标推进是否与应用变更在同一事务内完成？
- [ ] 请求重试是否幂等？
- [ ] 冲突时是否保留了败者内容？

**数据库**
- [ ] 是否通过新增迁移脚本实现，而非修改旧脚本？
- [ ] 是否有"从旧版本升级"的测试？
- [ ] 是否有可能导致数据丢失的操作？

**安全**
- [ ] 所有查询是否带用户隔离条件？是否有越权测试？
- [ ] 日志、错误信息中是否泄露敏感内容？
- [ ] 密钥、token 是否未入库？

### 附录 C：术语表（草案）

| 术语 | 含义 |
|---|---|
| Note | 一条笔记，内容为 Markdown 纯文本 |
| rev | 单条笔记的版本号，每次被服务端接受的修改递增 |
| seq | 账号级别的全局变更序号，由服务端分配，单调递增 |
| cursor | 客户端记录的"已同步到的 seq" |
| Tombstone（墓碑） | 软删除标记，用于把删除操作同步到其他设备 |
| Conflict copy（冲突副本） | 冲突时为保存败者内容而生成的独立笔记 |
| Conformance | 与语言无关的一致性测试用例，服务端与核心库都必须通过 |
| Device token | 每台设备独立的访问凭证，可单独吊销 |

### 附录 D：建议的首批 Git 提交顺序

1. `chore: init monorepo with README, AGENTS.md, justfile, editorconfig`
2. `docs(spec): add data model and sync protocol draft`
3. `docs(spec): add openapi and error codes`
4. `test(spec): add first batch of conformance scenarios`
5. `ci: add path-filtered workflows for core, server, spec`
6. 之后按 M1、M2 分别开分支实现

---

*本文档为草案，随项目推进持续更新。重大变更请通过 ADR 记录。*
