package com.anvil.job.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * CSV_IMPORT handler.
 *
 * <p><b>Idempotency warning:</b> This handler is NOT idempotent. A real implementation
 * inserting rows would duplicate data on crash-retry. Production implementations must
 * use upserts, idempotency keys, or transactional batch boundaries to prevent duplicates.
 */
@Component
public class CsvImportHandler implements JobHandler<String, String> {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int CHUNK_SIZE = 1000;

    @Override
    public String jobType() { return "CSV_IMPORT"; }

    @Override
    public int defaultMaxRetries() { return 2; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(120); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        JsonNode json = mapper.readTree(payload);
        int totalRows = json.has("totalRows") ? json.get("totalRows").asInt(100000) : 100000;

        int processed = 0;
        int errors = 0;
        while (processed < totalRows) {
            if (ctx.isCancellationRequested()) return null;

            int chunkEnd = Math.min(processed + CHUNK_SIZE, totalRows);
            int pct = (int) (chunkEnd * 100.0 / totalRows);
            ctx.reportProgress(pct, "Processing rows " + (processed + 1) + " - " + chunkEnd + " / " + totalRows);

            Thread.sleep(100);
            processed = chunkEnd;
        }

        return "{\"rowsProcessed\": " + totalRows + ", \"errors\": " + errors + ", \"duration\": \"completed\"}";
    }
}
