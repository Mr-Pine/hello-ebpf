package me.bechberger.ebpf.annotations;

import java.lang.annotation.*;

/** The current type is used here under a different name */
@Target({ElementType.TYPE, ElementType.TYPE_USE})
@Repeatable(OriginalNames.class)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OriginalName {
    /** The original name of the type here */
    String value();
}