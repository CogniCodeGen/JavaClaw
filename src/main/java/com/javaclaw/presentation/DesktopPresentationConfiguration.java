package com.javaclaw.presentation;

import com.javaclaw.chat.AssistantMessageFactory;
import com.javaclaw.chat.ChatMessageRowFactory;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 桌面 Presentation 层的显式工厂装配；页面 Controller 仍按 FXML 加载临时创建。 */
@Configuration(proxyBeanMethods = false)
public class DesktopPresentationConfiguration {

    @Bean
    MarkdownRegionViewFactory markdownRegionViewFactory(
            SpringFxmlLoader loader,
            FxDispatcher fx) {
        return new MarkdownRegionViewFactory(loader, fx);
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
}
