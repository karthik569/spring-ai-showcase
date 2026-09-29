package com.example.springai.error;

public class StructuredOutputParseException extends RuntimeException {

    private final String rawModelOutput;

    public StructuredOutputParseException(Class<?> targetType, String rawModelOutput) {
        this(targetType, rawModelOutput, null);
    }

    public StructuredOutputParseException(Class<?> targetType, String rawModelOutput, Throwable cause) {
        super("Model response did not contain a complete " + targetType.getSimpleName() + " object", cause);
        this.rawModelOutput = rawModelOutput;
    }

    public String getRawModelOutput() {
        return rawModelOutput;
    }
}
