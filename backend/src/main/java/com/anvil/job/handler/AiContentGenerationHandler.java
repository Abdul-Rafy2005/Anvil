package com.anvil.job.handler;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AiContentGenerationHandler implements JobHandler<String, String> {

    @Override
    public String jobType() { return "AI_CONTENT_GENERATION"; }

    @Override
    public int defaultMaxRetries() { return 2; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(30); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        String[] phases = {"Tokenizing prompt", "Running inference", "Post-processing output", "Formatting result"};
        for (int i = 0; i < phases.length; i++) {
            if (ctx.isCancellationRequested()) return null;
            int pct = (int) ((i + 1) * 100.0 / phases.length);
            ctx.reportProgress(pct, phases[i]);
            Thread.sleep(2000);
        }

        return "{\"generatedText\": \"Lorem ipsum generated content for job " + ctx.jobId()
                + "\", \"tokenCount\": 150, \"model\": \"anvil-llm-v1\"}";
    }
}
