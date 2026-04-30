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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.analytics.exporter.BaseAnalyticsTest;
import com.etendoerp.analytics.exporter.data.AnalyticsExporterConfig;

/**
 * Unit tests for {@link AnalyticsExporterConfigService}.
 * <p>
 * Covers default fallback behavior, automatic system-level configuration
 * creation, sanitization of persisted values, and selection of the effective
 * active configuration record.
 */
@ExtendWith(MockitoExtension.class)
public class AnalyticsExporterConfigServiceTest extends BaseAnalyticsTest {

  private AnalyticsExporterConfigService service;

  @Mock
  private OBCriteria<AnalyticsExporterConfig> mockCriteria;

  @Mock
  private AnalyticsExporterConfig mockConfigRecord;

  @Mock
  private AnalyticsExporterConfig mockSecondConfigRecord;

  @Mock
  private User mockUser;

  @Mock
  private Client mockClient;

  @Mock
  private Organization mockOrganization;

  @Mock
  private OBProvider mockProvider;

  private MockedStatic<OBProvider> mockedProvider;

  /**
   * Initializes the service under test and the static OBProvider mock.
   */
  @BeforeEach
  public void setUp() {
    service = new AnalyticsExporterConfigService();
    mockedProvider = mockStatic(OBProvider.class);
    mockedProvider.when(OBProvider::getInstance).thenReturn(mockProvider);
  }

  /**
   * Releases the static OBProvider mock created for each test.
   */
  @AfterEach
  public void tearDown() {
    if (mockedProvider != null) {
      mockedProvider.close();
    }
  }

  /**
   * Verifies that code defaults are returned when DAL configuration lookup fails.
   */
  @Test
  public void testGetEffectiveConfigFallsBackToDefaultsWhenCriteriaFails() {
    when(mockOBDal.createCriteria(AnalyticsExporterConfig.class)).thenThrow(new RuntimeException("boom"));

    AnalyticsExporterConfigService.EffectiveConfig config = service.getEffectiveConfig();

    assertNotNull(config);
    assertFalse(config.isConfigured());
    assertEquals(7, config.getInitialExportDays());
    assertEquals(1, config.getChunkDays());
    assertEquals(3, config.getMaxRetries());
    assertEquals(2000, config.getRetryDelayMs());
    assertEquals(30000, config.getConnectTimeoutMs());
    assertEquals(60000, config.getReadTimeoutMs());
    assertFalse(config.isDetailedLoggingEnabled());

    mockedContext.verify(() -> org.openbravo.dal.core.OBContext.setAdminMode(true), times(1));
    mockedContext.verify(org.openbravo.dal.core.OBContext::restorePreviousMode, times(1));
  }

  /**
   * Verifies that a default system configuration record is created when none exists.
   */
  @Test
  public void testGetEffectiveConfigCreatesDefaultSystemConfigWhenNoRecordsExist() {
    when(mockOBDal.createCriteria(AnalyticsExporterConfig.class)).thenReturn(mockCriteria);
    when(mockCriteria.add(any(Criterion.class))).thenReturn(mockCriteria);
    when(mockCriteria.addOrder(any(Order.class))).thenReturn(mockCriteria);
    when(mockCriteria.setFilterOnReadableClients(false)).thenReturn(mockCriteria);
    when(mockCriteria.setFilterOnReadableOrganization(false)).thenReturn(mockCriteria);
    when(mockCriteria.list()).thenReturn(Collections.emptyList());

    when(mockProvider.get(AnalyticsExporterConfig.class)).thenReturn(mockConfigRecord);
    when(mockOBDal.get(User.class, "100")).thenReturn(mockUser);
    when(mockOBDal.get(Client.class, "0")).thenReturn(mockClient);
    when(mockOBDal.get(Organization.class, "0")).thenReturn(mockOrganization);
    when(mockConfigRecord.getId()).thenReturn("CFG-1");

    AnalyticsExporterConfigService.EffectiveConfig config = service.getEffectiveConfig();

    assertTrue(config.isConfigured());
    assertEquals("CFG-1", config.getConfigRecordId());
    assertEquals(7, config.getInitialExportDays());
    assertEquals(1, config.getChunkDays());
    assertEquals(3, config.getMaxRetries());

    verify(mockConfigRecord).setClient(mockClient);
    verify(mockConfigRecord).setOrganization(mockOrganization);
    verify(mockConfigRecord).setCreatedBy(mockUser);
    verify(mockConfigRecord).setUpdatedBy(mockUser);
    verify(mockConfigRecord).setConfigurationName("Default System Configuration");
    verify(mockConfigRecord).setInitialExportDays(Long.valueOf(7));
    verify(mockConfigRecord).setChunkDays(Long.valueOf(1));
    verify(mockConfigRecord).setMaxRetries(Long.valueOf(3));
    verify(mockConfigRecord).setRetryDelayMs(Long.valueOf(2000));
    verify(mockConfigRecord).setConnectTimeoutMs(Long.valueOf(30000));
    verify(mockConfigRecord).setReadTimeoutMs(Long.valueOf(60000));
    verify(mockConfigRecord).setDetailedLoggingEnabled(false);
    verify(mockOBDal).save(mockConfigRecord);
    verify(mockOBDal).flush();
  }

