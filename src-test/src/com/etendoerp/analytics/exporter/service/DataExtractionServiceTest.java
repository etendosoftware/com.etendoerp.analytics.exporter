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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.base.exception.OBException;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.Session;
import org.openbravo.model.ad.access.SessionUsageAudit;
import org.openbravo.model.ad.module.Module;

import com.etendoerp.analytics.exporter.BaseAnalyticsTest;
import com.etendoerp.analytics.exporter.data.AnalyticsPayload;
import com.etendoerp.analytics.exporter.data.PayloadMetadata;

/**
 * Unit tests for DataExtractionService
 * Tests data extraction using DAL with proper mocking
 */
@ExtendWith(MockitoExtension.class)
public class DataExtractionServiceTest extends BaseAnalyticsTest {

  public static final String TEST_INSTANCE = "test-instance";
  private DataExtractionService service;
  
  @Mock
  private OBCriteria<Session> mockSessionCriteria;
  
  @Mock
  private OBCriteria<SessionUsageAudit> mockAuditCriteria;
  
  @Mock
  private OBCriteria<Module> mockModuleCriteria;
  
  private List<Session> mockSessions;
  private List<SessionUsageAudit> mockAudits;
  private List<Module> mockModules;

  /**
   * Sets up test fixtures and mock data before each test execution.
   */
  @BeforeEach
  public void setUp() {
    service = new DataExtractionService();
    
    // Setup mock data
    mockSessions = new ArrayList<>();
    mockAudits = new ArrayList<>();
    mockModules = new ArrayList<>();
  }

