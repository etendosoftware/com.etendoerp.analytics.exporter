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

package com.etendoerp.analytics.exporter.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.base.secureApp.VariablesSecureApp;

import com.etendoerp.analytics.exporter.service.AnalyticsSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for AnalyticsHealthCheck servlet
 * Tests health check endpoint responses and error handling
 */
@ExtendWith(MockitoExtension.class)
public class AnalyticsHealthCheckTest {

  public static final String LOGGININ = "#LOGGININ";
  public static final String SUCCESS = "SUCCESS";
  public static final String ERROR = "error";
  public static final String HEALTHY = "healthy";
  private AnalyticsHealthCheck servlet;

  @Mock
  private HttpServletRequest mockRequest;

  @Mock
  private HttpServletResponse mockResponse;

  @Mock
  private HttpSession mockSession;

  private StringWriter responseWriter;

  private MockedConstruction<VariablesSecureApp> mockedVars;
  private MockedConstruction<AnalyticsSyncService> mockedService;

  /**
   * Sets up test fixtures and mocks before each test execution.
   * @throws Exception if setup fails
   */
  @BeforeEach
  public void setUp() throws Exception {
    servlet = new AnalyticsHealthCheck();

    // Setup response writer
    responseWriter = new StringWriter();
    PrintWriter printWriter = new PrintWriter(responseWriter);
    lenient().when(mockResponse.getWriter()).thenReturn(printWriter);
  }

  /**
   * Cleans up mocked construction objects after each test execution.
   */
  @AfterEach
  public void tearDown() {
    if (mockedVars != null) {
      mockedVars.close();
    }
    if (mockedService != null) {
      mockedService.close();
    }
  }

  /**
   * Tests health check endpoint returns healthy status with successful sync state.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoGetHealthyStatus() throws Exception {
    // Mock VariablesSecureApp to return logged in
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    // Mock sync state
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastSyncTimestamp(Timestamp.from(Instant.now()));
    mockState.setLastJobId("job-123");
    mockState.setLastStatus(SUCCESS);
    mockState.setLog("Success log");

    // Mock service
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(mockState);
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
    verify(mockResponse).setContentType("application/json");
    verify(mockResponse).setCharacterEncoding("UTF-8");

    // Parse JSON response
    String jsonResponse = responseWriter.toString();
    assertNotNull(jsonResponse);
    assertTrue(jsonResponse.contains(HEALTHY));
    assertTrue(jsonResponse.contains("ok"));
    assertTrue(jsonResponse.contains("job-123"));
  }

  /**
   * Tests health check endpoint returns degraded status when sync fails.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoGetDegradedStatus() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    // Mock sync state with FAILED status
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastSyncTimestamp(Timestamp.from(Instant.now()));
    mockState.setLastJobId("job-456");
    mockState.setLastStatus("FAILED");
    mockState.setLog("Error occurred");

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(mockState);
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_OK);

    // Parse JSON
    String jsonResponse = responseWriter.toString();
    assertTrue(jsonResponse.contains(HEALTHY));
    assertTrue(jsonResponse.contains("degraded"));
    assertTrue(jsonResponse.contains("FAILED"));
  }

  /**
   * Tests health check endpoint returns no_data status when no sync has been performed.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoGetNoDataStatus() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    // Mock service returning null (no sync data)
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(null);
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_OK);

    // Parse JSON
    String jsonResponse = responseWriter.toString();
    assertTrue(jsonResponse.contains("no_data"));
    assertTrue(jsonResponse.contains("unknown"));
    assertTrue(jsonResponse.contains("No synchronization has been performed yet"));
  }

  /**
   * Tests health check endpoint returns unauthorized status for non-logged users.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoGetUnauthorized() throws Exception {
    // Mock VariablesSecureApp returning not logged in
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("N");
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);

    // Parse JSON
    String jsonResponse = responseWriter.toString();
    assertTrue(jsonResponse.contains(ERROR));
    assertTrue(jsonResponse.contains("Unauthorized"));
  }

  /**
   * Tests health check endpoint returns unauthorized when login status is null.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoGetNullLoginStatus() throws Exception {
    // Mock VariablesSecureApp returning null
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn(null);
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify unauthorized response
    verify(mockResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  /**
   * Tests health check endpoint handles exceptions and returns error response.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoGetWithException() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    // Mock service throwing exception
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenThrow(new RuntimeException("Database error"));
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify error response
    verify(mockResponse).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

    // Parse JSON
    String jsonResponse = responseWriter.toString();
    assertTrue(jsonResponse.contains(ERROR));
    assertTrue(jsonResponse.contains("unhealthy"));
    assertTrue(jsonResponse.contains("Database error"));
  }

  /**
   * Tests that POST requests are properly delegated to GET method.
   * @throws Exception if test execution fails
   */
  @Test
  public void testDoPost() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    // Mock service
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastStatus(SUCCESS);

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(mockState);
    });

    // Execute POST (should delegate to GET)
    servlet.doPost(mockRequest, mockResponse);

    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
  }

  /**
   * Tests that the JSON response has the correct format and fields.
   * @throws Exception if test execution fails
   */
  @Test
  public void testJsonResponseFormat() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    // Mock sync state
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastSyncTimestamp(Timestamp.from(Instant.parse("2025-01-15T10:00:00Z")));
    mockState.setLastJobId("job-789");
    mockState.setLastStatus(SUCCESS);
    mockState.setLog("Test log");

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(mockState);
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Parse JSON to verify structure
    String jsonResponse = responseWriter.toString();
    ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    java.util.Map<String, Object> jsonMap = mapper.readValue(jsonResponse, java.util.Map.class);

    assertNotNull(jsonMap);
    assertEquals(HEALTHY, jsonMap.get("status"));
    assertEquals("ok", jsonMap.get("health"));
    assertEquals("job-789", jsonMap.get("last_job_id"));
    assertEquals(SUCCESS, jsonMap.get("last_status"));
    assertEquals("Test log", jsonMap.get("log"));
    assertNotNull(jsonMap.get("last_sync_timestamp"));
  }

  /**
   * Tests that servlet constants have the expected values.
   */
  @Test
  public void testStatusConstants() {
    assertEquals("status", AnalyticsHealthCheck.STATUS);
    assertEquals("health", AnalyticsHealthCheck.HEALTH);
    assertEquals(ERROR, AnalyticsHealthCheck.ERROR);
  }

  /**
   * Tests that serialVersionUID is properly defined.
   */
  @Test
  public void testSerialVersionUID() {
    // Just verify the field exists
    assertEquals(1L, AnalyticsHealthCheck.serialVersionUID);
  }

  /**
   * Tests that response content type and encoding are properly set.
   * @throws Exception if test execution fails
   */
  @Test
  public void testContentTypeAndEncoding() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(null);
    });

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify content type and encoding
    verify(mockResponse).setContentType("application/json");
    verify(mockResponse).setCharacterEncoding("UTF-8");
  }

  /**
   * Tests that the response writer is properly flushed after writing.
   * @throws Exception if test execution fails
   */
  @Test
  public void testWriterFlushed() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue(LOGGININ)).thenReturn("Y");
    });

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(null);
    });

    // Use a mock writer to verify flush
    PrintWriter mockWriter = mock(PrintWriter.class);
    when(mockResponse.getWriter()).thenReturn(mockWriter);

    // Execute
    servlet.doGet(mockRequest, mockResponse);

    // Verify flush was called
    verify(mockWriter).flush();
  }
}
