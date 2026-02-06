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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.dal.core.OBContext;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessLogger;
import org.quartz.JobExecutionException;

import com.etendoerp.analytics.exporter.BaseAnalyticsTest;
import com.etendoerp.analytics.exporter.service.AnalyticsSyncService;

/**
 * Unit tests for AnalyticsSyncProcess
 * Tests orchestration of sync types and error handling
 */
@ExtendWith(MockitoExtension.class)
public class AnalyticsSyncProcessTest extends BaseAnalyticsTest {

  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";
  private AnalyticsSyncProcess process;

  @Mock
  private ProcessBundle mockBundle;

  @Mock
  private ProcessLogger mockLogger;

  private MockedConstruction<AnalyticsSyncService> mockedService;

  /**
   * Sets up test fixtures and mocks before each test execution.
   */
  @BeforeEach
  public void setUp() {
    process = new AnalyticsSyncProcess();

    // Setup logger
    lenient().when(mockBundle.getLogger()).thenReturn(mockLogger);
  }

  /**
   * Cleans up mocked service construction after each test execution.
   */
  @AfterEach
  public void tearDown() {
    if (mockedService != null) {
      mockedService.close();
    }
  }

  /**
   * Helper method to create a successful SyncResult for session/usage audits.
   */
  private AnalyticsSyncService.SyncResult createSuccessfulSessionResult(int sessions, int audits) {
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();
    result.setStatus(SUCCESS);
    result.setSessionsCount(sessions);
    result.setAuditsCount(audits);
    return result;
  }

  /**
   * Helper method to create a successful SyncResult for module metadata.
   */
  private AnalyticsSyncService.SyncResult createSuccessfulModuleResult(int modules) {
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();
    result.setStatus(SUCCESS);
    result.setModulesCount(modules);
    return result;
  }

  /**
   * Helper method to create a failed SyncResult with an error message.
   */
  private AnalyticsSyncService.SyncResult createFailedResult(String errorMessage) {
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();
    result.setStatus(FAILED);
    result.setMessage(errorMessage);
    result.setError(new RuntimeException(errorMessage));
    return result;
  }

