package com.anvil.scheduler;

import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;
import com.cronutils.model.time.ExecutionTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;

@Component
public class CronSchedulerHelper {

    private static final Logger log = LoggerFactory.getLogger(CronSchedulerHelper.class);

    private final CronParser cronParser;

    public CronSchedulerHelper() {
        CronDefinition definition = CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING);
        this.cronParser = new CronParser(definition);
    }

    public void validate(String cronExpression) {
        try {
            cronParser.parse(cronExpression);
        } catch (IllegalArgumentException e) {
            throw new InvalidCronExpressionException(cronExpression, e.getMessage());
        }
    }

    public Instant getNextFireTime(String cronExpression, Instant after) {
        Cron cron = cronParser.parse(cronExpression);
        ExecutionTime executionTime = ExecutionTime.forCron(cron);
        ZonedDateTime zonedDateTime = after.atZone(ZoneOffset.UTC);
        Optional<ZonedDateTime> next = executionTime.nextExecution(zonedDateTime);
        return next.map(ZonedDateTime::toInstant).orElse(null);
    }
}
