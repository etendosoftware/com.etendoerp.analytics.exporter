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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.erpCommon.utility.SystemInfo;
import org.openbravo.model.ad.access.Session;
import org.openbravo.model.ad.access.SessionUsageAudit;
import org.openbravo.model.ad.domain.Preference;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.analytics.exporter.data.AnalyticsPayload;

import com.etendoerp.analytics.exporter.BaseAnalyticsTest;
import com.etendoerp.analytics.exporter.data.AnalyticsSync;

/**
 * Unit tests for AnalyticsSyncService
 * Tests orchestration, JSON building, and state persistence
 */
@ExtendWith(MockitoExtension.class)
public class AnalyticsSyncServiceTest extends BaseAnalyticsTest {

  public static final String TEST_INSTANCE = "test-instance";
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

  @Mock
  private OBCriteria<Preference> mockPreferenceCriteria;

  private MockedStatic<SystemInfo> mockedSystemInfo;
  private MockedStatic<OBProvider> mockedProvider;

  /**
   * Sets up test fixtures and mocks before each test execution.
   */
  @BeforeEach
  public void setUp() {
    // Mock static methods FIRST, before creating service
    mockedSystemInfo = mockStatic(SystemInfo.class);
    mockedProvider = mockStatic(OBProvider.class);

    // Mock OBDal.createCriteria for Preference to avoid NPE in ReceiverHttpClient constructor
    setupLenientCriteriaMock(mockPreferenceCriteria);
    lenient().when(mockOBDal.createCriteria(Preference.class)).thenReturn(mockPreferenceCriteria);
    Preference mockPreference = mock(Preference.class);
    lenient().when(mockPreference.isSelected()).thenReturn(false);
    lenient().when(mockPreference.getSearchKey()).thenReturn("http://test-receiver.com/process");
    lenient().when(mockPreferenceCriteria.list()).thenReturn(List.of(mockPreference));

    // Mock SystemInfo to provide a default identifier (ActivationKey will fail but is handled)
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenReturn("test-instance-id");

    // NOW create the service after all mocks are configured
    service = new AnalyticsSyncService();
  }

  private void injectField(String fieldName, Object value) {
    try {
      Field field = AnalyticsSyncService.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(service, value);
    } catch (Exception e) {
      throw new AssertionError(REFLECTION_FAILED, e);
    }
  }

  private AnalyticsPayload createPayload(int sessionsCount, int auditsCount) {
    AnalyticsPayload payload = new AnalyticsPayload();
    payload.getMetadata().setSourceInstance(TEST_INSTANCE);
    payload.getMetadata().setExportTimestamp("2026-04-27T00:00:00.000000Z");

    for (int i = 0; i < sessionsCount; i++) {
      Session session = mock(Session.class);
      when(session.getId()).thenReturn("session-" + i);
      when(session.getUsername()).thenReturn("user-" + i);
      when(session.getCreationDate()).thenReturn(new Date());
      when(session.isSessionActive()).thenReturn(false);
      when(session.getLoginStatus()).thenReturn("S");
      when(session.getLastPing()).thenReturn(new Date());
      payload.getSessions().add(session);
    }

    for (int i = 0; i < auditsCount; i++) {
      SessionUsageAudit audit = mock(SessionUsageAudit.class);
      when(audit.getId()).thenReturn("audit-" + i);
      when(audit.getSession()).thenReturn(payload.getSessions().isEmpty() ? null : payload.getSessions().get(0));
      when(audit.getCommand()).thenReturn("DEFAULT");
      when(audit.getCreationDate()).thenReturn(new Date());
      when(audit.getObject()).thenReturn("process-" + i);
      payload.getUsageAudits().add(audit);
    }

    return payload;
  }

