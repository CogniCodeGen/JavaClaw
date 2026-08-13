package com.javaclaw.framework.spi;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Explicit authorization metadata for a host {@code @Tool} method or its declaring class. */
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ToolContract {
    String group();
    String[] permissions();
    boolean idempotent();
}
