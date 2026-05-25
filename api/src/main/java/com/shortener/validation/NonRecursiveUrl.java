package com.shortener.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = NonRecursiveUrlValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NonRecursiveUrl {
    String message() default "URL must not point to this shortener service";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
