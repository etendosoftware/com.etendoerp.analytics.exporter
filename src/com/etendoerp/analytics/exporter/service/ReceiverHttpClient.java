package com.etendoerp.analytics.exporter.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.exception.OBException;

import com.etendoerp.analytics.exporter.data.AnalyticsPayload;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for sending analytics data to the receiver
 * Implements retry policy for 5xx errors
 */
public class ReceiverHttpClient {

  private static final Logger log = LogManager.getLogger();
  private static final int MAX_RETRIES = 3;
  private static final int RETRY_DELAY_MS = 2000;
  private static final String DEFAULT_RECEIVER_URL = "https://receiver.otel2.etendo.cloud/process";

  private final String receiverUrl;
  private final ObjectMapper objectMapper;

  /**
   * Default constructor that uses the default receiver URL.
   */
  public ReceiverHttpClient() {
    this(DEFAULT_RECEIVER_URL);
  }

  /**
   * Constructor that allows specifying a custom receiver URL.
   * @param receiverUrl the URL of the receiver service
   */
  public ReceiverHttpClient(String receiverUrl) {
    this.receiverUrl = StringUtils.isNotBlank(receiverUrl) ? receiverUrl : DEFAULT_RECEIVER_URL;
    this.objectMapper = new ObjectMapper();
    log.debug("ReceiverHttpClient initialized with URL: {}", this.receiverUrl);
  }

  /**
   * Send analytics payload to receiver with retry logic
   *
   * @param payload
   *     The analytics data to send
   * @return ReceiverResponse with job_id if successful
   * @throws Exception
   *     if all retries fail
   */
  public ReceiverResponse sendPayload(AnalyticsPayload payload) throws Exception {
    log.debug("Preparing to send payload to receiver");

    // Convert payload to JSON
    String jsonPayload = objectMapper.writeValueAsString(payload);
    log.debug("Payload size: {} bytes", jsonPayload.getBytes(StandardCharsets.UTF_8).length);

    return sendPayload(jsonPayload);
  }

  /**
   * Send JSON payload string to receiver with retry logic
   *
   * @param jsonPayload
   *     The JSON string to send
   * @return ReceiverResponse with job_id if successful
   * @throws Exception
   *     if all retries fail
   */
  public ReceiverResponse sendPayload(String jsonPayload) throws Exception {
    log.debug("Preparing to send JSON payload to receiver");
    log.debug("Payload size: {} bytes", jsonPayload.getBytes(StandardCharsets.UTF_8).length);

    Exception lastException = null;

    // Retry loop
    for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        log.debug("Attempt {}/{} to send data to receiver", attempt, MAX_RETRIES);

        HttpURLConnection conn = getHttpURLConnection(jsonPayload);

        int responseCode = conn.getResponseCode();
        log.debug("Receiver responded with status code: {}", responseCode);

        // Read response
        String responseBody = readResponseBody(conn, responseCode);
        log.debug("Response body: {}", responseBody);

        // Handle response codes
        ReceiverResponse result = handleResponseCode(responseCode, responseBody, attempt);
        if (result != null) {
          return result;
        }

      } catch (Exception e) {
        lastException = e;
        log.error("Error on attempt {}/{}: {}", attempt, MAX_RETRIES, e.getMessage());

        if (attempt < MAX_RETRIES && shouldRetry(e)) {
          log.debug("Waiting {} ms before retry...", RETRY_DELAY_MS);
          Thread.sleep(RETRY_DELAY_MS);
        } else if (!shouldRetry(e)) {
          throw e;
        }
      }
    }

    // All retries failed
    String errorMsg = "Failed to send data after " + MAX_RETRIES + " attempts";
    log.error(errorMsg);
    throw new OBException(errorMsg, lastException);
  }

  /**
   * Read response body from connection.
   */
  private String readResponseBody(HttpURLConnection conn, int responseCode) throws IOException {
    StringBuilder response = new StringBuilder();
    try (BufferedReader br = new BufferedReader(
        new InputStreamReader(
            responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
            StandardCharsets.UTF_8))) {
      String line;
      while ((line = br.readLine()) != null) {
        response.append(line);
      }
    }
    return response.toString();
  }

  /**
   * Handle response code and return ReceiverResponse if successful, null if retry is needed.
   */
  private ReceiverResponse handleResponseCode(int responseCode, String responseBody, int attempt) throws Exception {
    if (responseCode == 202) {
      // Success - parse response
      ReceiverResponse receiverResponse = objectMapper.readValue(responseBody, ReceiverResponse.class);
      log.debug("Data accepted successfully. Job ID: {}", receiverResponse.getJobId());
      return receiverResponse;

    } else if (responseCode >= 500) {
      // Server error - retry
      log.warn("Server error ({}), will retry. Response: {}", responseCode, responseBody);
      if (attempt < MAX_RETRIES) {
        log.debug("Waiting {} ms before retry...", RETRY_DELAY_MS);
        Thread.sleep(RETRY_DELAY_MS);
      }
      return null; // Signal to retry

    } else if (responseCode >= 400) {
      // Client error - don't retry
      String errorMsg = "Client error: " + responseCode + " - " + responseBody;
      log.error(errorMsg);
      throw new OBException(errorMsg);

    } else {
      // Unexpected success code
      log.warn("Unexpected response code: {}", responseCode);
      throw new OBException("Unexpected response code: " + responseCode);
    }
  }

  private HttpURLConnection getHttpURLConnection(String jsonPayload) throws IOException {
    log.debug("Creating HTTP connection to: {}", receiverUrl);
    if (receiverUrl == null) {
      log.error("ERROR: receiverUrl is null!");
    }
    URL url = new URL(receiverUrl != null ? receiverUrl : DEFAULT_RECEIVER_URL);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

    // Configure connection
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    conn.setDoOutput(true);
    conn.setConnectTimeout(30000); // 30 seconds
    conn.setReadTimeout(60000); // 60 seconds

    // Send payload
    try (OutputStream os = conn.getOutputStream()) {
      byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
      os.write(input, 0, input.length);
    }
    return conn;
  }

  /**
   * Determine if an exception should trigger a retry
   */
  private boolean shouldRetry(Exception e) {
    // Retry on network errors, timeouts, etc.
    // Don't retry on JSON parsing errors or other logic errors
    String message = e.getMessage().toLowerCase();
    return message.contains("timeout")
        || message.contains("connection")
        || message.contains("network")
        || message.contains("server error");
  }

  /**
   * Response from the receiver
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class ReceiverResponse {
    private String status;

    @SuppressWarnings("java:S116") // Field name matches JSON API format
    private String job_id;

    private String message;

    @SuppressWarnings("java:S116") // Field name matches JSON API format
    private Integer queue_position;

    private String error;

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public String getJobId() {
      return job_id;
    }

    public void setJobId(String jobId) {
      this.job_id = jobId;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public Integer getQueuePosition() {
      return queue_position;
    }

    public void setQueuePosition(Integer queuePosition) {
      this.queue_position = queuePosition;
    }

    public String getError() {
      return error;
    }

    public void setError(String error) {
      this.error = error;
    }
  }
}
