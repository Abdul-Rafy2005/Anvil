package com.anvil.job.handler;

import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HandlerUnitTest {

    @Mock private JobRepository jobRepository;
    @Mock private Job job;

    private JobExecutionContext ctx;
    private final List<Integer> reportedPcts = new ArrayList<>();
    private final List<String> reportedMessages = new ArrayList<>();

    @BeforeEach
    void setUp() {
        lenient().when(job.getId()).thenReturn(UUID.randomUUID());
        lenient().when(job.getStatus()).thenReturn(JobStatus.RUNNING);
        lenient().when(jobRepository.save(any(Job.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(jobRepository.findById(any())).thenReturn(Optional.of(job));

        ctx = new JobExecutionContextImpl(job, 1, jobRepository) {
            @Override
            public void reportProgress(int pct, String message) {
                reportedPcts.add(pct);
                reportedMessages.add(message);
                super.reportProgress(pct, message);
            }

            @Override
            public boolean isCancellationRequested() {
                return false;
            }
        };
    }

    @Test
    void reportGeneration_producesResult() throws Exception {
        ReportGenerationHandler handler = new ReportGenerationHandler();
        String result = handler.execute("{}", ctx);

        assertNotNull(result);
        assertTrue(result.contains("reportUrl"));
        assertTrue(result.contains("pages"));
        assertEquals(6, reportedPcts.size());
        for (int i = 0; i < reportedPcts.size(); i++) {
            assertTrue(reportedPcts.get(i) > (i == 0 ? 0 : reportedPcts.get(i - 1)),
                    "Progress should be increasing");
        }
    }

    @Test
    void imageProcessing_producesResult() throws Exception {
        ImageProcessingHandler handler = new ImageProcessingHandler();
        String result = handler.execute("{\"batchSize\": 2}", ctx);

        assertNotNull(result);
        assertTrue(result.contains("convertedCount"));
        assertTrue(result.contains("WebP"));
        assertFalse(reportedPcts.isEmpty());
    }

    @Test
    void csvImport_producesResult() throws Exception {
        CsvImportHandler handler = new CsvImportHandler();
        String result = handler.execute("{\"totalRows\": 5000}", ctx);

        assertNotNull(result);
        assertTrue(result.contains("rowsProcessed"));
        assertTrue(result.contains("5000"));
        assertFalse(reportedPcts.isEmpty());
        assertEquals(100, reportedPcts.get(reportedPcts.size() - 1).intValue());
    }

    @Test
    void emailCampaign_producesResult() throws Exception {
        EmailCampaignHandler handler = new EmailCampaignHandler();
        String result = handler.execute("{\"recipientCount\": 200}", ctx);

        assertNotNull(result);
        assertTrue(result.contains("sent"));
        assertTrue(result.contains("200"));
        assertFalse(reportedPcts.isEmpty());
    }

    @Test
    void aiContentGeneration_producesResult() throws Exception {
        AiContentGenerationHandler handler = new AiContentGenerationHandler();
        String result = handler.execute("{}", ctx);

        assertNotNull(result);
        assertTrue(result.contains("generatedText"));
        assertTrue(result.contains("tokenCount"));
        assertEquals(4, reportedPcts.size());
    }

    @Test
    void fileCompression_producesResult() throws Exception {
        FileCompressionHandler handler = new FileCompressionHandler();
        String result = handler.execute("{\"format\": \"ZIP\"}", ctx);

        assertNotNull(result);
        assertTrue(result.contains("archiveUrl"));
        assertTrue(result.contains("ratio"));
        assertFalse(reportedPcts.isEmpty());
    }

    @Test
    void reportGeneration_respectsCancellation() throws Exception {
        ReportGenerationHandler handler = new ReportGenerationHandler();
        JobExecutionContext cancelCtx = new JobExecutionContextImpl(job, 1, jobRepository) {
            private int calls = 0;
            @Override
            public void reportProgress(int pct, String message) {
                calls++;
                super.reportProgress(pct, message);
            }
            @Override
            public boolean isCancellationRequested() {
                return calls >= 2;
            }
        };

        String result = handler.execute("{}", cancelCtx);
        assertNull(result);
        assertTrue(reportedPcts.size() <= 3, "Should stop after cancellation detected");
    }

    @Test
    void csvImport_respectsCancellation() throws Exception {
        CsvImportHandler handler = new CsvImportHandler();
        JobExecutionContext cancelCtx = new JobExecutionContextImpl(job, 1, jobRepository) {
            private int calls = 0;
            @Override
            public void reportProgress(int pct, String message) {
                calls++;
                super.reportProgress(pct, message);
            }
            @Override
            public boolean isCancellationRequested() {
                return calls >= 2;
            }
        };

        String result = handler.execute("{\"totalRows\": 100000}", cancelCtx);
        assertNull(result);
    }

    @Test
    void allHandlers_haveCorrectJobType() {
        assertEquals("REPORT_GENERATION", new ReportGenerationHandler().jobType());
        assertEquals("IMAGE_PROCESSING", new ImageProcessingHandler().jobType());
        assertEquals("CSV_IMPORT", new CsvImportHandler().jobType());
        assertEquals("EMAIL_CAMPAIGN", new EmailCampaignHandler().jobType());
        assertEquals("AI_CONTENT_GENERATION", new AiContentGenerationHandler().jobType());
        assertEquals("FILE_COMPRESSION", new FileCompressionHandler().jobType());
    }

    @Test
    void allHandlers_havePositiveTimeout() {
        assertTrue(new ReportGenerationHandler().defaultTimeout().toSeconds() > 0);
        assertTrue(new ImageProcessingHandler().defaultTimeout().toSeconds() > 0);
        assertTrue(new CsvImportHandler().defaultTimeout().toSeconds() > 0);
        assertTrue(new EmailCampaignHandler().defaultTimeout().toSeconds() > 0);
        assertTrue(new AiContentGenerationHandler().defaultTimeout().toSeconds() > 0);
        assertTrue(new FileCompressionHandler().defaultTimeout().toSeconds() > 0);
    }

    @Test
    void allHandlers_havePositiveMaxRetries() {
        assertTrue(new ReportGenerationHandler().defaultMaxRetries() > 0);
        assertTrue(new ImageProcessingHandler().defaultMaxRetries() > 0);
        assertTrue(new CsvImportHandler().defaultMaxRetries() > 0);
        assertTrue(new EmailCampaignHandler().defaultMaxRetries() > 0);
        assertTrue(new AiContentGenerationHandler().defaultMaxRetries() > 0);
        assertTrue(new FileCompressionHandler().defaultMaxRetries() > 0);
    }
}
