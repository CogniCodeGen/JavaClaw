package com.javaclaw.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class ToolRegistrationSupportTest {

    @Test
    void registersEveryComponentExposedByACompositeToolSet() {
        Toolkit toolkit = new Toolkit();

        ToolRegistrationSupport.register(toolkit, new CompositeTools());

        assertEquals(Set.of("first_tool", "second_tool"), Set.copyOf(toolkit.getToolNames()));
    }

    private static final class CompositeTools implements ToolObjectProvider {
        @Override
        public List<Object> toolObjects() {
            return List.of(new FirstTools(), new SecondTools());
        }
    }

    private static final class FirstTools {
        @Tool(name = "first_tool", description = "first test tool")
        public String first() {
            return "first";
        }
    }

    private static final class SecondTools {
        @Tool(name = "second_tool", description = "second test tool")
        public String second() {
            return "second";
        }
    }
}
