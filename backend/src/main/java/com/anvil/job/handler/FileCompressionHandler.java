package com.anvil.job.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * FILE_COMPRESSION handler.
 *
 * <p><b>Idempotency note:</b> Naturally idempotent if output overwrites the same archive.
 * Not idempotent if output path includes timestamps or if downstream systems are notified.
 */
@Component
public class FileCompressionHandler implements JobHandler<String, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String jobType() { return "FILE_COMPRESSION"; }

    @Override
    public int defaultMaxRetries() { return 3; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(30); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        JsonNode json = mapper.readTree(payload);
        String format = json.has("format") ? json.get("format").asText("ZIP") : "ZIP";

        String[] steps = {"Reading input files", "Analyzing compression targets", "Compressing data",
                "Verifying integrity", "Writing output archive"};
        for (int i = 0; i < steps.length; i++) {
            if (ctx.isCancellationRequested()) return null;
            int pct = (int) ((i + 1) * 100.0 / steps.length);
            ctx.reportProgress(pct, steps[i]);
            Thread.sleep(2500);
        }

        return "{\"archiveUrl\": \"/archives/" + ctx.jobId() + "." + format.toLowerCase()
                + "\", \"originalSize\": \"250MB\", \"compressedSize\": \"85MB\", \"ratio\": \"66%\"}";
    }
}
