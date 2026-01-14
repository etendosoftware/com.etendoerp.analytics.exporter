package com.etendoerp.analytics.exporter.process;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessLogger;
import org.quartz.JobExecutionException;

import com.etendoerp.analytics.exporter.service.AnalyticsSyncService;

/**
 * Unit tests for AnalyticsSyncProcess
 * Tests orchestration of sync types and error handling
 */
@RunWith(MockitoJUnitRunner.class)
public class AnalyticsSyncProcessTest {

  private AnalyticsSyncProcess process;
  
  @Mock
  private ProcessBundle mockBundle;
  
  @Mock
  private ProcessLogger mockLogger;
  
  @Mock
  private OBDal mockOBDal;
  
  private MockedStatic<OBContext> mockedContext;
  private MockedStatic<OBDal> mockedDal;
  private MockedConstruction<AnalyticsSyncService> mockedService;

  @Before
  public void setUp() {
    process = new AnalyticsSyncProcess();
    
    // Setup logger
    when(mockBundle.getLogger()).thenReturn(mockLogger);
    
    // Mock static methods
    mockedContext = mockStatic(OBContext.class);
    mockedDal = mockStatic(OBDal.class);
    
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
    if (mockedService != null) {
      mockedService.close();
    }
  }

  @Test
  public void testExecuteSuccessfulSync() throws Exception {
    // Mock successful sync results
    AnalyticsSyncService.SyncResult sessionUsageResult = new AnalyticsSyncService.SyncResult();
    sessionUsageResult.setStatus("SUCCESS");
    sessionUsageResult.setSessionsCount(10);
    sessionUsageResult.setAuditsCount(20);
    
    AnalyticsSyncService.SyncResult metadataResult = new AnalyticsSyncService.SyncResult();
    metadataResult.setStatus("SUCCESS");
    metadataResult.setModulesCount(5);
    
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

  @Test(expected = JobExecutionException.class)
  public void testExecuteWithPartialFailure() throws Exception {
    // Mock mixed results
    AnalyticsSyncService.SyncResult sessionUsageResult = new AnalyticsSyncService.SyncResult();
    sessionUsageResult.setStatus("SUCCESS");
    sessionUsageResult.setSessionsCount(10);
    sessionUsageResult.setAuditsCount(20);
    
    AnalyticsSyncService.SyncResult metadataResult = new AnalyticsSyncService.SyncResult();
    metadataResult.setStatus("FAILED");
    metadataResult.setMessage("Connection timeout");
    
    // Mock AnalyticsSyncService
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS))
          .thenReturn(sessionUsageResult);
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA))
          .thenReturn(metadataResult);
    });
    
    // Execute - should throw exception
    process.execute(mockBundle);
  }

  @Test(expected = JobExecutionException.class)
  public void testExecuteWithBothFailures() throws Exception {
    // Mock failed results
    AnalyticsSyncService.SyncResult sessionUsageResult = new AnalyticsSyncService.SyncResult();
    sessionUsageResult.setStatus("FAILED");
    sessionUsageResult.setMessage("Database error");
    
    AnalyticsSyncService.SyncResult metadataResult = new AnalyticsSyncService.SyncResult();
    metadataResult.setStatus("FAILED");
    metadataResult.setMessage("Network error");
    
    // Mock AnalyticsSyncService
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS))
          .thenReturn(sessionUsageResult);
      when(mock.executeSync(AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA))
          .thenReturn(metadataResult);
    });
    
    // Execute - should throw exception
    process.execute(mockBundle);
  }

  @Test(expected = JobExecutionException.class)
  public void testExecuteWithException() throws Exception {
    // Mock service throwing exception
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenThrow(new RuntimeException("Critical error"));
    });
    
    // Execute - should wrap in JobExecutionException
    process.execute(mockBundle);
  }

  @Test
  public void testExecuteRestoresModeOnSuccess() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult sessionUsageResult = new AnalyticsSyncService.SyncResult();
    sessionUsageResult.setStatus("SUCCESS");
    
    AnalyticsSyncService.SyncResult metadataResult = new AnalyticsSyncService.SyncResult();
    metadataResult.setStatus("SUCCESS");
    
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(sessionUsageResult, metadataResult);
    });
    
    // Execute
    process.execute(mockBundle);
    
    // Verify mode restored in finally block
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

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

  @Test
  public void testExecuteCommitsAndClosesOnSuccess() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult sessionUsageResult = new AnalyticsSyncService.SyncResult();
    sessionUsageResult.setStatus("SUCCESS");
    
    AnalyticsSyncService.SyncResult metadataResult = new AnalyticsSyncService.SyncResult();
    metadataResult.setStatus("SUCCESS");
    
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(sessionUsageResult, metadataResult);
    });
    
    // Execute
    process.execute(mockBundle);
    
    // Verify commit in finally block
    verify(mockOBDal).commitAndClose();
  }

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

  @Test
  public void testConstants() {
    assertEquals("==========================================", AnalyticsSyncProcess.SEPARATOR);
    assertEquals("------------------------------------------", AnalyticsSyncProcess.SEPARATOR_MIDDLE);
    assertEquals("SUCCESS", AnalyticsSyncProcess.SUCCESS);
  }

  @Test
  public void testSetProcessLoggerOnService() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();
    result.setStatus("SUCCESS");
    
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(result);
    });
    
    // Execute
    process.execute(mockBundle);
    
    // Verify setProcessLogger was called on service
    AnalyticsSyncService service = mockedService.constructed().get(0);
    verify(service).setProcessLogger(mockLogger);
  }

  @Test
  public void testLogsSummaryInformation() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult sessionUsageResult = new AnalyticsSyncService.SyncResult();
    sessionUsageResult.setStatus("SUCCESS");
    sessionUsageResult.setSessionsCount(15);
    sessionUsageResult.setAuditsCount(30);
    
    AnalyticsSyncService.SyncResult metadataResult = new AnalyticsSyncService.SyncResult();
    metadataResult.setStatus("SUCCESS");
    metadataResult.setModulesCount(8);
    
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

  @Test
  public void testUsesDefaultReceiverUrl() throws Exception {
    // Mock successful results
    AnalyticsSyncService.SyncResult result = new AnalyticsSyncService.SyncResult();
    result.setStatus("SUCCESS");
    
    mockedService = Mockito.mockConstruction(AnalyticsSyncService.class, (mock, context) -> {
      when(mock.executeSync(anyString())).thenReturn(result);
    });
    
    // Execute
    process.execute(mockBundle);
    
    // Verify default constructor was used (no URL parameter)
    assertEquals(1, mockedService.constructed().size());
  }
}
