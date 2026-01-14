package com.etendoerp.analytics.exporter.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.utility.SystemInfo;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.analytics.exporter.data.AnalyticsSync;

/**
 * Unit tests for AnalyticsSyncService
 * Tests orchestration, JSON building, and state persistence
 */
@RunWith(MockitoJUnitRunner.class)
public class AnalyticsSyncServiceTest {

  private AnalyticsSyncService service;

  @Mock
  private DataExtractionService mockExtractionService;

  @Mock
  private ReceiverHttpClient mockHttpClient;

  @Mock
  private OBDal mockOBDal;

  @Mock
  private OBCriteria<AnalyticsSync> mockSyncCriteria;

  @Mock
  private OBCriteria<Client> mockClientCriteria;

  private MockedStatic<OBContext> mockedContext;
  private MockedStatic<OBDal> mockedDal;
  private MockedStatic<SystemInfo> mockedSystemInfo;
  private MockedStatic<OBProvider> mockedProvider;

  @Before
  public void setUp() {
    service = new AnalyticsSyncService();

    // Mock static methods
    mockedContext = mockStatic(OBContext.class);
    mockedDal = mockStatic(OBDal.class);
    mockedSystemInfo = mockStatic(SystemInfo.class);
    mockedProvider = mockStatic(OBProvider.class);

    mockedDal.when(OBDal::getInstance).thenReturn(mockOBDal);
  }

  @After
  public void tearDown() {
    if (mockedContext != null) {
      mockedContext.close();
    }
    if (mockedDal != null) {
      mockedDal.close();
    }
    if (mockedSystemInfo != null) {
      mockedSystemInfo.close();
    }
    if (mockedProvider != null) {
      mockedProvider.close();
    }
  }

  @Test
  public void testConstructorDefault() {
    AnalyticsSyncService newService = new AnalyticsSyncService();
    assertNotNull(newService);
  }

  @Test
  public void testConstructorWithUrl() {
    AnalyticsSyncService newService = new AnalyticsSyncService("http://test.com");
    assertNotNull(newService);
  }

  @Test
  public void testSyncTypeConstants() {
    assertEquals("SESSION_USAGE_AUDITS", AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS);
    assertEquals("MODULE_METADATA", AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA);
  }

  @Test
  public void testSyncResultGettersAndSetters() {
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();

    Timestamp start = Timestamp.from(Instant.now());
    Timestamp end = Timestamp.from(Instant.now().plusSeconds(10));
    Exception ex = new Exception("test");

    result.setStartTime(start);
    result.setEndTime(end);
    result.setStatus("SUCCESS");
    result.setMessage("Test message");
    result.setJobId("job-123");
    result.setSessionsCount(10);
    result.setAuditsCount(20);
    result.setModulesCount(5);
    result.setError(ex);

    assertEquals(start, result.getStartTime());
    assertEquals(end, result.getEndTime());
    assertEquals("SUCCESS", result.getStatus());
    assertEquals("Test message", result.getMessage());
    assertEquals("job-123", result.getJobId());
    assertEquals(10, result.getSessionsCount());
    assertEquals(20, result.getAuditsCount());
    assertEquals(5, result.getModulesCount());
    assertEquals(ex, result.getError());
  }

  @Test
  public void testSyncStateGettersAndSetters() {
    AnalyticsSyncService.SyncState state = new AnalyticsSyncService.SyncState();

    Timestamp ts = Timestamp.from(Instant.now());

    state.setLastSyncTimestamp(ts);
    state.setLastJobId("job-456");
    state.setLastStatus("SUCCESS");
    state.setLog("Test log");

    assertEquals(ts, state.getLastSyncTimestamp());
    assertEquals("job-456", state.getLastJobId());
    assertEquals("SUCCESS", state.getLastStatus());
    assertEquals("Test log", state.getLog());
  }

