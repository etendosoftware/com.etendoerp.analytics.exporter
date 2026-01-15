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
import org.openbravo.erpCommon.utility.SystemInfo;
import org.openbravo.model.ad.system.Client;

import com.etendoerp.analytics.exporter.BaseAnalyticsTest;
import com.etendoerp.analytics.exporter.data.AnalyticsSync;

/**
 * Unit tests for AnalyticsSyncService
 * Tests orchestration, JSON building, and state persistence
 */
@RunWith(MockitoJUnitRunner.class)
public class AnalyticsSyncServiceTest extends BaseAnalyticsTest {

  public static final String JOB_123 = "job-123";
  public static final String FAILED = "FAILED";
  public static final String MAP_LOGIN_STATUS = "mapLoginStatus";
  public static final String SUCCESS = "SUCCESS";
  public static final String GET_INSTANCE_NAME = "getInstanceName";
  public static final String GET_SYNC_STATE = "getSyncState";
  public static final String REFLECTION_FAILED = "Reflection failed";
  private AnalyticsSyncService service;

  @Mock
  private DataExtractionService mockExtractionService;

  @Mock
  private ReceiverHttpClient mockHttpClient;

  @Mock
  private OBCriteria<AnalyticsSync> mockSyncCriteria;

  @Mock
  private OBCriteria<Client> mockClientCriteria;

  private MockedStatic<SystemInfo> mockedSystemInfo;
  private MockedStatic<OBProvider> mockedProvider;

  /**
   * Sets up test fixtures and mocks before each test execution.
   */
  @Before
  public void setUp() {
    service = new AnalyticsSyncService();

    // Mock static methods
    mockedSystemInfo = mockStatic(SystemInfo.class);
    mockedProvider = mockStatic(OBProvider.class);
  }

  /**
   * Cleans up mocked static objects after each test execution.
   */
  @After
  public void tearDown() {
    if (mockedSystemInfo != null) {
      mockedSystemInfo.close();
    }
    if (mockedProvider != null) {
      mockedProvider.close();
    }
  }

  /**
   * Tests that service can be instantiated with default constructor.
   */
  @Test
  public void testConstructorDefault() {
    AnalyticsSyncService newService = new AnalyticsSyncService();
    assertNotNull(newService);
  }

  /**
   * Tests that service can be instantiated with custom receiver URL.
   */
  @Test
  public void testConstructorWithUrl() {
    AnalyticsSyncService newService = new AnalyticsSyncService("http://test.com");
    assertNotNull(newService);
  }

  /**
   * Tests that sync type constants have the expected values.
   */
  @Test
  public void testSyncTypeConstants() {
    assertEquals("SESSION_USAGE_AUDITS", AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS);
    assertEquals("MODULE_METADATA", AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA);
  }

  /**
   * Tests getters and setters for SyncResult inner class.
   */
  @Test
  public void testSyncResultGettersAndSetters() {
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();

    Timestamp start = Timestamp.from(Instant.now());
    Timestamp end = Timestamp.from(Instant.now().plusSeconds(10));
    Exception ex = new Exception("test");

    result.setStartTime(start);
    result.setEndTime(end);
    result.setStatus(SUCCESS);
    result.setMessage("Test message");
    result.setJobId(JOB_123);
    result.setSessionsCount(10);
    result.setAuditsCount(20);
    result.setModulesCount(5);
    result.setError(ex);

    assertEquals(start, result.getStartTime());
    assertEquals(end, result.getEndTime());
    assertEquals(SUCCESS, result.getStatus());
    assertEquals("Test message", result.getMessage());
    assertEquals(JOB_123, result.getJobId());
    assertEquals(10, result.getSessionsCount());
    assertEquals(20, result.getAuditsCount());
    assertEquals(5, result.getModulesCount());
    assertEquals(ex, result.getError());
  }

  /**
   * Tests getters and setters for SyncState inner class.
   */
  @Test
  public void testSyncStateGettersAndSetters() {
    AnalyticsSyncService.SyncState state = new AnalyticsSyncService.SyncState();

    Timestamp ts = Timestamp.from(Instant.now());

    state.setLastSyncTimestamp(ts);
    state.setLastJobId("job-456");
    state.setLastStatus(SUCCESS);
    state.setLog("Test log");

    assertEquals(ts, state.getLastSyncTimestamp());
    assertEquals("job-456", state.getLastJobId());
    assertEquals(SUCCESS, state.getLastStatus());
    assertEquals("Test log", state.getLog());
  }

