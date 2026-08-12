# @claw-manager/document-parser

OpenClaw 插件共用的办公文档解析库。支持 DOCX、PDF、XLSX/XLS、CSV、PPTX 和纯文本。

默认限制：单文件 20MB、文字最多 80k 字、图片最多 10 张、PDF 视觉页最多 10 页。超过限制时返回 `warnings`，调用方应把这些提示展示给 Agent/用户。

本包不依赖 LibreOffice、Pandoc 或 Tesseract。Office 内嵌图片会被提取为图片文件；PDF 优先提取文字，页面渲染为 best-effort，运行环境不支持渲染时会返回提示。
