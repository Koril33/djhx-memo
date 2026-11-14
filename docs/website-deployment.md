# 官网与双远程发布记录

2026-11-14在0.1.8复现旧哈希JS将三个下载入口覆盖为0.1.3 Release页；历史HTML/哈希核验不代表执行脚本后链接正确。现已改为单一下载清单生成直链，完整构建原子切换，并对线上执行真实浏览器JS、各平台公开附件哈希及API路由分别验证，实际结果见[0.1.8记录](releases/0.1.8-validation.md)。

日期：2026-11-09。本轮将 Gitea 开发成果同步到公开 GitHub main，补齐 v0.1.3 的 Android/Linux 安装包，并在原同步服务域名部署静态产品官网。官网不是独立 Web 笔记客户端，不改变数据库、epoch、鉴权或同步协议。

## 提交与来源核对

- 本轮开始时 GitHub/Gitea main 同为 `6c92c9a`；`9692a4c` 的 Windows Android 构建入口、`7c553d7` 的围栏代码高亮、`5ee96a7` 的0.1.3发布准备已在 main。
- 所有其他远程开发分支头均已是该 main 的祖先；只有 `redesign-website-homepage` 的 `73d6074`、`53fbbbd` 两个提交未包含，可保留历史直接快进，无需重复 cherry-pick 或改写旧提交。
- 两远程的 annotated tag `v0.1.3` 指向同一tag对象，peel为 `5ee96a771b0eb2546082b7badbf833e55310b3ce`。本轮新构建的 Android/core/desktop源码与此提交相同；官网/运维的后续提交不冒充客户端新版本，不移动发布tag。
- GitHub公开承载 main、tag 和 Release；Gitea继续保留私人开发分支与评审。只同步此次已审阅的 main，不将私有机器配置或所有开发分支批量公开。

## 网站与 Nginx 配置

官网由 `website/` 通过锁定 Vite 构建，共享Logo母版生成器，并携带项目、图标及Vite许可。四语/主题本机偏好、下载直链及重复下载入口保持简单静态实现。下载链接在相应 Release 附件公开可取后才上线。

部署位置为 `/var/www/memo.djhx.site`，使用指向只读版本目录的软链接；只上传 `website/dist/`，不上传源码、缓存、环境变量或签名材料。首页 `no-cache`，哈希资源长期缓存，缺失静态文件404。`/v1`、`/v1/` 下的路径和精确 `/healthz` 原样代理到原Go进程，保留可信转发头、SSE不缓冲和90秒超时。