  /**
   * Tests successful extraction of analytics data with sessions and audits.
   */
  @Test
  public void testExtractAnalyticsDataSuccess() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    setupStandardCriteriaMock(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    setupCriteriaWithAliasMock(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute
    AnalyticsPayload result = service.extractAnalyticsData(TEST_INSTANCE, null, 7);
    
    // Verify
    assertNotNull(result);
    assertNotNull(result.getMetadata());
    assertEquals(TEST_INSTANCE, result.getMetadata().getSourceInstance());
    assertEquals(Integer.valueOf(7), result.getMetadata().getDaysExported());
    assertNotNull(result.getSessions());
    assertNotNull(result.getUsageAudits());
    
    // Verify admin mode was set and restored
    mockedContext.verify(() -> OBContext.setAdminMode(true), times(1));
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests extraction of analytics data using last sync timestamp for incremental sync.
   */
  @Test
  public void testExtractAnalyticsDataWithLastSyncTimestamp() {
    Timestamp lastSync = Timestamp.from(Instant.now().minusSeconds(86400));
    
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    setupStandardCriteriaMock(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    setupCriteriaWithAliasMock(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute
    AnalyticsPayload result = service.extractAnalyticsData(TEST_INSTANCE, lastSync, null);
    
    // Verify
    assertNotNull(result);
    assertNotNull(result.getMetadata());
    assertEquals(TEST_INSTANCE, result.getMetadata().getSourceInstance());
    
    // Verify criteria was configured for incremental sync
    verify(mockSessionCriteria, times(2)).add(any(Criterion.class)); // POS filter + timestamp filter
    verify(mockAuditCriteria, times(2)).add(any(Criterion.class)); // POS filter + timestamp filter (createAlias is separate)
  }

  /**
   * Tests extraction of all analytics data when days to export is null.
   */
  @Test
  public void testExtractAnalyticsDataWithNullDaysToExport() {
    // Setup mocks
    when(mockOBDal.createCriteria(Session.class)).thenReturn(mockSessionCriteria);
    when(mockOBDal.createCriteria(SessionUsageAudit.class)).thenReturn(mockAuditCriteria);
    
    setupStandardCriteriaMock(mockSessionCriteria);
    when(mockSessionCriteria.list()).thenReturn(mockSessions);
    
    setupCriteriaWithAliasMock(mockAuditCriteria);
    when(mockAuditCriteria.list()).thenReturn(mockAudits);
    
    // Execute - no lastSync, no daysToExport (all data)
    AnalyticsPayload result = service.extractAnalyticsData(TEST_INSTANCE, null, null);
    
    // Verify
    assertNotNull(result);
    assertNotNull(result.getMetadata());
    
    // Should only have POS filter, no date filters
    verify(mockSessionCriteria, times(1)).add(any(Criterion.class));
  }

  /**
   * Tests that runtime exceptions are wrapped in OBException during extraction.
   */
  @Test
  public void testExtractAnalyticsDataThrowsOBException() {
    // Setup mock to throw exception
    when(mockOBDal.createCriteria(Session.class)).thenThrow(new RuntimeException("Database error"));
    
    // Execute - should wrap exception in OBException
    assertThrows(OBException.class, () -> service.extractAnalyticsData(TEST_INSTANCE, null, 7));
  }

  /**
   * Tests successful extraction of module metadata.
   */
  @Test
  public void testExtractModuleMetadataSuccess() {
    // Setup mocks
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    setupStandardCriteriaMock(mockModuleCriteria);
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

  /**
   * Tests extraction of module metadata with last sync timestamp for incremental sync.
   */
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

  /**
   * Tests extraction of module metadata when no modules are found.
   */
  @Test
  public void testExtractModuleMetadataWithEmptyResult() {
    // Setup mocks with empty list
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    setupStandardCriteriaMock(mockModuleCriteria);
    when(mockModuleCriteria.list()).thenReturn(new ArrayList<>());
    
    // Execute
    List<Module> result = service.extractModuleMetadata(null);
    
    // Verify
    assertNotNull(result);
    assertTrue(result.isEmpty());
  }

  /**
   * Tests that admin mode is restored even when an exception occurs during extraction.
   */
  @Test
  public void testExtractAnalyticsDataRestoresModeOnException() {
    // Setup mock to throw exception
    when(mockOBDal.createCriteria(Session.class)).thenThrow(new RuntimeException("Test exception"));
    
    try {
      service.extractAnalyticsData(TEST_INSTANCE, null, 7);
    } catch (OBException e) {
      // Expected
    }
    
    // Verify previous mode was restored even on exception
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests that admin mode is restored after successful module metadata extraction.
   */
  @Test
  public void testExtractModuleMetadataRestoresModeOnCompletion() {
    // Setup mocks
    when(mockOBDal.createCriteria(Module.class)).thenReturn(mockModuleCriteria);
    setupStandardCriteriaMock(mockModuleCriteria);
    when(mockModuleCriteria.list()).thenReturn(mockModules);
    
    // Execute
    service.extractModuleMetadata(null);
    
    // Verify mode was restored
    mockedContext.verify(OBContext::restorePreviousMode, times(1));
  }

  /**
   * Tests extraction of analytics data when zero days are specified.
   */
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
    AnalyticsPayload result = service.extractAnalyticsData(TEST_INSTANCE, null, 0);
    
    // Verify
    assertNotNull(result);
    assertEquals(Integer.valueOf(0), result.getMetadata().getDaysExported());
    
    // Should only have POS filter (0 days means no date filter)
    verify(mockSessionCriteria, times(1)).add(any(Criterion.class));
  }

  /**
   * Tests that metadata timestamp is formatted in ISO-8601 format.
   */
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
    AnalyticsPayload result = service.extractAnalyticsData(TEST_INSTANCE, null, 7);
    
    // Verify timestamp format (should be ISO-8601 with microseconds)
    String timestamp = result.getMetadata().getExportTimestamp();
    assertNotNull(timestamp);
    assertTrue(timestamp.contains("T"));
    assertTrue(timestamp.contains("+") || timestamp.contains("Z"));
  }

  /**
   * Tests that service can be instantiated successfully.
   */
  @Test
  public void testConstructor() {
    DataExtractionService newService = new DataExtractionService();
    assertNotNull(newService);
  }

  /**
   * Tests that readable organization and client filters are disabled for admin access.
   */
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
    service.extractAnalyticsData(TEST_INSTANCE, null, 7);
    
    // Verify filters are disabled for admin access
    verify(mockSessionCriteria).setFilterOnReadableOrganization(false);
    verify(mockSessionCriteria).setFilterOnReadableClients(false);
    verify(mockAuditCriteria).setFilterOnReadableOrganization(false);
    verify(mockAuditCriteria).setFilterOnReadableClients(false);
  }
}
