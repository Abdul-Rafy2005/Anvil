package com.anvil.job.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ImageProcessingHandler implements JobHandler<String, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String jobType() { return "IMAGE_PROCESSING"; }

    @Override
    public int defaultMaxRetries() { return 3; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(60); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        JsonNode json = mapper.readTree(payload);
        int batchSize = json.has("batchSize") ? json.get("batchSize").asInt(10) : 10;
        int totalImages = batchSize * 5;

        for (int i = 0; i < totalImages; i++) {
            if (ctx.isCancellationRequested()) return null;
            if (i % 5 == 0) {
                int pct = (int) ((i + 1) * 100.0 / totalImages);
                ctx.reportProgress(pct, "Converting image " + (i + 1) + " / " + totalImages + " to WebP");
            }
            Thread.sleep(200);
        }

        return "{\"convertedCount\": " + totalImages + ", \"outputFormat\": \"WebP\", \"outputDir\": \"/output/" + ctx.jobId() + "\"}";
    }
}
