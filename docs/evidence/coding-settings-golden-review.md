# Coding 设置入口的视觉回归记录

2026-09-07，在保留 509f197 的 JavaFX 布局、CSS 令牌与交互语言的前提下，设置导航新增“编程环境”。生产导航
因此由 29 项变为 30 项。更新前先备份工作树中已有的 54 张参考图及清单，再对真实 JavaFX 生成结果逐图比较。
本次没有把尚未提交的旧参考图还原到 Git HEAD，也没有修改截图渲染器或逐字节比较规则。

## 差异与审阅

范围仍是九主题、三密度、两个窗口尺寸与 100% 字号，共 54 张 macOS aarch64 设置中心壳/外观页截图。
[逐图记录](coding-settings-golden-diff.tsv)保留原始文件名、前后 SHA-256、改变的像素数量与差异边界。每张图只有
29、34 或 39 个像素变化，全部落在已有导航滚动条滑块的下端；内容区与其余像素完全相同。原因是导航新增一项后
滑块略微缩短。另直接查看了 emerald/compact/minimum 的前后图，确认变化符合生产控件行为。

差异边界采用左闭右开的 `[left, top, right, bottom]` 坐标，均为以下六种之一：

| 密度 | 最小窗口 | 标准窗口 |
| --- | --- | --- |
| 紧凑 | `[199,213,204,220]` | `[199,262,204,271]` |
| 标准 | `[199,203,204,210]` | `[199,248,204,257]` |
| 舒展 | `[199,192,204,199]` | `[199,233,204,241]` |

更新前原图与清单保留在本次执行机的 `/tmp/javaclaw-golden-before-coding-v4nxcbk3/macos-reference`；该路径是本机
临时审阅备份，不是发行内容。逐图记录的 SHA-256 为
`857d75774559523f67ac5d909f0d9fdf45cf08d1c67b8d2951f3952928c66e1e`。

## 正常门禁复验

审阅后显式更新参考图，再执行不带更新开关的普通 Failsafe 比较，独立 JVM 的 1 项矩阵测试全部通过。该测试逐字节
比较 54 张图，并要求原参考清单不被重写。普通复验命令为：

```sh
mvn -pl javaclaw-desktop -am test-compile failsafe:integration-test failsafe:verify \
  -Dit.test=ManagementCenterGoldenIT -Dfailsafe.failIfNoSpecifiedTests=false
```

| 本机日志 | 结果 | SHA-256 |
| --- | --- | --- |
| `/tmp/javaclaw-desktop-golden-update.log` | 显式更新后通过 | `bc75c26027cda91b1714983321957e31f03eb2209fa422a4acde14d2b50b422f` |
| `/tmp/javaclaw-desktop-golden-check.log` | 未启用更新，54 图比较通过 | `a148f910ea11760c05aa9a25c3d2ec2b1dfd5cfb620c6e37067f6c012c345215` |

此记录只证明现有设置中心壳/外观页的矩阵及新增导航对滚动条的影响。编程环境正文、命令和 Diff 转录的多状态视觉、
其他字号以及 Linux/Windows 的真实界面，不能由这 54 张图替代验收。完整仓库的最终构建结果另行记录。
