# 0.2.2 下载镜像与最新 DEB 运行手册

状态：2026-11-22，客户端/构建/部署工具已接入，尚未在生产服务器启用。实际检查与未执行项见[0.2.2记录](releases/0.2.2-validation.md)。本轮只创建Release draft，不执行本手册中的公开、SSH或服务器操作。

## 兼容和职责

- 签名目录保持v1和GitHub规范资产URL；0.2.2在验证身份后推导固定memo传输地址。旧客户端仍通过GitHub升级一次；GitHub不可达时从官网人工覆盖安装，不能改变应用身份或签名。
- 来源切换不切换目录feed：`https://memo.djhx.site/updates/stable/current.json`仍是检查/撤回/安装前复核入口。memo全站不可达时，GitHub选择不提供完整自动更新灾备；发布页按钮供人工恢复。
- GitHub Actions使用原签名身份构建草稿；只有Release公开后catalog工作流才生成目录、官网与镜像部署包。CI没有服务器SSH凭据，不自动公开Release或部署。
- Linux只在浏览器打开最新amd64DEB。个人源latest为短缓存302，GitHub使用正式Release的固定名别名；两者都不形成客户端安装句柄。

## 服务器首次准备

维护者在授权的部署窗口核对磁盘容量、备份/保留预算、Python3.9+、curl/sha256sum/flock/GNU mv、Nginx与SSH/sudo环境。Python工具按块计算哈希，不依赖仅3.11提供的file_digest。部署路径只接受无空格的绝对目录，默认`/var/www`；变更路径时同时调整Nginx配置。

Windows Git Bash本机按实际可运行性选择`python3`、`python`、`py -3`，跳过WindowsApps占位程序；也可用`DJHX_DEPLOY_PYTHON`指定Python可执行文件（含空格的完整路径需引号，不填命令参数）。该选择只用于本机，服务器仍要求可用的`python3`。脚本先完成下载、解包、镜像及目录验签，之后才显示确认和sudo口令提示；之前任一步失败都会打印阶段及退出码，不能仅凭出现下载横幅判断网络故障。

2026-11-22本机工具回归：Git Bash的`python3`解析到WindowsApps占位程序，运行无输出、退出49，实际`python`为3.11.2；旧脚本在下载后的首个`python3`处受`set -e`影响退出。修复后通过3项无网络/SSH的回归（跳过占位程序、缺少可用Python先报错、显式含空格路径），并以真实v0.2.2部署包完成下载、解包、镜像哈希和Rust目录/资产验证（sequence13），在确认处回答`no`后退出，未进入sudo或SSH。错误日志只打印阶段/退出码，不打印可能含口令的命令。此工具修复不修改已发布tag、安装包或CI工作流，使用最新仓库中的脚本即可。

独立持久目录为：

| 路径 | 用途 |
|---|---|
| `/var/www/memo.djhx.site-downloads/releases/<tag>/` | 只读原始EXE/APK/DEB、Windows.sig及SHA256SUMS；同路径不同内容拒绝覆盖 |
| `/var/www/memo.djhx.site-downloads/latest-deb.conf` | 精确latest location的302目标；首次先写`return 503;` |
| `/var/www/memo.djhx.site-updates/stable/` | 独立持久签名目录；不随站点回滚而回退 |
| `/var/www/memo.djhx.site-releases/<stamp>/` | 可轮换的官网页面；updates软链指向持久feed |
| `/tmp/djhx-deploy/<stamp>/` | 0700私有暂存，仅本次部署使用 |

备份原Nginx配置/链接/签名目录，按[示例配置](../config/nginx-downloads.conf.example)在http层建立下载专用连接区，在memo server块加入downloads路由及latest include。先建立latest的503占位再`nginx -t`/reload；不得把`/v1`、SSE、`/healthz`加入下载限流。示例单IP2连接、总4连接、每连接1MiB/s仅为起点，必须按生产带宽测量调整，不能当作资源验收已通过。

静态目录只给Nginx读权限，不放数据库/私钥或执行脚本，关闭目录列表。首次新目录由部署工具从当前站点updates复制原始已签名文件；新官网updates指向独立feed。历史站点仍可能包含旧目录，后续人工回切旧页面时必须保留独立feed路由，不得重新暴露历史current。

## 正式推广顺序

1. 先验收草稿附件：标准三端文件、Windows.sig、server归档、固定名`djhx-memo-linux-amd64.deb`及SHA256SUMS。别名与版本化DEB字节/hash相同；Android原证书、包名/code17/minSdk29/双ABI，Windows版本化签名均核对。Linux原生/旧版覆盖、两源实际下载和系统安装结果单独记录。
2. 维护者决定公开正式Release后，catalog job继续生成兼容v1签名目录；bundle job下载同一套附件、核对SHA与别名，用`prepare-download-assets.py`生成`downloads/manifest.json`及版本化文件，与`site/`、`updates/stable/`打包。清单是本地打包完整性记录，不能替代目录签名或原APK证书。
3. 本机运行`scripts/deploy-website.sh v0.2.2`。先设置`DJHX_DEPLOY_SSH_TARGET`，必要时设置`DJHX_DEPLOY_WWW_ROOT`；脚本下载部署包、安全解包、核对镜像清单，并以已有Rust验证器验签目录、Windows包和APK大小/hash，然后展示摘要。确认及sudo口令仍按原工具交互，仅经stdin，不打印/落盘。
4. 上传私有staging，执行其中本次独立远程脚本。`deploy-remote-v2.sh`取得全局flock，要求新sequence严格大于持久feed当前值，以Python整数比较避免溢出；版本化目录名/资产名受固定规则限制。
5. 发布器逐文件fsync并以硬链接原子放入持久文件区；现有相同hash可幂等复用，不同hash拒绝。旧文件不删除。对公网每份镜像做匿名完整下载/hash检查，文件失败时不推广页面、latest或current。
6. 暂存新官网，updates链接到持久feed；准备latest302，`nginx -t`后原子切换站点链接并reload，复核首页及latest实际DEB完整hash。再放入不可变`catalog-<sequence>.json`，原子替换current，公网比较原始签名字节。
7. 完成后按[官网检查器](../scripts/check-website-downloads.py)验证静态/真实JS、四语主题、主/备用链接；验证HEAD/Range/长度/Content-Type、未知文件404，下载并发期间API/SSE健康。当前脚本不替代这些目标环境检查。

## 失败、撤回与保留

current推广前失败可恢复旧页面/latest；持久文件区保留已写入原包，feed保持原序号。脚本在移动current之前标记已进入推广阶段，随后失败不自动恢复旧feed；即使移动失败也保守要求检查现场并向前修复。

current一旦可被客户端接受，修复/撤回必须以更高sequence重新签署，不能切回低序号或清客户端防回放。网站外观可以独立回切，但不得回切feed；两源旧包及所引用资产继续保留。新current校验失败、并发部署、磁盘不足、nginx失败、HTTP失败需在隔离Linux环境注入验收，本轮尚未实际执行该部署矩阵。

撤回Linux包须同步调整个人latest与GitHub的latest Release选择，指向含正确固定名别名的安全适用版本；无安全版本则暂停入口。GitHub后续单端发行不能被推广成不含Linux别名的latest。旧文件不可改写，浏览器latest可变不意味着自动更新允许降级。
