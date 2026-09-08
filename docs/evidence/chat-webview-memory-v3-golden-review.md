# JavaFX 26 设置中心参考图审阅

2026-09-07，本轮把 JavaFX 从 25 升级到 26.0.2。在相同应用类、JDK、平台与 54 张原参考图下，
设置中心的可变高度导航列表在 `Scene.snapshot` 中重新估算滚动条长度。本次按已有
[视觉证据规则](../architecture/acceptance-matrix.md#证据记录规则)接纳这一已定位的版本差异。
没有修改 JavaClaw 的布局、CSS、截图夹具或逐字节比较门禁，也没有安装旧版 JavaFX 类到产品中。

对照覆盖九主题、三密度、两个窗口和 100% 字号。54 张尺寸一致，其中 27 张逐字节一致；另 27 张总计
783 个不同像素，仅位于左侧滚动条末端的三个区域，坐标均包含边界：

- `[199,262,203,270]`
- `[199,202,203,205]`
- `[199,248,203,255]`

这些区域外的每个像素完全一致。主任务实际读取并比较了 `carbon/standard/standard` 与
`emerald/compact/standard` 的前后整图，确认字体、颜色、文案、卡片、布局和交互控件没有其他变化。
[逐图记录](chat-webview-memory-v3-golden-diff.json)保存旧提交、54 张旧新 SHA-256、像素边界与清单摘要；
旧图可从记录中的 Git 提交恢复。本次只替换 27 张有差异的图并同步摘要清单，未触及另外 27 张图。

隔离诊断中的六种 carbon 情况在完整 JavaFX 25 上均匹配原图；26.0.2 出现局部差异，改用 Metal 或 ES2
仍复现。字节码对照发现 `Parent.requestParentLayout(boolean force)` 从 `parent.requestLayout()`
变成 `parent.requestLayout(force)`；仅在临时诊断进程替换回旧 `Parent` 便消除差异。26 的调用栈为
`Scene.snapshot → doCSSLayoutSyncForSnapshot → doLayoutPass → Parent.layout → VirtualFlow.layoutChildren`。
这些实验位于 `/private/tmp/javaclaw-golden-diagnosis`，不属于产品依赖或生产补丁。

原计划“不更新 Golden”据此调整为本次明确审阅的参考图升级；不是格式化的附带改写。普通 `verify`
继续禁止更新参考图，并必须在独立 JVM 中通过全部 54 张比较；最终复验结果见
[V3 验证记录](chat-webview-memory-v3-validation.md)。此记录不代替其他页面与其他平台的视觉验收。
