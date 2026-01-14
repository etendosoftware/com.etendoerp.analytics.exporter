package com.etendoerp.analytics.exporter.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.ad.access.Session;
import org.openbravo.model.ad.access.SessionUsageAudit;
import org.openbravo.model.ad.module.Module;

import com.etendoerp.analytics.exporter.data.AnalyticsPayload;
import com.etendoerp.analytics.exporter.data.PayloadMetadata;

/**
 * Unit tests for DataExtractionService
 * Tests data extraction using DAL with proper mocking
 */
@RunWith(MockitoJUnitRunner.class)
public class DataExtractionServiceTest {

  private DataExtractionService service;
  
  @Mock
  private OBDal mockOBDal;
  
  @Mock
  private OBCriteria<Session> mockSessionCriteria;
  
  @Mock
  private OBCriteria<SessionUsageAudit> mockAuditCriteria;
  
  @Mock
  private OBCriteria<Module> mockModuleCriteria;
  
  private MockedStatic<OBContext> mockedContext;
  private MockedStatic<OBDal> mockedDal;
  
  private List<Session> mockSessions;
  private List<SessionUsageAudit> mockAudits;
  private List<Module> mockModules;

  @Before
  public void setUp() {
    service = new DataExtractionService();
    
    // Setup mock data
    mockSessions = new ArrayList<>();
    mockAudits = new ArrayList<>();
    mockModules = new ArrayList<>();
    
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
  }

