package dev.shop.api.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Text a person typed: no control, format, surrogate, private-use or unassigned code points. */
@Documented
@Constraint(validatedBy = PlainTextValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PlainText {

    String message() default "must not contain control or invisible characters";

    boolean allowNewline() default false;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