  /**
   * Cleans up mocked static objects after each test execution.
   */
  @AfterEach
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
    mockedContext.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
    mockedContext.verify(OBContext::restorePreviousMode, atLeastOnce());
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
   * Tests chunked first sync execution for session usage audits.
   *
   * @throws Exception
   *     if service execution or reflection-based injection fails
   */
  @Test
  public void testExecuteSyncChunksFirstSyncAndAggregatesResults() throws Exception {
    injectField("extractionService", mockExtractionService);
    injectField("httpClient", mockHttpClient);

    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    setupLenientCriteriaMock(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(Collections.emptyList());

    OBProvider providerInstance = mock(OBProvider.class);
    AnalyticsSync syncRecord = mock(AnalyticsSync.class);
    mockedProvider.when(OBProvider::getInstance).thenReturn(providerInstance);
    when(providerInstance.get(AnalyticsSync.class)).thenReturn(syncRecord);
    when(mockOBDal.get(Client.class, "0")).thenReturn(mock(Client.class));
    when(mockOBDal.get(Organization.class, "0")).thenReturn(mock(Organization.class));
    when(mockOBDal.get(Module.class, "0")).thenReturn(null);
    when(mockOBDal.get(Process.class, "process-0")).thenReturn(null);

    ReceiverHttpClient.ReceiverResponse response = new ReceiverHttpClient.ReceiverResponse();
    response.setJobId("chunk-job-1");
    when(mockHttpClient.sendPayload(anyString())).thenReturn(response);

    AnalyticsPayload payloadWithData = createPayload(1, 1);
    AnalyticsPayload emptyPayload = createPayload(0, 0);
    when(mockExtractionService.extractAnalyticsDataForWindow(anyString(), any(Timestamp.class), any(Timestamp.class), any()))
        .thenReturn(payloadWithData, emptyPayload, emptyPayload, emptyPayload, emptyPayload, emptyPayload, emptyPayload);

    AnalyticsSyncService.SyncResult result = service.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS);

    assertEquals(SUCCESS, result.getStatus());
    assertEquals(1, result.getSessionsCount());
    assertEquals(1, result.getAuditsCount());
    assertEquals("chunk-job-1", result.getJobId());
    assertTrue(result.getMessage().contains("1 chunk"));
    verify(mockHttpClient, times(1)).sendPayload(anyString());
    verify(mockExtractionService, times(7)).extractAnalyticsDataForWindow(anyString(), any(Timestamp.class), any(Timestamp.class), any());
    verify(mockOBDal, times(1)).save(syncRecord);
    verify(mockOBDal, times(1)).flush();
  }

  /**
   * Tests chunked sync when all windows are empty.
   *
   * @throws Exception
   *     if service execution or reflection-based injection fails
   */
  @Test
  public void testExecuteSyncChunksWithNoData() throws Exception {
    injectField("extractionService", mockExtractionService);
    injectField("httpClient", mockHttpClient);

    lenient().when(mockOBDal.createCriteria(AnalyticsSync.class)).thenReturn(mockSyncCriteria);
    setupLenientCriteriaMock(mockSyncCriteria);
    lenient().when(mockSyncCriteria.list()).thenReturn(Collections.emptyList());

    AnalyticsPayload emptyPayload = createPayload(0, 0);
    when(mockExtractionService.extractAnalyticsDataForWindow(anyString(), any(Timestamp.class), any(Timestamp.class), any()))
        .thenReturn(emptyPayload, emptyPayload, emptyPayload, emptyPayload, emptyPayload, emptyPayload, emptyPayload);

    AnalyticsSyncService.SyncResult result = service.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS);

    assertEquals(SUCCESS, result.getStatus());
    assertEquals(0, result.getSessionsCount());
    assertEquals(0, result.getAuditsCount());
    assertTrue(result.getMessage().contains("No new data"));
    verify(mockHttpClient, times(0)).sendPayload(anyString());
    verify(mockExtractionService, times(7)).extractAnalyticsDataForWindow(anyString(), any(Timestamp.class), any(Timestamp.class), any());
  }

  /**
   * Tests timestamp formatting with a valid date.
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
   */
  @Test
  public void testGetInstanceNameSuccess() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenReturn("test-instance-123");

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_INSTANCE_NAME);
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("test-instance-123", result);

    mockedContext.verify(() -> OBContext.setAdminMode(true), atLeastOnce());
    mockedContext.verify(OBContext::restorePreviousMode, atLeastOnce());
  }

  /**
   * Tests instance name retrieval when system identifier is empty.
   *
   * @throws Exception
   *     if reflection fails
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
   *
   * @throws Exception
   *     if reflection fails
   */
  @Test
  public void testGetInstanceNameWithException() throws Exception {
    mockedSystemInfo.when(SystemInfo::getSystemIdentifier).thenThrow(new RuntimeException("DB error"));

    java.lang.reflect.Method method = AnalyticsSyncService.class.getDeclaredMethod(GET_INSTANCE_NAME);
    method.setAccessible(true);

    String result = (String) method.invoke(service);
    assertEquals("", result); // Returns empty on exception

    mockedContext.verify(OBContext::restorePreviousMode, atLeastOnce());
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
