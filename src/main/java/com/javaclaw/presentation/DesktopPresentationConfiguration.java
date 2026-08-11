package com.javaclaw.presentation;

import com.javaclaw.chat.AssistantMessageFactory;
import com.javaclaw.chat.ChatMessageRowFactory;
import com.javaclaw.chat.ChatInlineImageRenderer;
import com.javaclaw.chat.ChatShortcutHelpFactory;
import com.javaclaw.chat.ClarificationCardFactory;
import com.javaclaw.chat.LoopDecisionFactory;
import com.javaclaw.chat.ExpandableMarkdownBlockFactory;
import com.javaclaw.chat.MarkdownBubbleFactory;
import com.javaclaw.chat.MarkdownRenderEngine;
import com.javaclaw.chat.markdown.MarkdownParagraphRenderer;
import com.javaclaw.chat.markdown.MarkdownRegionViewFactory;
import com.javaclaw.platform.desktop.ExternalLinkOpener;
import com.javaclaw.platform.desktop.ProjectAttachmentPicker;
import com.javaclaw.platform.execution.ManagedTaskExecutor;
import com.javaclaw.platform.fxml.SpringFxmlLoader;
import com.javaclaw.platform.fx.FxDispatcher;
import com.javaclaw.ui.javafx.loop.LoopStatusViewFactory;
import com.javaclaw.ui.javafx.image.ImageViewerFactory;
import com.javaclaw.ui.javafx.control.WindowToastFactory;
import com.javaclaw.ui.javafx.knowledge.KnowledgeMenuEntryFactory;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsExportTargetPicker;
import com.javaclaw.ui.javafx.diagnostics.DiagnosticsViewFactory;
import com.javaclaw.ui.javafx.diagnostics.JavaFxDiagnosticsExportTargetPicker;
import com.javaclaw.ui.javafx.theme.ThemeManagerThemeSelectionService;
import com.javaclaw.ui.javafx.theme.ThemeMenuEntryFactory;
import com.javaclaw.ui.javafx.theme.ThemeSelectionService;
import com.javaclaw.ui.javafx.theme.FontManagerFontSelectionService;
import com.javaclaw.ui.javafx.theme.FontSelectionService;
import com.javaclaw.ui.javafx.plugin.JavaFxPluginJarPicker;
import com.javaclaw.ui.javafx.plugin.PluginCenterViewFactory;
import com.javaclaw.ui.javafx.plugin.PluginComponentFactory;
import com.javaclaw.ui.javafx.plugin.PluginJarPicker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 桌面 Presentation 层的显式工厂装配；页面 Controller 仍按 FXML 加载临时创建。 */
@Configuration(proxyBeanMethods = false)
public class DesktopPresentationConfiguration {

    @Bean
    ImageViewerFactory imageViewerFactory(
            SpringFxmlLoader loader,
            FxDispatcher fx) {
        return new ImageViewerFactory(loader, fx);
    }

    @Bean
    ChatInlineImageRenderer chatInlineImageRenderer(ImageViewerFactory imageViewer) {
        return new ChatInlineImageRenderer(imageViewer);
    }

    @Bean
    ChatShortcutHelpFactory chatShortcutHelpFactory(SpringFxmlLoader loader) {
        return new ChatShortcutHelpFactory(loader);
    }

    @Bean
    WindowToastFactory windowToastFactory(SpringFxmlLoader loader) {
        return new WindowToastFactory(loader);
    }

    @Bean
    MarkdownRegionViewFactory markdownRegionViewFactory(
            SpringFxmlLoader loader,
            FxDispatcher fx,
            ImageViewerFactory imageViewer) {
        return new MarkdownRegionViewFactory(loader, fx, imageViewer);
    }

    @Bean
    MarkdownRenderEngine markdownRenderEngine(MarkdownRegionViewFactory regions) {
        return (markdown, style) -> MarkdownParagraphRenderer.render(markdown, style, regions);
    }

    @Bean
    ExternalLinkOpener externalLinkOpener(ManagedTaskExecutor tasks) {
        return new ExternalLinkOpener(tasks);
    }

    @Bean
    ProjectAttachmentPicker projectAttachmentPicker() {
        return new ProjectAttachmentPicker();
    }

    @Bean
    DiagnosticsExportTargetPicker diagnosticsExportTargetPicker() {
        return new JavaFxDiagnosticsExportTargetPicker();
    }

    @Bean
    DiagnosticsViewFactory diagnosticsViewFactory(SpringFxmlLoader loader) {
        return new DiagnosticsViewFactory(loader);
    }

    @Bean
    PluginJarPicker pluginJarPicker() {
        return new JavaFxPluginJarPicker();
    }

    @Bean
    PluginComponentFactory pluginComponentFactory(SpringFxmlLoader loader) {
        return new PluginComponentFactory(loader);
    }

    @Bean
    PluginCenterViewFactory pluginCenterViewFactory(SpringFxmlLoader loader) {
        return new PluginCenterViewFactory(loader);
    }

    @Bean
    MarkdownBubbleFactory markdownBubbleFactory(SpringFxmlLoader loader) {
        return new MarkdownBubbleFactory(loader);
    }

    @Bean
    AssistantMessageFactory assistantMessageFactory(SpringFxmlLoader loader) {
        return new AssistantMessageFactory(loader);
    }

    @Bean
    ExpandableMarkdownBlockFactory expandableMarkdownBlockFactory(SpringFxmlLoader loader) {
        return new ExpandableMarkdownBlockFactory(loader);
    }

    @Bean
    ChatMessageRowFactory chatMessageRowFactory(SpringFxmlLoader loader) {
        return new ChatMessageRowFactory(loader);
    }

    @Bean
    LoopDecisionFactory loopDecisionFactory(SpringFxmlLoader loader) {
        return new LoopDecisionFactory(loader);
    }

    @Bean
    ClarificationCardFactory clarificationCardFactory(SpringFxmlLoader loader) {
        return new ClarificationCardFactory(loader);
    }

    @Bean
    LoopStatusViewFactory loopStatusViewFactory(SpringFxmlLoader loader) {
        return new LoopStatusViewFactory(loader);
    }

    @Bean
    ThemeSelectionService themeSelectionService() {
        return new ThemeManagerThemeSelectionService();
    }

    @Bean
    FontSelectionService fontSelectionService() {
        return new FontManagerFontSelectionService();
    }

    @Bean
    ThemeMenuEntryFactory themeMenuEntryFactory(SpringFxmlLoader loader) {
        return new ThemeMenuEntryFactory(loader);
    }

    @Bean
    KnowledgeMenuEntryFactory knowledgeMenuEntryFactory(SpringFxmlLoader loader) {
        return new KnowledgeMenuEntryFactory(loader);
    }
}
