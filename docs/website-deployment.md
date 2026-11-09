# 官网与双远程发布记录

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

安装包、公开附件/校验、服务器切换与双远程main最终结果在完成后追加于下方。当前准备记录不等同于线上验收通过；物理arm64/API29、完整输入法/平台矩阵及连续7天试用继续见[验证清单](validation.md)。
