/*
 *************************************************************************
 * The contents of this file are subject to the Etendo License
 * (the "License"), you may not use this file except in compliance with
 * the License.
 * You may obtain a copy of the License at
 * https://github.com/etendosoftware/etendo_core/blob/main/legal/Etendo_license.txt
 * Software distributed under the License is distributed on an
 * "AS IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing rights
 * and limitations under the License.
 * All portions are Copyright © 2021–2026 FUTIT SERVICES, S.L
 * All Rights Reserved.
 * Contributor(s): Futit Services S.L.
 *************************************************************************
 */

package com.etendoerp.analytics.exporter.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.domain.Preference;

import com.etendoerp.analytics.exporter.data.AnalyticsPayload;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * HTTP client for sending analytics data to the receiver
 * Implements retry policy for 5xx errors
 */
public class ReceiverHttpClient {

  private static final Logger log = LogManager.getLogger();
  private static final String DEFAULT_RECEIVER_URL = "https://receiver.otel2.etendo.cloud/process";

  private final String receiverUrl;
  private final AnalyticsExporterConfigService configService;
  private final ObjectMapper objectMapper;

  /**
   * Default constructor that uses preference URL if available, otherwise default receiver URL.
   */
  public ReceiverHttpClient() {
    this(null, new AnalyticsExporterConfigService());
  }

  /**
   * Constructor that allows specifying a custom receiver URL.
   *
   * @param receiverUrl
   *     the URL of the receiver service
   */
  public ReceiverHttpClient(String receiverUrl) {
    this(receiverUrl, new AnalyticsExporterConfigService());
  }

  ReceiverHttpClient(String receiverUrl, AnalyticsExporterConfigService configService) {
    this.receiverUrl = StringUtils.trimToNull(receiverUrl);
    this.configService = configService != null ? configService : new AnalyticsExporterConfigService();
    this.objectMapper = new ObjectMapper();
    log.debug("ReceiverHttpClient initialized with explicit URL override: {}", this.receiverUrl);
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
    AnalyticsExporterConfigService.EffectiveConfig config = configService.getEffectiveConfig();
    String effectiveReceiverUrl = StringUtils.defaultIfBlank(receiverUrl,
        StringUtils.defaultIfBlank(config.getReceiverUrl(), DEFAULT_RECEIVER_URL));
    int maxRetries = config.getMaxRetries();
    int retryDelayMs = config.getRetryDelayMs();

    log.debug("Preparing to send JSON payload to receiver");
    log.debug("Payload size: {} bytes", jsonPayload.getBytes(StandardCharsets.UTF_8).length);

    Exception lastException = null;
    long sendStart = System.nanoTime();

    // Retry loop
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
      try {
        log.debug("Attempt {}/{} to send data to receiver at {}", attempt, maxRetries, effectiveReceiverUrl);

        HttpURLConnection conn = getHttpURLConnection(jsonPayload, effectiveReceiverUrl, config);

        int responseCode = conn.getResponseCode();
        log.debug("Receiver responded with status code: {}", responseCode);

        // Read response
        String responseBody = readResponseBody(conn, responseCode);
        log.debug("Response body: {}", responseBody);

        // Handle response codes
        ReceiverResponse result = handleResponseCode(responseCode, responseBody, attempt, maxRetries, retryDelayMs);
        if (result != null) {
          result.setHttpStatusCode(responseCode);
          result.setRequestDurationMs(elapsedMillis(sendStart));
          return result;
        }

      } catch (Exception e) {
        lastException = e;
        log.error("Error on attempt {}/{}: {}", attempt, maxRetries, e.getMessage());

        if (attempt < maxRetries && shouldRetry(e)) {
          log.debug("Waiting {} ms before retry...", retryDelayMs);
          Thread.sleep(retryDelayMs);
        } else if (!shouldRetry(e)) {
          throw e;
        }
      }
    }

    // All retries failed
    String errorMsg = "Failed to send data after " + maxRetries + " attempts";
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
  private ReceiverResponse handleResponseCode(int responseCode, String responseBody, int attempt, int maxRetries,
      int retryDelayMs) throws Exception {
    if (responseCode == 202) {
      // Success - parse response
      ReceiverResponse receiverResponse = objectMapper.readValue(responseBody, ReceiverResponse.class);
      log.debug("Data accepted successfully. Job ID: {}", receiverResponse.getJobId());
      return receiverResponse;

    } else if (responseCode >= 500) {
      // Server error - retry
      log.warn("Server error ({}), will retry. Response: {}", responseCode, responseBody);
      if (attempt < maxRetries) {
        log.debug("Waiting {} ms before retry...", retryDelayMs);
        Thread.sleep(retryDelayMs);
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

  private HttpURLConnection getHttpURLConnection(String jsonPayload, String effectiveReceiverUrl,
      AnalyticsExporterConfigService.EffectiveConfig config) throws IOException {
    log.debug("Creating HTTP connection to: {}", effectiveReceiverUrl);
    URL url = new URL(StringUtils.defaultIfBlank(effectiveReceiverUrl, DEFAULT_RECEIVER_URL));
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

    // Configure connection
    conn.setRequestMethod("POST");
    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
    conn.setDoOutput(true);
    conn.setConnectTimeout(config.getConnectTimeoutMs());
    conn.setReadTimeout(config.getReadTimeoutMs());

    // Send payload
    try (OutputStream os = conn.getOutputStream()) {
      byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
      os.write(input, 0, input.length);
    }
    return conn;
  }

  private long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  /**
   * Determine if an exception should trigger a retry
   */
  private boolean shouldRetry(Exception e) {
    // Retry on network errors, timeouts, etc.
    // Don't retry on JSON parsing errors or other logic errors
    String message = StringUtils.defaultString(e.getMessage()).toLowerCase();
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

    @JsonProperty("job_id")
    @SuppressWarnings("java:S116") // Field name matches JSON API format
    private String job_id;

    private String message;

    @JsonProperty("queue_position")
    @SuppressWarnings("java:S116") // Field name matches JSON API format
    private Integer queue_position;

    private String error;
    private Integer httpStatusCode;
    private Long requestDurationMs;

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

    public Integer getHttpStatusCode() {
      return httpStatusCode;
    }

    public void setHttpStatusCode(Integer httpStatusCode) {
      this.httpStatusCode = httpStatusCode;
    }

    public Long getRequestDurationMs() {
      return requestDurationMs;
    }

    public void setRequestDurationMs(Long requestDurationMs) {
      this.requestDurationMs = requestDurationMs;
    }
  }

  /**
   * Get receiver URL from preferences
   *
   * @return the configured receiver URL from preferences, or null if not configured
   */
  public static String getReceiverUrlFromPreference() {
    try {
      List<Preference> preferences = OBDal.getInstance().createCriteria(Preference.class)
          .add(Restrictions.eq(Preference.PROPERTY_PROPERTY, "ETAE_ReceiverURL"))
          .list();

      Preference receiverURL = preferences.stream()
          .filter(Preference::isSelected)
          .findFirst()
          .orElse(preferences.get(0));

      return receiverURL.getSearchKey();
    } catch (Exception e) {
      log.warn("Could not load receiver URL from preferences", e);
      return null;
    }
  }
}
