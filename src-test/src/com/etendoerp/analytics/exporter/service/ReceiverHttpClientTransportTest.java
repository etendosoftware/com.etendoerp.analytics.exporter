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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.criterion.Criterion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.domain.Preference;

import com.etendoerp.analytics.exporter.BaseAnalyticsTest;
import com.etendoerp.analytics.exporter.data.AnalyticsPayload;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

@ExtendWith(MockitoExtension.class)
public class ReceiverHttpClientTransportTest extends BaseAnalyticsTest {

  @Mock
  private AnalyticsExporterConfigService mockConfigService;

  @Mock
  private OBCriteria<Preference> mockPreferenceCriteria;

  @Mock
  private Preference selectedPreference;

  @Mock
  private Preference fallbackPreference;

  @Test
  public void testSendPayloadReturnsAcceptedResponseAndMetrics() throws Exception {
    AnalyticsExporterConfigService.EffectiveConfig config = buildConfig(1, 0);
    when(mockConfigService.getEffectiveConfig()).thenReturn(config);

    AtomicInteger hits = new AtomicInteger();
    HttpServer server = createServer(exchange -> {
      hits.incrementAndGet();
      writeResponse(exchange, 202,
          "{\"status\":\"accepted\",\"job_id\":\"job-1\",\"message\":\"ok\",\"queue_position\":2}");
    });

    try {
      ReceiverHttpClient client = new ReceiverHttpClient(serverUrl(server), mockConfigService);
      ReceiverHttpClient.ReceiverResponse response = client.sendPayload("{\"ping\":true}");

      assertEquals(1, hits.get());
      assertEquals("accepted", response.getStatus());
      assertEquals("job-1", response.getJobId());
      assertEquals(Integer.valueOf(202), response.getHttpStatusCode());
      assertNotNull(response.getRequestDurationMs());
      assertTrue(response.getRequestDurationMs() >= 0);
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void testSendPayloadRetriesOnServerErrorThenSucceeds() throws Exception {
    AnalyticsExporterConfigService.EffectiveConfig config = buildConfig(2, 1);
    when(mockConfigService.getEffectiveConfig()).thenReturn(config);

    AtomicInteger hits = new AtomicInteger();
    HttpServer server = createServer(exchange -> {
      if (hits.incrementAndGet() == 1) {
        writeResponse(exchange, 500, "temporary failure");
      } else {
        writeResponse(exchange, 202,
            "{\"status\":\"accepted\",\"job_id\":\"job-2\",\"message\":\"ok\",\"queue_position\":1}");
      }
    });

    try {
      ReceiverHttpClient client = new ReceiverHttpClient(serverUrl(server), mockConfigService);
      ReceiverHttpClient.ReceiverResponse response = client.sendPayload("{\"ping\":true}");

      assertEquals(2, hits.get());
      assertEquals("job-2", response.getJobId());
      assertEquals(Integer.valueOf(202), response.getHttpStatusCode());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void testSendPayloadThrowsOnClientErrorWithoutRetry() throws Exception {
    AnalyticsExporterConfigService.EffectiveConfig config = buildConfig(3, 1);
    when(mockConfigService.getEffectiveConfig()).thenReturn(config);

    AtomicInteger hits = new AtomicInteger();
    HttpServer server = createServer(exchange -> {
      hits.incrementAndGet();
      writeResponse(exchange, 400, "bad request");
    });

    try {
      ReceiverHttpClient client = new ReceiverHttpClient(serverUrl(server), mockConfigService);

      assertThrows(OBException.class, () -> client.sendPayload("{\"ping\":true}"));
      assertEquals(1, hits.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void testSendPayloadSerializesAnalyticsPayloadObject() throws Exception {
    AnalyticsExporterConfigService.EffectiveConfig config = buildConfig(1, 0);
    when(mockConfigService.getEffectiveConfig()).thenReturn(config);

    AtomicInteger hits = new AtomicInteger();
    HttpServer server = createServer(exchange -> {
      hits.incrementAndGet();
      writeResponse(exchange, 202,
          "{\"status\":\"accepted\",\"job_id\":\"job-3\",\"message\":\"ok\",\"queue_position\":0}");
    });

    try {
      ReceiverHttpClient client = new ReceiverHttpClient(serverUrl(server), mockConfigService);
      AnalyticsPayload payload = new AnalyticsPayload();
      payload.getMetadata().setSourceInstance("test-instance");

      ReceiverHttpClient.ReceiverResponse response = client.sendPayload(payload);

      assertEquals(1, hits.get());
      assertEquals("job-3", response.getJobId());
    } finally {
      server.stop(0);
    }
  }

  @Test
  public void testGetReceiverUrlFromPreferenceUsesSelectedPreferenceFirst() {
    when(mockOBDal.createCriteria(Preference.class)).thenReturn(mockPreferenceCriteria);
    when(mockPreferenceCriteria.add(any(Criterion.class))).thenReturn(mockPreferenceCriteria);
    when(mockPreferenceCriteria.list()).thenReturn(Arrays.asList(fallbackPreference, selectedPreference));
    when(fallbackPreference.isSelected()).thenReturn(false);
    when(selectedPreference.isSelected()).thenReturn(true);
    when(selectedPreference.getSearchKey()).thenReturn("https://selected.example/process");

    String url = ReceiverHttpClient.getReceiverUrlFromPreference();

    assertEquals("https://selected.example/process", url);
  }

  @Test
  public void testGetReceiverUrlFromPreferenceFallsBackToFirstPreference() {
    when(mockOBDal.createCriteria(Preference.class)).thenReturn(mockPreferenceCriteria);
    when(mockPreferenceCriteria.add(any(Criterion.class))).thenReturn(mockPreferenceCriteria);
    when(mockPreferenceCriteria.list()).thenReturn(Collections.singletonList(fallbackPreference));
    when(fallbackPreference.isSelected()).thenReturn(false);
    when(fallbackPreference.getSearchKey()).thenReturn("https://fallback.example/process");

    String url = ReceiverHttpClient.getReceiverUrlFromPreference();

    assertEquals("https://fallback.example/process", url);
  }

  @Test
  public void testGetReceiverUrlFromPreferenceReturnsNullWhenLookupFails() {
    when(mockOBDal.createCriteria(Preference.class)).thenReturn(mockPreferenceCriteria);
    when(mockPreferenceCriteria.add(any(Criterion.class))).thenReturn(mockPreferenceCriteria);
    when(mockPreferenceCriteria.list()).thenReturn(Collections.emptyList());

    String url = ReceiverHttpClient.getReceiverUrlFromPreference();

    assertEquals(null, url);
  }

  private AnalyticsExporterConfigService.EffectiveConfig buildConfig(int retries, int delayMs) {
    AnalyticsExporterConfigService.EffectiveConfig config = new AnalyticsExporterConfigService.EffectiveConfig();
    config.setReceiverUrl("http://unused");
    config.setInitialExportDays(7);
    config.setChunkDays(1);
    config.setMaxRetries(retries);
    config.setRetryDelayMs(delayMs);
    config.setConnectTimeoutMs(2000);
    config.setReadTimeoutMs(2000);
    config.setDetailedLoggingEnabled(false);
    return config;
  }

  private HttpServer createServer(HttpHandler handler) throws IOException {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext("/process", handler);
    server.start();
    return server;
  }

  private String serverUrl(HttpServer server) {
    return "http://localhost:" + server.getAddress().getPort() + "/process";
  }

  private void writeResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
    exchange.sendResponseHeaders(statusCode, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
