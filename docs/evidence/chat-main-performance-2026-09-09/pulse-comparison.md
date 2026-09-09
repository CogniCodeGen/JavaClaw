# 最终短对话 JavaFX 阶段对照

每个单元格依次是：事件/脉冲数；P95 ms；最大 ms；总 ms。空项表示没有事件，不是0ms。

| 回放阶段 | 插桩阶段 | before | after |
|---|---|---:|---:|
| idle | CSS Pass | 298; 0.0046; 0.0169; 0.7961 | 300; 0.0037; 0.0854; 0.7901 |
| idle | Layout Pass | 298; 0.0095; 0.0162; 1.6041 | 300; 0.0284; 0.1955; 5.1268 |
| idle | Update bounds | — | — |
| idle | Waiting for previous rendering | — | — |
| idle | Copy state to render graph | — | — |
| idle | Dirty Opts Computed | — | — |
| idle | Render Roots Discovered | — | — |
| idle | Painting | — | — |
| idle | Presenting | — | — |
| idle | Prism recorded sum per pulse | — | — |
| input | CSS Pass | 142; 0.0038; 0.5135; 0.7119 | 145; 0.0038; 0.3588; 0.5169 |
| input | Layout Pass | 142; 1.7208; 2.1900; 37.1129 | 145; 0.9879; 2.2439; 27.9745 |
| input | Update bounds | 31; 0.6744; 0.7580; 9.9596 | 31; 0.6097; 0.6763; 7.1827 |
| input | Waiting for previous rendering | 31; 0.0090; 0.0099; 0.1311 | 31; 0.0141; 0.0350; 0.1295 |
| input | Copy state to render graph | 31; 0.3314; 0.5473; 5.9940 | 31; 0.3839; 0.3965; 4.6203 |
| input | Dirty Opts Computed | 31; 0.3159; 0.3940; 4.2016 | 31; 0.2812; 0.2856; 3.7131 |
| input | Render Roots Discovered | 30; 0.2308; 0.2722; 4.1382 | 30; 0.2732; 0.2943; 3.6655 |
| input | Painting | 32; 1.8526; 6.9665; 32.1576 | 32; 3.4566; 4.2200; 28.2408 |
| input | Presenting | 31; 1.5990; 3.4327; 22.0698 | 31; 1.4514; 1.7197; 15.5215 |
| input | Prism recorded sum per pulse | 31; 5.2528; 8.5776; 62.5673 | 31; 5.4416; 6.0697; 51.1409 |
| scroll | CSS Pass | 204; 0.0039; 0.0110; 0.3804 | 207; 0.0038; 0.0048; 0.3810 |
| scroll | Layout Pass | 204; 0.1804; 0.9614; 9.8542 | 207; 0.0408; 0.0716; 3.4371 |
| scroll | Update bounds | 54; 0.6536; 0.7408; 6.3701 | 3; 0.5876; 0.5876; 1.2559 |
| scroll | Waiting for previous rendering | 54; 5.9312; 7.1131; 31.6720 | 3; 0.0322; 0.0322; 0.0415 |
| scroll | Copy state to render graph | 54; 0.2062; 0.4088; 3.0017 | 3; 0.2264; 0.2264; 0.5099 |
| scroll | Dirty Opts Computed | 54; 0.1145; 0.2850; 1.9812 | 3; 0.0451; 0.0451; 0.0992 |
| scroll | Render Roots Discovered | 51; 0.1503; 0.2629; 2.5708 | — |
| scroll | Painting | 54; 10.4671; 12.0047; 267.8373 | 3; 9.2250; 9.2250; 22.2822 |
| scroll | Presenting | 54; 0.6567; 1.1587; 16.0163 | 3; 0.9424; 0.9424; 2.2066 |
| scroll | Prism recorded sum per pulse | 54; 11.0575; 12.9507; 288.4057 | 3; 10.2125; 10.2125; 24.5880 |

## 观察

- 以下只属于本机短对话专用插桩回放。最终普通profile与这次记录不同，不能混用CPU统计或向500条/60Hz外推。
- input Layout P95下降但max略升；Painting P95上升而max下降，各分位与总量方向不同。不要概括为全部阶段都加快。
- scroll Prism脉冲54→3，表示实际绘制次数明显不同；不能据其总时长下降声称同等重绘负载的每帧加速。新版仅3条数据，P95等于max。
- idle两边均没有已记录Prism阶段；CSS/Layout仍各有约300次短phase。没有绘制事件不表示FX线程完全静止。
- 所有纳入窗口的阶段事件完整落在对应StartedAt/EndedAt之间，本轮跨边界计数均0。阶段归类和P95/max没有混入启动、预热或截图。

## 统计限制

- 仅统计完整落在阶段时间窗的事件；跨边界事件另计且不进入P95/max。分位数为nearest-rank，空数据为null，不表示零开销。
- javafx.PulsePhase 是 JavaFX 调用点之间的 wall-clock duration，不是 CPU 时间；多个线程可并行，不能将FX与Prism总时长直接相加成端到端帧时长。
- Layout Pass 源码从 doLayoutPass 前开始，到下一phase结束，包含 post-layout listeners 和少量检查，不等于纯 doLayoutPass 耗时。
- Prism sum 按 pulseId+线程累加已记录 Dirty Opts Computed/Render Roots Discovered/Painting/Presenting；不包含未插桩的准备/收尾，也不是显示器光学帧。Painting可能同一pulse出现多段，单事件P95与按pulse求和不同。
- Presenting 含 prepare/present 等wall time，不能单独认定 GPU 忙或驱动阻塞；Waiting for previous rendering 是FX对渲染和同步的等待。
- JFR 事件关闭调用栈但每阶段仍创建事件；该专用短对话回放必须前后使用相同设置，不能直接替代普通profile的CPU指标。
- 此专用记录只有 short-idle，不能外推500条历史/20Hz/60Hz流式场景的phase耗时；单轮短窗口不支持稳定回归或跨平台结论。
