package com.anvil.config;

import com.anvil.auth.JwtProvider;
import com.anvil.auth.User;
import com.anvil.auth.UserRepository;
import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobPriority;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.repository.JobRepository;
import com.anvil.notification.NotificationService;
import com.anvil.notification.NotificationType;
import com.anvil.queue.AnvilQueue;
import com.anvil.queue.OutboxRelay;
import com.anvil.worker.WorkerRunner;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "worker.id=f0000000-0000-0000-0000-000000000001",
        "worker.poll-interval-ms=999999999",
        "worker.heartbeat-interval-ms=999999999",
        "worker.visibility-timeout-seconds=5",
        "watchdog.interval-ms=999999999",
        "queue.relay.interval-ms=999999999",
        "scheduler.retry-interval-ms=999999999",
        "scheduler.scheduled-scan-interval-ms=999999999",
        "spring.flyway.enabled=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebSocketIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("anvil_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getFirstMappedPort());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @LocalServerPort
    private int serverPort;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtProvider jwtProvider;
    @Autowired private JobRepository jobRepository;
    @Autowired private AnvilQueue queue;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private WorkerRunner workerRunner;
    @Autowired private NotificationService notificationService;
    @Autowired private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private UUID testUserId;
    private String jwtToken;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        String email = "wsuser" + System.nanoTime() + "@test.com";
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + testUserId + "', '" + email + "', 'hash', 'USER', true)");
        User user = userRepository.findById(testUserId).orElseThrow();
        jwtToken = jwtProvider.generateAccessToken(user);
    }

    @Test
    @Order(1)
    void connect_withValidToken_establishesSession() throws Exception {
        WebSocketStompClient stompClient = createStompClient();

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        StompSessionHandler handler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        };

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + jwtToken);

        stompClient.connect(wsUrl(), httpHeaders, new StompHeaders(), handler);

        StompSession session = sessionFuture.get(10, TimeUnit.SECONDS);
        assertNotNull(session);
        assertTrue(session.isConnected());
        stompClient.stop();
    }

    @Test
    @Order(2)
    void connect_withInvalidToken_rejected() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        StompSessionHandler handler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                try {
                    Thread.sleep(3000);
                    result.complete(!session.isConnected());
                } catch (InterruptedException e) {
                    result.complete(true);
                }
            }
            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                result.complete(true);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                result.complete(true);
            }
        };

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer invalid-token-12345");

        stompClient.connect(wsUrlRaw(), httpHeaders, new StompHeaders(), handler);

        try {
            boolean rejected = result.get(15, TimeUnit.SECONDS);
            assertTrue(rejected, "Connection with invalid token should be rejected");
        } catch (TimeoutException e) {
            fail("Connection with invalid token was not rejected within timeout");
        }
        stompClient.stop();
    }

    @Test
    @Order(3)
    void connect_withoutToken_rejected() throws Exception {
        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        StompSessionHandler handler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                try {
                    Thread.sleep(3000);
                    result.complete(!session.isConnected());
                } catch (InterruptedException e) {
                    result.complete(true);
                }
            }
            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                result.complete(true);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                result.complete(true);
            }
        };

        stompClient.connect(wsUrlRaw(), new WebSocketHttpHeaders(), new StompHeaders(), handler);

        try {
            boolean rejected = result.get(15, TimeUnit.SECONDS);
            assertTrue(rejected, "Connection without token should be rejected");
        } catch (TimeoutException e) {
            fail("Connection without token was not rejected within timeout");
        }
        stompClient.stop();
    }

    @Test
    @Order(4)
    void subscribeToJob_receivesStatusUpdates() throws Exception {
        drainQueue();

        Job job = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.QUEUED);
        job.setMaxRetries(1);
        job = jobRepository.saveAndFlush(job);
        UUID jobId = job.getId();
        queue.enqueue(jobId, JobPriority.MEDIUM);

        java.util.List<Map<String, Object>> receivedFrames = new CopyOnWriteArrayList<>();
        CountDownLatch frameLatch = new CountDownLatch(2);

        WebSocketStompClient stompClient = createStompClient();
        StompFrameHandler frameHandler = new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                String body = new String((byte[]) payload);
                try {
                    Map<String, Object> msg = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(body, Map.class);
                    receivedFrames.add(msg);
                    frameLatch.countDown();
                } catch (Exception ignored) {}
            }
        };

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        };

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + jwtToken);

        stompClient.connect(wsUrl(), httpHeaders, new StompHeaders(), sessionHandler);
        StompSession session = sessionFuture.get(10, TimeUnit.SECONDS);
        session.subscribe("/topic/job/" + jobId, frameHandler);

        outboxRelay.relay();
        workerRunner.poll();

        boolean completed = frameLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Should receive at least 2 status frames");

        boolean hasRunning = receivedFrames.stream()
                .anyMatch(f -> "RUNNING".equals(f.get("status")));
        boolean hasCompleted = receivedFrames.stream()
                .anyMatch(f -> "COMPLETED".equals(f.get("status")));

        assertTrue(hasRunning, "Should receive RUNNING status update");
        assertTrue(hasCompleted, "Should receive COMPLETED status update");

        stompClient.stop();
    }

    @Test
    @Order(5)
    void subscribeToJob_receivesProgressUpdates_inIncreasingOrder() throws Exception {
        drainQueue();

        String payload = "{\"totalRows\": 5000}";
        Job job = new Job(testUserId, "CSV_IMPORT", payload, JobStatus.QUEUED);
        job.setMaxRetries(1);
        job = jobRepository.saveAndFlush(job);
        UUID jobId = job.getId();
        queue.enqueue(jobId, JobPriority.MEDIUM);

        java.util.List<Map<String, Object>> receivedFrames = new CopyOnWriteArrayList<>();
        CountDownLatch progressLatch = new CountDownLatch(5);

        WebSocketStompClient stompClient = createStompClient();
        StompFrameHandler frameHandler = new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                String body = new String((byte[]) payload);
                try {
                    Map<String, Object> msg = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(body, Map.class);
                    if ("PROGRESS".equals(msg.get("type"))) {
                        receivedFrames.add(msg);
                        progressLatch.countDown();
                    }
                } catch (Exception ignored) {}
            }
        };

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        };

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + jwtToken);

        stompClient.connect(wsUrl(), httpHeaders, new StompHeaders(), sessionHandler);
        StompSession session = sessionFuture.get(10, TimeUnit.SECONDS);
        session.subscribe("/topic/job/" + jobId, frameHandler);

        outboxRelay.relay();
        workerRunner.poll();

        boolean completed = progressLatch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Should receive 5 progress frames");

        int lastPct = 0;
        for (Map<String, Object> frame : receivedFrames) {
            int pct = ((Number) frame.get("progressPct")).intValue();
            assertTrue(pct > lastPct, "Progress should be increasing: got " + pct + " after " + lastPct);
            lastPct = pct;
        }
        assertEquals(100, lastPct, "Final progress should be 100%");

        stompClient.stop();
    }

    @Test
    @Order(6)
    void subscribeToUserTopic_receivesNotification() throws Exception {
        UUID notifJobId = UUID.randomUUID();
        String topic = "/topic/user/" + testUserId + "/notifications";

        WebSocketStompClient stompClient = createStompClient();
        java.util.List<Map<String, Object>> receivedFrames = new CopyOnWriteArrayList<>();
        CountDownLatch notifLatch = new CountDownLatch(1);

        StompFrameHandler frameHandler = new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @SuppressWarnings("unchecked")
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                String body = new String((byte[]) payload);
                try {
                    Map<String, Object> msg = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readValue(body, Map.class);
                    receivedFrames.add(msg);
                    notifLatch.countDown();
                } catch (Exception ignored) {}
            }
        };

        CompletableFuture<StompSession> sessionFuture = new CompletableFuture<>();
        StompSessionHandler sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                sessionFuture.complete(session);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionFuture.completeExceptionally(exception);
            }
        };

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + jwtToken);

        stompClient.connect(wsUrl(), httpHeaders, new StompHeaders(), sessionHandler);
        StompSession session = sessionFuture.get(10, TimeUnit.SECONDS);
        session.subscribe(topic, frameHandler);

        Thread.sleep(1000);

        notificationService.createNotification(testUserId, notifJobId,
                NotificationType.JOB_COMPLETED, "Job completed successfully");

        boolean received = notifLatch.await(10, TimeUnit.SECONDS);
        assertTrue(received, "Should receive notification on user topic");

        Map<String, Object> notif = receivedFrames.get(0);
        assertEquals("JOB_COMPLETED", notif.get("type"));
        assertEquals("Job completed successfully", notif.get("message"));

        stompClient.stop();
    }

    @Test
    @Order(7)
    void subscribeToOtherUserTopic_rejected() throws Exception {
        UUID otherUserId = UUID.randomUUID();
        String otherEmail = "wsuser" + System.nanoTime() + "@test.com";
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + otherUserId + "', '" + otherEmail + "', 'hash', 'USER', true)");
        User otherUser = userRepository.findById(otherUserId).orElseThrow();
        String otherToken = jwtProvider.generateAccessToken(otherUser);

        WebSocketStompClient stompClient = createStompClient();
        CompletableFuture<Boolean> sessionClosed = new CompletableFuture<>();

        StompSessionHandler handler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
                StompFrameHandler frameHandler = new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) { return byte[].class; }
                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {}
                };
                session.subscribe("/topic/user/" + testUserId + "/notifications", frameHandler);
            }
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                sessionClosed.complete(true);
            }
            @Override
            public void handleException(StompSession session, StompCommand command,
                                        StompHeaders headers, byte[] payload, Throwable exception) {
                sessionClosed.complete(true);
            }
        };

        WebSocketHttpHeaders httpHeaders = new WebSocketHttpHeaders();
        httpHeaders.add("Authorization", "Bearer " + otherToken);

        stompClient.connect(wsUrl(), httpHeaders, new StompHeaders(), handler);

        boolean closed = sessionClosed.get(15, TimeUnit.SECONDS);
        assertTrue(closed, "Session should be closed when subscribing to another user's topic");

        stompClient.stop();
    }

    private void drainQueue() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-ws", 1).isPresent()) {}
        }
    }

    private WebSocketStompClient createStompClient() {
        List<Transport> transports = List.of(new WebSocketTransport(new StandardWebSocketClient()));
        SockJsClient sockJsClient = new SockJsClient(transports);
        WebSocketStompClient stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new org.springframework.messaging.converter.MappingJackson2MessageConverter());
        return stompClient;
    }

    private String wsUrl() {
        return "http://localhost:" + serverPort + "/ws";
    }

    private String wsUrlRaw() {
        return "ws://localhost:" + serverPort + "/ws";
    }
}