  /**
   * Tests successful execution of sync process with both sync types.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteSuccessfulSync() throws Exception {
    // Mock successful sync results
    AnalyticsSyncService.SyncResult sessionUsageResult = createSuccessfulSessionResult(10, 20);
    AnalyticsSyncService.SyncResult metadataResult = createSuccessfulModuleResult(5);

    // Mock AnalyticsSyncService
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS))
          .thenReturn(sessionUsageResult);
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA))
          .thenReturn(metadataResult);
    });

    // Execute
    process.execute(mockBundle);

    // Verify admin mode
    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));

    // Verify commit
    verify(mockOBDal).commitAndClose();

    // Verify logger was called (process logs multiple messages)
    verify(mockLogger, atLeast(2)).log(anyString());
  }

  /**
   * Tests execution when one sync type succeeds and the other fails.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteWithPartialFailure() throws Exception {
    // Mock mixed results
    AnalyticsSyncService.SyncResult sessionUsageResult = createSuccessfulSessionResult(10, 20);
    AnalyticsSyncService.SyncResult metadataResult = createFailedResult("Module sync failed");

    // Mock AnalyticsSyncService
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS))
          .thenReturn(sessionUsageResult);
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA))
          .thenReturn(metadataResult);
    });

    // Execute - should throw JobExecutionException
    assertThrows(JobExecutionException.class, () -> process.execute(mockBundle));
  }

  /**
   * Tests execution when both sync types fail.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteWithBothFailures() throws Exception {
    // Mock failed results
    AnalyticsSyncService.SyncResult sessionUsageResult = createFailedResult("Database error");
    AnalyticsSyncService.SyncResult metadataResult = createFailedResult("Network error");

    // Mock AnalyticsSyncService
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS))
          .thenReturn(sessionUsageResult);
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA))
          .thenReturn(metadataResult);
    });

    // Execute - should throw exception
    assertThrows(JobExecutionException.class, () -> process.execute(mockBundle));
  }

  /**
   * Tests that runtime exceptions are properly wrapped in JobExecutionException.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteWithException() throws Exception {
    // Mock service throwing exception
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenThrow(new RuntimeException("Critical error"));
    });

    // Execute - should wrap in JobExecutionException
    assertThrows(JobExecutionException.class, () -> process.execute(mockBundle));
  }

  /**
   * Tests that admin mode is properly restored after successful execution.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteRestoresModeOnSuccess() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult sessionUsageResult = createSuccessfulSessionResult(0, 0);
    AnalyticsSyncService.SyncResult metadataResult = createSuccessfulModuleResult(0);

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(sessionUsageResult, metadataResult);
    });

    // Execute
    process.execute(mockBundle);

    // Verify mode restored in finally block
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests that admin mode is properly restored even when an exception occurs.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteRestoresModeOnException() throws Exception {
    // Mock exception
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenThrow(new RuntimeException("Test error"));
    });

    try {
      process.execute(mockBundle);
    } catch (JobExecutionException e) {
      // Expected
    }

    // Verify mode restored even on exception
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests that database changes are committed after successful execution.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteCommitsAndClosesOnSuccess() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult sessionUsageResult = createSuccessfulSessionResult(0, 0);
    AnalyticsSyncService.SyncResult metadataResult = createSuccessfulModuleResult(0);

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(sessionUsageResult, metadataResult);
    });

    // Execute
    process.execute(mockBundle);

    // Verify commit in finally block
    verify(mockOBDal).commitAndClose();
  }

  /**
   * Tests that database changes are committed even when an exception occurs.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testExecuteCommitsAndClosesOnException() throws Exception {
    // Mock exception
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenThrow(new RuntimeException("Test error"));
    });

    try {
      process.execute(mockBundle);
    } catch (JobExecutionException e) {
      // Expected
    }

    // Verify commit even on exception
    verify(mockOBDal).commitAndClose();
  }

  /**
   * Tests that process constants have the expected values.
   */
  @Test
  public void testConstants() {
    assertEquals("==========================================", AnalyticsSyncProcess.SEPARATOR);
    assertEquals("------------------------------------------", AnalyticsSyncProcess.SEPARATOR_MIDDLE);
    assertEquals(SUCCESS, AnalyticsSyncProcess.SUCCESS);
  }

  /**
   * Tests that the process logger is properly set on the sync service.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testSetProcessLoggerOnService() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult result = createSuccessfulSessionResult(0, 0);

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(result);
    });

    // Execute
    process.execute(mockBundle);

    // Verify setProcessLogger was called on service
    AnalyticsSyncService service = mockedService.constructed().get(0);
    verify(service).setProcessLogger(mockLogger);
  }

  /**
   * Tests that summary information is properly logged during execution.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testLogsSummaryInformation() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult sessionUsageResult = createSuccessfulSessionResult(15, 30);
    AnalyticsSyncService.SyncResult metadataResult = createSuccessfulModuleResult(8);

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS))
          .thenReturn(sessionUsageResult);
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA))
          .thenReturn(metadataResult);
    });

    // Execute
    process.execute(mockBundle);

    // Verify logger was called multiple times with different messages
    verify(mockLogger, atLeast(2)).log(anyString()); // At least start and summary
  }

  /**
   * Tests that the default receiver URL is used when none is specified.
   *
   * @throws Exception
   *     if test execution fails
   */
  @Test
  public void testUsesDefaultReceiverUrl() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();
    result.setStatus(SUCCESS);

    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(result);
    });

    // Execute
    process.execute(mockBundle);

    // Verify default constructor was used (no URL parameter)
    assertEquals(1, mockedService.constructed().size());
  }
}