Nginx文件改名为 `/etc/nginx/conf.d/memo.djhx.site.conf`。旧 `zz-` 前缀只用于把文件排在已有站点后，避免其变为隐式443默认站点；改名后字母顺序可能改变，所以须在原默认HTTPS站点仅增加显式 `default_server`，保留原默认行为。已有80默认站点已显式声明，保持不变。[Nginx官方默认主机规则](https://nginx.org/en/docs/http/request_processing.html)说明了此区别，文件名本身不负责域名路由。

切换前将原 `/etc/nginx` 备份到 root 专用目录，并记录原Go PID、二进制和未改配置哈希。新配置 `nginx -t` 通过后reload；健康/首页失败自动恢复原配置和静态链接。只更新Nginx和静态文件，不重启或替换Go服务，不触碰笔记数据库/epoch。新旧 memo 配置不得同时以 `.conf` 后缀加载。

## 验证与执行状态

准备阶段已完成四语切换、明暗切换/刷新持久化、1280×720与390×844浏览器显示检查，无横向溢出或浏览器警告/错误；统一品牌生成器36文件检查通过，既有客户端图标字节不变。core格式/Clippy及22项单元测试通过；真实Go/core联调7项通过，30个SSE样本P95为593.524ms、最大886.185ms。原有测试中的7个ignored联调另显式运行，没有计为常规单测通过。

安装包、公开附件/校验与服务器切换结果如下；物理arm64/API29、完整输入法/平台矩阵及连续7天试用继续见[验证清单](validation.md)。

## GitHub Release 附件

[v0.1.3](https://github.com/Koril33/djhx-memo/releases/tag/v0.1.3) 保留原Windows附件，补齐Linux与Android；统一SHA256SUMS和版本说明一并更新。三个附件都从无需登录的公开链接重新下载，逐字节计数和SHA256与本机归档相符；未移动tag，也未替换Windows包。

| 附件 | 字节 | SHA256 |
| --- | ---: | --- |
| djhx-memo-0.1.3-windows-x64-setup.exe | 7,510,080 | `f2af36b59d9618567b091677bbb090727b9a8a5923d906bd18a88a71b6f210ab` |
| djhx-memo-0.1.3-linux-amd64.deb | 8,185,720 | `98030e44925eac08e388c8cbeae448693213b26dc51029659b49f12495cddda1` |
| djhx-memo-0.1.3-android.apk | 36,228,262 | `57c56997659e9b74a09492edbc391bb231bfd7efbc6de531fe270caa844f794a` |

Linux前端27项、原生2项及定向高亮3项通过，实际deb覆盖安装、高亮/中英浅深主题/正常关闭重开、原数据哈希通过，随包7份许可；见[Linux验证](releases/0.1.3-linux-validation.md)。Android重新生成绑定并重编双ABI，JVM12项、设备15通过/1联调跳过，额外高亮探针2项及真实IME滚动1项通过；原签名覆盖安装、16KiB对齐、5份通知和实际安装包哈希通过，见[Android验证](releases/0.1.3-android-validation.md)。没有把本轮未重编的Windows包当成本机重新验收。

## 线上切换结果

- 官网构建来源 `3a75bdb`，部署到 `/var/www/memo.djhx.site-releases/20261109T134050Z-3a75bdb`，`/var/www/memo.djhx.site` 为root管理的软链接。Nginx已改为 `memo.djhx.site.conf`，旧 `zz-` 文件移入备份，没有重复加载。
- 备份位于 `/var/backups/djhx-memo-website-20261109T134050Z-3a75bdb`，权限0700。原默认443站点仅增加显式 `default_server`；原80默认站点不变。原主配置、默认HTTP配置及Go二进制哈希一致，原Go PID保持、服务active、NRestarts=0。既有站点和未知Host的TLS响应仍200，正文SHA256与切换前完全相同。
- `nginx -t` 通过后reload。第一次立即探测仍收到旧worker的401，随后重试取得与构建首页完全相同的200，未触发回退；不把此异步窗口误报为服务故障，也不声称本轮实际注入了回退失败。
- 公网正常验证证书的HTTPS请求核对首页及全部7个静态文件/许可的字节哈希；首页no-cache、哈希资源immutable、缺失资源/页面404、HTTP固定301到HTTPS、healthz200、无凭据API/SSE为401且no-store，均通过。
- 正式域名下使用专用合成账号完成邀请注册、第二设备登录、Push/重复幂等、Pull、ZIP、SSE首信号/提交通知/15秒心跳与吊销EOF。实测首信号52.3ms、提交通知77.1ms、心跳15.05s，为单次烟测，不是P95。合成笔记经正常同步进入墓碑；两枚设备token已吊销，测试账号按规范保留，未读取操作者真实笔记。
- 浏览器本机正式构建的桌面/手机、四语和主题回归通过；正式域名的HTTP资源校验通过。工具访问正式域名的浏览器预览两次超时，没有把它记成线上浏览器截图验收；构建截图保存在本机 `.local/release-013/`。

本轮原始构建、公开下载/资源哈希、代理部署与公网烟测证据在被忽略的 `.local/release-013/`；部署者私有账号、邀请和口令不入仓库，临时测试凭据用后清除。root备份保留用于回退。Git/GitHub API权限已验证，最终main以正常快进推送到两个远程；不公开Gitea开发分支、不删除或改写历史。
