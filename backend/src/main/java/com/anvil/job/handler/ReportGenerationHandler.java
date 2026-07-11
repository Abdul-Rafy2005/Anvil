package com.anvil.job.handler;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ReportGenerationHandler implements JobHandler<String, String> {

    @Override
    public String jobType() { return "REPORT_GENERATION"; }

    @Override
    public int defaultMaxRetries() { return 3; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(30); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        int totalSteps = 6;
        String[] steps = {"Querying data source", "Building report layout", "Generating charts",
                "Rendering PDF", "Attaching summaries", "Finalizing"};

        for (int i = 0; i < totalSteps; i++) {
            if (ctx.isCancellationRequested()) return null;
            int pct = (int) ((i + 1) * 100.0 / totalSteps);
            ctx.reportProgress(pct, steps[i]);
            Thread.sleep(3000);
        }

        return "{\"reportUrl\": \"/reports/" + ctx.jobId() + ".pdf\", \"pages\": 42, \"format\": \"PDF\"}";
    }
}