  @Test
  public void testExtractAnalyticsDataSuccess() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    // Configure session criteria
    when(mockSessionCriteria.add(any(Criterion.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.addOrder(any(Order.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    // Configure audit criteria
    when(mockAuditCriteria.createAlias(any(), any())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.add(any(Criterion.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.addOrder(any(Order.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute
    AnalyticsPayload result = service.extractAnalyticsData("test-instance", null, 7);
    
    // Verify
    assertNotNull(result);
    assertNotNull(result.getMetadata());
    assertEquals("test-instance", result.getMetadata().getSourceInstance());
    assertEquals(Integer.valueOf(7), result.getMetadata().getDaysExported());
    assertNotNull(result.getSessions());
    assertNotNull(result.getUsageAudits());
    
    // Verify admin mode was set and restored
    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testExtractAnalyticsDataWithLastSyncTimestamp() {
    Timestamp lastSync = Timestamp.from(Instant.now().minusSeconds(86400));
    
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    when(mockSessionCriteria.add(any(Criterion.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.addOrder(any(Order.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    when(mockAuditCriteria.createAlias(any(), any())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.add(any(Criterion.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.addOrder(any(Order.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute
    AnalyticsPayload result = service.extractAnalyticsData("test-instance", lastSync, null);
    
    // Verify
    assertNotNull(result);
    assertNotNull(result.getMetadata());
    assertEquals("test-instance", result.getMetadata().getSourceInstance());
    
    // Verify criteria was configured for incremental sync
    verify(mockSessionCriteria, times(2)).add(any(Criterion.class)); // POS filter + timestamp filter
    verify(mockAuditCriteria, times(2)).add(any(Criterion.class)); // POS filter + timestamp filter (createAlias is separate)
  }

  @Test
  public void testExtractAnalyticsDataWithNullDaysToExport() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    when(mockSessionCriteria.add(any(Criterion.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.addOrder(any(Order.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    when(mockAuditCriteria.createAlias(any(), any())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.add(any(Criterion.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.addOrder(any(Order.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute - no lastSync, no daysToExport (all data)
    AnalyticsPayload result = service.extractAnalyticsData("test-instance", null, null);
    
    // Verify
    assertNotNull(result);
    assertNotNull(result.getMetadata());
    
    // Should only have POS filter, no date filters
    verify(mockSessionCriteria, times(1)).add(any(Criterion.class));
  }

  @Test(expected = OBException.class)
  public void testExtractAnalyticsDataThrowsOBException() {
    // Setup mock to throw exception
    when(mockOBDal.createCriteria(Session.class)).thenThrow(new RuntimeException("Database error"));
    
    // Execute - should wrap exception in OBException
    service.extractAnalyticsData("test-instance", null, 7);
  }

  @Test
  public void testExtractModuleMetadataSuccess() {
    // Setup mocks
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    
    when(mockModuleCriteria.add(any(Criterion.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.addOrder(any(Order.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.list()).thenReturn(mockModules);
    
    // Execute
    List<Module> result = service.extractModuleMetadata(null);
    
    // Verify
    assertNotNull(result);
    assertEquals(mockModules, result);
    
    // Verify admin mode
    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
    
    // Verify only enabled filter (no timestamp filter)
    verify(mockModuleCriteria, times(1)).add(any(Criterion.class));
  }

  @Test
  public void testExtractModuleMetadataWithLastSyncTimestamp() {
    Timestamp lastSync = Timestamp.from(Instant.now().minusSeconds(86400));
    
    // Setup mocks
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    
    when(mockModuleCriteria.add(any(Criterion.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.addOrder(any(Order.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.list()).thenReturn(mockModules);
    
    // Execute
    List<Module> result = service.extractModuleMetadata(lastSync);
    
    // Verify
    assertNotNull(result);
    
    // Verify enabled + timestamp filters
    verify(mockModuleCriteria, times(2)).add(any(Criterion.class));
  }

  @Test
  public void testExtractModuleMetadataWithEmptyResult() {
    // Setup mocks with empty list
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    
    when(mockModuleCriteria.add(any(Criterion.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.addOrder(any(Order.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.list()).thenReturn(new ArrayList<>());
    
    // Execute
    List<Module> result = service.extractModuleMetadata(null);
    
    // Verify
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  @Test
  public void testExtractAnalyticsDataRestoresModeOnException() {
    // Setup mock to throw exception
    when(mockOBDal.createCriteria(Session.class)).thenThrow(new RuntimeException("Test exception"));
    
    try {
      service.extractAnalyticsData("test-instance", null, 7);
    } catch (OBException e) {
      // Expected
    }
    
    // Verify previous mode was restored even on exception
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testExtractModuleMetadataRestoresModeOnCompletion() {
    // Setup mocks
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    
    when(mockModuleCriteria.add(any(Criterion.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.addOrder(any(Order.class))).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockModuleCriteria);
    when(mockModuleCriteria.list()).thenReturn(mockModules);
    
    // Execute
    service.extractModuleMetadata(null);
    
    // Verify mode was restored
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  @Test
  public void testExtractAnalyticsDataWithZeroDays() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    when(mockSessionCriteria.add(any(Criterion.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.addOrder(any(Order.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    when(mockAuditCriteria.createAlias(any(), any())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.add(any(Criterion.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.addOrder(any(Order.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute with 0 days
    AnalyticsPayload result = service.extractAnalyticsData("test-instance", null, 0);
    
    // Verify
    assertNotNull(result);
    assertEquals(Integer.valueOf(0), result.getMetadata().getDaysExported());
    
    // Should only have POS filter (0 days means no date filter)
    verify(mockSessionCriteria, times(1)).add(any(Criterion.class));
  }

  @Test
  public void testMetadataTimestampFormat() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    when(mockSessionCriteria.add(any(Criterion.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.addOrder(any(Order.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    when(mockAuditCriteria.createAlias(any(), any())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.add(any(Criterion.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.addOrder(any(Order.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute
    AnalyticsPayload result = service.extractAnalyticsData("test-instance", null, 7);
    
    // Verify timestamp format (should be ISO-8601 with microseconds)
    String timestamp = result.getMetadata().getExportTimestamp();
    assertNotNull(timestamp);
    assertTrue(timestamp.contains("T"));
    assertTrue(timestamp.contains("+") || timestamp.contains("Z"));
  }

  @Test
  public void testConstructor() {
    DataExtractionService newService = new DataExtractionService();
    assertNotNull(newService);
  }

  @Test
  public void testFiltersDisableReadableChecks() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    when(mockSessionCriteria.add(any(Criterion.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.addOrder(any(Order.class))).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    when(mockAuditCriteria.createAlias(any(), any())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.add(any(Criterion.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.addOrder(any(Order.class))).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.setFilterOnReadableClients(anyBoolean())).thenReturn(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute
    service.extractAnalyticsData("test-instance", null, 7);
    
    // Verify filters are disabled for admin access
    verify(mockSessionCriteria).setFilterOnReadableOrganization(false);
    verify(mockSessionCriteria).setFilterOnReadableClients(false);
    verify(mockAuditCriteria).setFilterOnReadableOrganization(false);
    verify(mockAuditCriteria).setFilterOnReadableClients(false);
  }
}
