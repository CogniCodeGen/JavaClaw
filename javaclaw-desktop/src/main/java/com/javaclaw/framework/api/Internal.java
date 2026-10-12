package com.javaclaw.framework.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks host implementation surface that is not part of the extension compatibility contract. */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.PACKAGE, ElementType.CONSTRUCTOR, ElementType.METHOD})
public @interface Internal { }
