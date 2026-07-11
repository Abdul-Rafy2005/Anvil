package com.anvil.job.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobHandlerRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobHandlerRegistry.class);

    private final Map<String, JobHandler<?, ?>> handlers = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public JobHandlerRegistry(ApplicationContext applicationContext) {
        Map<String, JobHandler> discovered = applicationContext.getBeansOfType(JobHandler.class);
        for (Map.Entry<String, JobHandler> entry : discovered.entrySet()) {
            JobHandler handler = entry.getValue();
            handlers.put(handler.jobType(), handler);
            log.info("Registered job handler: type={} class={}", handler.jobType(), handler.getClass().getSimpleName());
        }
        log.info("Job handler registry initialized with {} handlers", handlers.size());
    }

    @SuppressWarnings("unchecked")
    public <TPayload, TResult> JobHandler<TPayload, TResult> getHandler(String jobType) {
        JobHandler<?, ?> handler = handlers.get(jobType);
        if (handler == null) {
            throw new UnknownJobTypeException(jobType);
        }
        return (JobHandler<TPayload, TResult>) handler;
    }

    public boolean hasHandler(String jobType) {
        return handlers.containsKey(jobType);
    }
}
