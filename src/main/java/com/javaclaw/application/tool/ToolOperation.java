package com.javaclaw.application.tool;

/** 经授权后执行的工具正文。 */
@FunctionalInterface
public interface ToolOperation {

    String execute() throws Exception;
}