  @Test
  public void testGetHealthStatusWithNoData() {
    // Setup mocks - using lenient to avoid unnecessary stubbing errors
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.add(any(Criterion.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(new ArrayList<>());

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNull(result);

    // Verify admin mode was set and restored
    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testGetHealthStatusWithData() {
    // Setup mock data
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    Date syncDate = new Date();
    when(mockSync.getLastSync()).thenReturn(syncDate);
    when(mockSync.getLastStatus()).thenReturn("SUCCESS");
    when(mockSync.getLog()).thenReturn("Job ID: job-789\nSuccess");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Setup criteria - using lenient to avoid unnecessary stubbing warnings
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.add(any(Criterion.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.addOrderBy(anyString(), any(Boolean.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.setMaxResults(any(Integer.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(results);

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNotNull(result);
    assertNotNull(result.getLastSyncTimestamp());
    assertEquals("SUCCESS", result.getLastStatus());
    assertEquals("job-789", result.getLastJobId());
  }

  @Test
  public void testGetHealthStatusWithNullLastSync() {
    // Setup mock data with null lastSync
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLastSync()).thenReturn(null);
    when(mockSync.getLastStatus()).thenReturn("FAILED");
    when(mockSync.getLog()).thenReturn("Error occurred");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Setup criteria - using lenient to avoid unnecessary stubbing warnings
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.add(any(Criterion.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.addOrderBy(anyString(), any(Boolean.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.setMaxResults(any(Integer.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(results);

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNotNull(result);
    assertNull(result.getLastSyncTimestamp());
    assertEquals("FAILED", result.getLastStatus());
  }

  @Test
  public void testGetHealthStatusWithLogWithoutJobId() {
    // Setup mock data without Job ID in log
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn("SUCCESS");
    when(mockSync.getLog()).thenReturn("Completed successfully");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Setup criteria - using lenient to avoid unnecessary stubbing warnings
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.add(any(Criterion.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.addOrderBy(anyString(), any(Boolean.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.setMaxResults(any(Integer.class))).thenReturn(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(results);

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNotNull(result);
    assertNull(result.getLastJobId()); // No Job ID in log
  }

  @Test
  public void testGetHealthStatusRestoresPreviousModeOnException() {
    // Setup mock to throw exception
    when(mockOBDal.createCriteria(AnalyticsSync.class)).thenThrow(new RuntimeException("Test error"));

    try {
      service.getHealthStatus();
    } catch (Exception e) {
      // Expected
    }

    // Verify previous mode was restored
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testFormatTimestampWithValidDate() throws Exception {
    // Use reflection to access private method
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("formatTimestamp",
        java.util.Date.class);
    method.setAccessible(true);

    Date testDate = new Date(1705228800000L); // 2024-01-14T08:00:00Z
    String result = (String) method.invoke(service, testDate);

    assertNotNull(result);
    assertTrue(result.contains("T"));
    assertTrue(result.contains("Z") || result.contains("+") || result.contains("-"));
  }

  @Test
  public void testFormatTimestampWithNull() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("formatTimestamp",
        java.util.Date.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, (Date) null);
    assertNull(result);
  }

  @Test
  public void testMapLoginStatusSuccess() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("mapLoginStatus", String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "S");
    assertEquals("SUCCESS", result);
  }

  @Test
  public void testMapLoginStatusFailed() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("mapLoginStatus", String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "F");
    assertEquals("FAILED", result);
  }

  @Test
  public void testMapLoginStatusLocked() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("mapLoginStatus", String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "L");
    assertEquals("LOCKED", result);
  }

  @Test
  public void testMapLoginStatusUnknown() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("mapLoginStatus", String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "X");
    assertEquals("X", result); // Returns as-is for unknown
  }

  @Test
  public void testMapLoginStatusNull() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("mapLoginStatus", String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, (String) null);
    assertEquals("UNKNOWN", result);
  }

  @Test
  public void testGetInstanceNameSuccess() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenReturn("test-instance-123");

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("getInstanceName");
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("test-instance-123", result);

    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testGetInstanceNameWithEmptyIdentifier() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenReturn("");

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("getInstanceName");
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("", result);
  }

  @Test
  public void testGetInstanceNameWithException() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenThrow(new RuntimeException("DB error"));

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("getInstanceName");
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("", result); // Returns empty on exception

    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testJobIdExtractionFromLog() {
    // Test SyncState with job ID in log
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLog()).thenReturn("Job ID: job-123\nSessions: 5\nSuccess");
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn("SUCCESS");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Use reflection to call getSyncState
    try {
      java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("getSyncState", List.class);
      method.setAccessible(true);

      AnalyticsSyncService.SyncState state = (AnalyticsSyncService.SyncState) method.invoke(null, results);

      assertEquals("job-123", state.getLastJobId());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testJobIdExtractionWithNA() {
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLog()).thenReturn("Job ID: N/A\nFailed");
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn("FAILED");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    try {
      java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("getSyncState", List.class);
      method.setAccessible(true);

      AnalyticsSyncService.SyncState state = (AnalyticsSyncService.SyncState) method.invoke(null, results);

      assertNull(state.getLastJobId()); // N/A should result in null
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testJobIdExtractionWithoutJobId() {
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLog()).thenReturn("Some other log message");
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn("SUCCESS");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    try {
      java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("getSyncState", List.class);
      method.setAccessible(true);

      AnalyticsSyncService.SyncState state = (AnalyticsSyncService.SyncState) method.invoke(null, results);

      assertNull(state.getLastJobId()); // No Job ID in log
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
