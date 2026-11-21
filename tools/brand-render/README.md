# 玉白品牌资源包渲染

可选的开发工具，按 [ADR-0020](../../docs/adr/0020-jade-ivory-brand-render.md)锁定 `@resvg/resvg-js@2.6.2`（MPL-2.0；[上游源码与许可](https://github.com/yisibl/resvg-js/tree/v2.6.2)）。只渲染已核哈希的自有SVG，不加载系统字体或外部资源。普通客户端/官网构建和Python品牌校验不需要此工具。

从本目录安装固定依赖：

```text
npm ci --ignore-scripts --no-audit --no-fund
node render.mjs
```

渲染输出先放 `.local/brand-jade-pack/`，包含62项图形输出、原母版和包内清单。ZIP成员顺序/时间戳固定，正文无字体依赖；adaptive前景缩放为母版线性映射的85%，保持上一版Android约58dp的前景占幅，并检查66dp alpha安全区和单色负形。设计板按72/108视口比例模拟圆形遮罩，不使用系统字体；这不代替真机验收。

确认母版和映射后，从本目录执行 `node render.mjs --write-pack` 将已渲染包写入 `assets/brand/packs/jade-ivory-v1.zip`；已存在包如字节不同会拒绝覆盖，应创建新版本而非覆盖历史。再从仓库根执行：

```text
python scripts/generate-brand-assets.py
python scripts/generate-brand-assets.py --check
```

普通客户端/官网构建只消费已提交的受管输出，不执行渲染器。Python读取器核包/源文件/每个成员哈希及完整路径集合，不提取任意ZIP路径。修改母版须先归档并确认新哈希，不直接手改活动PNG、ICO、Android图层或官网图标。当前输出和实际检查见[验证记录](../../docs/brand-jade-ivory-validation.md)；第三方声明见[通知](THIRD_PARTY_NOTICES.md)。
