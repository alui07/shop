package dev.shop.api.web;

/** Which code points count as plain text; shared by validation and by sanitising text that is not user input. */
public final class PlainTextRules {

    /** The same sets, in ECMA-262 syntax, for the OpenAPI document and the web app's schemas. */
    public static final String SINGLE_LINE_PATTERN = "^[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}]*$";

    public static final String MULTI_LINE_PATTERN = "^(?:\\n|[^\\p{Cc}\\p{Cf}\\p{Cs}\\p{Co}\\p{Cn}])*$";

    private PlainTextRules() {}

    public static boolean allows(int codePoint, boolean allowNewline) {
        if (codePoint == '\n') {
            return allowNewline;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.SURROGATE,
                    Character.PRIVATE_USE,
                    Character.UNASSIGNED -> false;
            default -> true;
        };
    }

    public static String withoutDisallowed(String text) {
        StringBuilder allowed = new StringBuilder(text.length());
        text.codePoints().filter(codePoint -> allows(codePoint, false)).forEach(allowed::appendCodePoint);
        return allowed.toString();
    }
}
