package com.etendoerp.analytics.exporter.process;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Timestamp;
import java.time.Instant;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.secureApp.VariablesSecureApp;

import com.etendoerp.analytics.exporter.service.AnalyticsSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Unit tests for AnalyticsHealthCheck servlet
 * Tests health check endpoint responses and error handling
 */
@RunWith(MockitoJUnitRunner.class)
public class AnalyticsHealthCheckTest {

  private AnalyticsHealthCheck servlet;
  
  @Mock
  private HttpServletRequest mockRequest;
  
  @Mock
  private HttpServletResponse mockResponse;
  
  @Mock
  private HttpSession mockSession;
  
  private StringWriter responseWriter;
  private PrintWriter printWriter;
  
  private MockedConstruction<VariablesSecureApp> mockedVars;
  private MockedConstruction<AnalyticsSyncService> mockedService;

  @Before
  public void setUp() throws Exception {
    servlet = new AnalyticsHealthCheck();
    
    // Setup response writer
    responseWriter = new StringWriter();
    printWriter = new PrintWriter(responseWriter);
    when(mockResponse.getWriter()).thenReturn(printWriter);
  }

  @After
  public void tearDown() {
    if (mockedVars != null) {
      mockedVars.close();
    }
    if (mockedService != null) {
      mockedService.close();
    }
  }

  @Test
  public void testDoGetHealthyStatus() throws Exception {
    // Mock VariablesSecureApp to return logged in
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
    });
    
    // Mock sync state
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastSyncTimestamp(Timestamp.from(Instant.now()));
    mockState.setLastJobId("job-123");
    mockState.setLastStatus("SUCCESS");
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
    assertTrue(jsonResponse.contains("healthy"));
    assertTrue(jsonResponse.contains("ok"));
    assertTrue(jsonResponse.contains("job-123"));
  }

  @Test
  public void testDoGetDegradedStatus() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
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
    assertTrue(jsonResponse.contains("healthy"));
    assertTrue(jsonResponse.contains("degraded"));
    assertTrue(jsonResponse.contains("FAILED"));
  }

  @Test
  public void testDoGetNoDataStatus() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
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

  @Test
  public void testDoGetUnauthorized() throws Exception {
    // Mock VariablesSecureApp returning not logged in
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("N");
    });
    
    // Execute
    servlet.doGet(mockRequest, mockResponse);
    
    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    
    // Parse JSON
    String jsonResponse = responseWriter.toString();
    assertTrue(jsonResponse.contains("error"));
    assertTrue(jsonResponse.contains("Unauthorized"));
  }

  @Test
  public void testDoGetNullLoginStatus() throws Exception {
    // Mock VariablesSecureApp returning null
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn(null);
    });
    
    // Execute
    servlet.doGet(mockRequest, mockResponse);
    
    // Verify unauthorized response
    verify(mockResponse).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
  }

  @Test
  public void testDoGetWithException() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
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
    assertTrue(jsonResponse.contains("error"));
    assertTrue(jsonResponse.contains("unhealthy"));
    assertTrue(jsonResponse.contains("Database error"));
  }

  @Test
  public void testDoPost() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
    });
    
    // Mock service
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastStatus("SUCCESS");
    
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.getHealthStatus()).thenReturn(mockState);
    });
    
    // Execute POST (should delegate to GET)
    servlet.doPost(mockRequest, mockResponse);
    
    // Verify response
    verify(mockResponse).setStatus(HttpServletResponse.SC_OK);
  }

  @Test
  public void testJsonResponseFormat() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
    });
    
    // Mock sync state
    AnalyticsSyncService.SyncState mockState = new AnalyticsSyncService.SyncState();
    mockState.setLastSyncTimestamp(Timestamp.from(Instant.parse("2025-01-15T10:00:00Z")));
    mockState.setLastJobId("job-789");
    mockState.setLastStatus("SUCCESS");
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
    assertEquals("healthy", jsonMap.get("status"));
    assertEquals("ok", jsonMap.get("health"));
    assertEquals("job-789", jsonMap.get("last_job_id"));
    assertEquals("SUCCESS", jsonMap.get("last_status"));
    assertEquals("Test log", jsonMap.get("log"));
    assertNotNull(jsonMap.get("last_sync_timestamp"));
  }

  @Test
  public void testStatusConstants() {
    assertEquals("status", AnalyticsHealthCheck.STATUS);
    assertEquals("health", AnalyticsHealthCheck.HEALTH);
    assertEquals("error", AnalyticsHealthCheck.ERROR);
  }

  @Test
  public void testSerialVersionUID() {
    // Just verify the field exists
    assertEquals(1L, AnalyticsHealthCheck.serialVersionUID);
  }

  @Test
  public void testContentTypeAndEncoding() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
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

  @Test
  public void testWriterFlushed() throws Exception {
    // Mock VariablesSecureApp
    mockedVars = Mockito.mockConstruction(VariablesSecureApp.class, (mock, context) -> {
      when(mock.getSessionValue("#LOGGININ")).thenReturn("Y");
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
