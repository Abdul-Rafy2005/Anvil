package com.anvil.scheduler;

public class InvalidCronExpressionException extends RuntimeException {
    public InvalidCronExpressionException(String expression, String detail) {
        super("Invalid cron expression: '" + expression + "' — " + detail);
    }
}