  /**
   * Verifies that invalid persisted numeric values are sanitized back to safe defaults.
   */
  @Test
  public void testGetEffectiveConfigUsesStoredValuesAndSanitizesInvalidNumbers() {
    when(mockOBDal.createCriteria(AnalyticsExporterConfig.class)).thenReturn(mockCriteria);
    when(mockCriteria.add(any(Criterion.class))).thenReturn(mockCriteria);
    when(mockCriteria.addOrder(any(Order.class))).thenReturn(mockCriteria);
    when(mockCriteria.setFilterOnReadableClients(false)).thenReturn(mockCriteria);
    when(mockCriteria.setFilterOnReadableOrganization(false)).thenReturn(mockCriteria);
    when(mockCriteria.list()).thenReturn(Collections.singletonList(mockConfigRecord));

    when(mockConfigRecord.getId()).thenReturn("CFG-2");
    when(mockConfigRecord.getReceiverURL()).thenReturn("   ");
    when(mockConfigRecord.getInitialExportDays()).thenReturn(Long.valueOf(0));
    when(mockConfigRecord.getChunkDays()).thenReturn(Long.valueOf(-5));
    when(mockConfigRecord.getMaxRetries()).thenReturn(Long.valueOf(0));
    when(mockConfigRecord.getRetryDelayMs()).thenReturn(Long.valueOf(-1));
    when(mockConfigRecord.getConnectTimeoutMs()).thenReturn(Long.valueOf(100));
    when(mockConfigRecord.getReadTimeoutMs()).thenReturn(Long.valueOf(500));
    when(mockConfigRecord.isDetailedLoggingEnabled()).thenReturn(Boolean.TRUE);

    AnalyticsExporterConfigService.EffectiveConfig config = service.getEffectiveConfig();

    assertTrue(config.isConfigured());
    assertEquals("CFG-2", config.getConfigRecordId());
    assertEquals(7, config.getInitialExportDays());
    assertEquals(1, config.getChunkDays());
    assertEquals(3, config.getMaxRetries());
    assertEquals(2000, config.getRetryDelayMs());
    assertEquals(30000, config.getConnectTimeoutMs());
    assertEquals(60000, config.getReadTimeoutMs());
    assertTrue(config.isDetailedLoggingEnabled());
  }

  /**
   * Verifies that the most recently updated active configuration is selected when
   * multiple active records are available.
   */
  @Test
  public void testGetEffectiveConfigUsesMostRecentlyUpdatedRecordWhenMultipleExist() {
    when(mockOBDal.createCriteria(AnalyticsExporterConfig.class)).thenReturn(mockCriteria);
    when(mockCriteria.add(any(Criterion.class))).thenReturn(mockCriteria);
    when(mockCriteria.addOrder(any(Order.class))).thenReturn(mockCriteria);
    when(mockCriteria.setFilterOnReadableClients(false)).thenReturn(mockCriteria);
    when(mockCriteria.setFilterOnReadableOrganization(false)).thenReturn(mockCriteria);
    when(mockCriteria.list()).thenReturn(Arrays.asList(mockConfigRecord, mockSecondConfigRecord));

    when(mockConfigRecord.getId()).thenReturn("CFG-PRIMARY");
    when(mockConfigRecord.getReceiverURL()).thenReturn("https://custom.receiver/process");
    when(mockConfigRecord.getInitialExportDays()).thenReturn(Long.valueOf(10));
    when(mockConfigRecord.getChunkDays()).thenReturn(Long.valueOf(2));
    when(mockConfigRecord.getMaxRetries()).thenReturn(Long.valueOf(5));
    when(mockConfigRecord.getRetryDelayMs()).thenReturn(Long.valueOf(50));
    when(mockConfigRecord.getConnectTimeoutMs()).thenReturn(Long.valueOf(5000));
    when(mockConfigRecord.getReadTimeoutMs()).thenReturn(Long.valueOf(7000));
    when(mockConfigRecord.isDetailedLoggingEnabled()).thenReturn(Boolean.FALSE);

    AnalyticsExporterConfigService.EffectiveConfig config = service.getEffectiveConfig();

    assertTrue(config.isConfigured());
    assertEquals("CFG-PRIMARY", config.getConfigRecordId());
    assertEquals("https://custom.receiver/process", config.getReceiverUrl());
    assertEquals(10, config.getInitialExportDays());
    assertEquals(2, config.getChunkDays());
    assertEquals(5, config.getMaxRetries());
    assertEquals(50, config.getRetryDelayMs());
    assertEquals(5000, config.getConnectTimeoutMs());
    assertEquals(7000, config.getReadTimeoutMs());
    assertFalse(config.isDetailedLoggingEnabled());
  }
}
