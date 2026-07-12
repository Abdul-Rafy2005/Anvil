package com.anvil.job.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * EMAIL_CAMPAIGN handler.
 *
 * <p><b>Idempotency warning:</b> This handler is NOT idempotent. A real implementation
 * sending emails would double-send on crash-retry. Production implementations must
 * use an idempotency key (e.g. campaign_id + batch_number) to prevent duplicate sends.
 */
@Component
public class EmailCampaignHandler implements JobHandler<String, String> {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int BATCH_SIZE = 50;

    @Override
    public String jobType() { return "EMAIL_CAMPAIGN"; }

    @Override
    public int defaultMaxRetries() { return 3; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(90); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        JsonNode json = mapper.readTree(payload);
        int totalRecipients = json.has("recipientCount") ? json.get("recipientCount").asInt(1000) : 1000;

        int sent = 0;
        int bounced = 0;
        while (sent < totalRecipients) {
            if (ctx.isCancellationRequested()) return null;

            int batchEnd = Math.min(sent + BATCH_SIZE, totalRecipients);
            int pct = (int) (batchEnd * 100.0 / totalRecipients);
            ctx.reportProgress(pct, "Sending batch " + ((sent / BATCH_SIZE) + 1) + " - " + batchEnd + " / " + totalRecipients + " emails");

            Thread.sleep(50);
            sent = batchEnd;
        }

        return "{\"sent\": " + sent + ", \"bounced\": " + bounced + ", \"campaignId\": \"" + ctx.jobId() + "\"}";
    }
}
