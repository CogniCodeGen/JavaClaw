# 可选打包字体 — 放置说明

如需让指定字体在所有平台保持完全一致，可把以下字体文件放到本目录
（`src/main/resources/fonts/`）。`FontManager.loadBundledFonts()` 只注册实际存在的资源；
未随包提供且系统未安装的字体不会出现在设置面板中，应用会使用系统原生字体，
不会产生逐文件缺失警告。

## 需要的文件（与 `FontManager.BUNDLED_FONTS` 对应）

```
fonts/
├── Inter-Regular.ttf
├── Inter-Medium.ttf
├── Inter-SemiBold.ttf
├── Inter-Bold.ttf
├── NotoSansSC-Regular.otf
├── NotoSansSC-Medium.otf
├── NotoSansSC-Bold.otf
├── CascadiaCode-Regular.ttf
└── CascadiaCode-SemiBold.ttf
```

## 下载来源（均为 SIL OFL 1.1，可随应用打包分发）

- **Inter** — github.com/rsms/inter（拉丁正文回退）
- **Noto Sans SC** — fonts.google.com/noto（中文回退，字形优美、覆盖全）
- **Cascadia Code** — github.com/microsoft/cascadia-code（柔和等宽，带连字）

> 体积考虑：Noto Sans SC 全字重较大（每个 ~8–10MB）。若在意安装包体积，可只打包
> Regular + Medium + Bold 三档，或用 pyftsubset 子集化裁剪到常用字。

## 为什么要打包

CSS 里 named family（如 `"Inter"`）只有在系统装了该字体时才生效，否则静默回退。
打包 + `loadBundledFonts()` 后这些 family 在任何机器都能解析 —— 这就是
「系统原生优先、打包回退保证一致」的落地方式。请在项目 `LICENSE` / `THIRD-PARTY`
中保留各字体的 OFL 声明。