  /**
   * Tests health status retrieval when no sync data exists.
   */
  @Test
  public void testGetHealthStatusWithNoData() {
    // Setup mocks
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    setupLenientCriteriaMock(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(new ArrayList<>());

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNull(result);

    // Verify admin mode was set and restored
    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests health status retrieval when sync data is available.
   */
  @Test
  public void testGetHealthStatusWithData() {
    // Setup mock data
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    Date syncDate = new Date();
    when(mockSync.getLastSync()).thenReturn(syncDate);
    when(mockSync.getLastStatus()).thenReturn(SUCCESS);
    when(mockSync.getLog()).thenReturn("Job ID: job-789\nSuccess");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Setup criteria
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    setupLenientCriteriaMock(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(results);

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNotNull(result);
    assertNotNull(result.getLastSyncTimestamp());
    assertEquals(SUCCESS, result.getLastStatus());
    assertEquals("job-789", result.getLastJobId());
  }

  /**
   * Tests health status when last sync timestamp is null.
   */
  @Test
  public void testGetHealthStatusWithNullLastSync() {
    // Setup mock data with null lastSync
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLastSync()).thenReturn(null);
    when(mockSync.getLastStatus()).thenReturn(FAILED);
    when(mockSync.getLog()).thenReturn("Error occurred");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Setup criteria
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    setupLenientCriteriaMock(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(results);

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNotNull(result);
    assertNull(result.getLastSyncTimestamp());
    assertEquals(FAILED, result.getLastStatus());
  }

  /**
   * Tests health status when log does not contain a job ID.
   */
  @Test
  public void testGetHealthStatusWithLogWithoutJobId() {
    // Setup mock data without Job ID in log
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn(SUCCESS);
    when(mockSync.getLog()).thenReturn("Completed successfully");

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Setup criteria
    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    setupLenientCriteriaMock(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(results);

    // Execute
    AnalyticsSyncService.SyncState result = service.getHealthStatus();

    // Verify
    assertNotNull(result);
    assertNull(result.getLastJobId()); // No Job ID in log
  }

  /**
   * Tests that admin mode is restored even when exception occurs during health status check.
   */
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

  /**
   * Tests timestamp formatting with a valid date.
   * @throws Exception if reflection fails
   */
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

  /**
   * Tests timestamp formatting with null date.
   * @throws Exception if reflection fails
   */
  @Test
  public void testFormatTimestampWithNull() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod("formatTimestamp",
        java.util.Date.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, (Date) null);
    assertNull(result);
  }

  /**
   * Tests login status mapping for successful login.
   * @throws Exception if reflection fails
   */
  @Test
  public void testMapLoginStatusSuccess() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(MAP_LOGIN_STATUS, String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "S");
    assertEquals(SUCCESS, result);
  }

  /**
   * Tests login status mapping for failed login.
   * @throws Exception if reflection fails
   */
  @Test
  public void testMapLoginStatusFailed() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(MAP_LOGIN_STATUS, String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "F");
    assertEquals(FAILED, result);
  }

  /**
   * Tests login status mapping for locked account.
   * @throws Exception if reflection fails
   */
  @Test
  public void testMapLoginStatusLocked() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(MAP_LOGIN_STATUS, String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "L");
    assertEquals("LOCKED", result);
  }

  /**
   * Tests login status mapping for unknown status code.
   * @throws Exception if reflection fails
   */
  @Test
  public void testMapLoginStatusUnknown() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(MAP_LOGIN_STATUS, String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, "X");
    assertEquals("X", result); // Returns as-is for unknown
  }

  /**
   * Tests login status mapping with null status.
   * @throws Exception if reflection fails
   */
  @Test
  public void testMapLoginStatusNull() throws Exception {
    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(MAP_LOGIN_STATUS, String.class);
    method.setAccessible(true);

    String result = (String) method.invoke(service, (String) null);
    assertEquals("UNKNOWN", result);
  }

  /**
   * Tests successful retrieval of instance name.
   * @throws Exception if reflection fails
   */
  @Test
  public void testGetInstanceNameSuccess() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenReturn("test-instance-123");

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_INSTANCE_NAME);
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("test-instance-123", result);

    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests instance name retrieval when system identifier is empty.
   * @throws Exception if reflection fails
   */
  @Test
  public void testGetInstanceNameWithEmptyIdentifier() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenReturn("");

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_INSTANCE_NAME);
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("", result);
  }

  /**
   * Tests instance name retrieval when an exception occurs.
   * @throws Exception if reflection fails
   */
  @Test
  public void testGetInstanceNameWithException() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenThrow(new RuntimeException("DB error"));

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_INSTANCE_NAME);
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("", result); // Returns empty on exception

    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests job ID extraction from sync log.
   */
  @Test
  public void testJobIdExtractionFromLog() {
    // Test SyncState with job ID in log
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLog()).thenReturn("Job ID: job-123\nSessions: 5\nSuccess");
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn(SUCCESS);

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    // Use reflection to call getSyncState
    try {
      java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_SYNC_STATE, List.class);
      method.setAccessible(true);

      AnalyticsSyncService.SyncState state = (AnalyticsSyncService.SyncState) method.invoke(null, results);

      assertEquals(JOB_123, state.getLastJobId());
    } catch (Exception e) {
      throw new AssertionError(REFLECTION_FAILED, e);
    }
  }

  /**
   * Tests job ID extraction when log contains N/A value.
   */
  @Test
  public void testJobIdExtractionWithNA() {
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLog()).thenReturn("Job ID: N/A\nFailed");
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn(FAILED);

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    try {
      java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_SYNC_STATE, List.class);
      method.setAccessible(true);

      AnalyticsSyncService.SyncState state = (AnalyticsSyncService.SyncState) method.invoke(null, results);

      assertNull(state.getLastJobId()); // N/A should result in null
    } catch (Exception e) {
      throw new AssertionError(REFLECTION_FAILED, e);
    }
  }

  /**
   * Tests job ID extraction when log does not contain job ID.
   */
  @Test
  public void testJobIdExtractionWithoutJobId() {
    AnalyticsSync mockSync = mock(AnalyticsSync.class);
    when(mockSync.getLog()).thenReturn("Some other log message");
    when(mockSync.getLastSync()).thenReturn(new Date());
    when(mockSync.getLastStatus()).thenReturn(SUCCESS);

    List<AnalyticsSync> results = new ArrayList<>();
    results.add(mockSync);

    try {
      java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_SYNC_STATE, List.class);
      method.setAccessible(true);

      AnalyticsSyncService.SyncState state = (AnalyticsSyncService.SyncState) method.invoke(null, results);

      assertNull(state.getLastJobId()); // No Job ID in log
    } catch (Exception e) {
      throw new AssertionError(REFLECTION_FAILED, e);
    }
  }
}
