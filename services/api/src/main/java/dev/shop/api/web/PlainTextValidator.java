package dev.shop.api.web;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PlainTextValidator implements ConstraintValidator<PlainText, CharSequence> {

    private boolean allowNewline;

    @Override
    public void initialize(PlainText constraint) {
        this.allowNewline = constraint.allowNewline();
    }

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        return value == null
                || value.toString().codePoints().allMatch(codePoint -> PlainTextRules.allows(codePoint, allowNewline));
    }
}
