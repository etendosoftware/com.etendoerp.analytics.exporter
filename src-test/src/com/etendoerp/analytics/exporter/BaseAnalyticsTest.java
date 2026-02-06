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

package com.etendoerp.analytics.exporter;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import org.hibernate.criterion.Criterion;
import org.hibernate.criterion.Order;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.openbravo.base.structure.BaseOBObject;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.businessUtility.Preferences;

/**
 * Base class for Analytics Exporter tests.
 * Provides common setup, teardown, and utility methods for mocked static objects.
 */
public abstract class BaseAnalyticsTest {

  @Mock
  protected OBDal mockOBDal;

  protected MockedStatic<OBContext> mockedContext;
  protected MockedStatic<OBDal> mockedDal;
  protected MockedStatic<Preferences> mockedPreferences;

  /**
   * Sets up common mocked static objects before each test.
   */
  @BeforeEach
  public void baseSetUp() {
    mockedContext = mockStatic(OBContext.class);
    mockedDal = mockStatic(OBDal.class);
    mockedDal.when(OBDal::getInstance).thenReturn(mockOBDal);
    
    // Mock Preferences to return null (will use default URL)
    mockedPreferences = mockStatic(Preferences.class);
    mockedPreferences.when(() -> Preferences.getPreferenceValue(anyString(), anyBoolean(), 
        anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(null);
  }

  /**
   * Cleans up mocked static objects after each test.
   */
  @AfterEach
  public void baseTearDown() {
    if (mockedContext != null) {
      mockedContext.close();
    }
    if (mockedDal != null) {
      mockedDal.close();
    }
    if (mockedPreferences != null) {
      mockedPreferences.close();
    }
  }

  /**
   * Configures a standard OBCriteria mock chain for common query operations.
   * Sets up add, addOrder, setFilterOnReadableOrganization, and setFilterOnReadableClients
   * to return the criteria itself (fluent interface pattern).
   *
   * @param criteria
   *     the mocked criteria to configure
   * @param <T>
   *     the entity type
   * @return the same criteria mock for chaining
   */
  protected <T extends BaseOBObject> OBCriteria<T> setupStandardCriteriaMock(OBCriteria<T> criteria) {
    when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    return criteria;
  }

  /**
   * Configures an OBCriteria mock for queries that use aliases.
   * In addition to standard criteria setup, also mocks the createAlias method.
   *
   * @param criteria
   *     the mocked criteria to configure
   * @param <T>
   *     the entity type
   * @return the same criteria mock for chaining
   */
  protected <T extends BaseOBObject> OBCriteria<T> setupCriteriaWithAliasMock(OBCriteria<T> criteria) {
    setupStandardCriteriaMock(criteria);
    when(criteria.createAlias(any(), any())).thenReturn(criteria);
    return criteria;
  }

  /**
   * Configures a lenient OBCriteria mock for tests that may not use all stubs.
   * Uses lenient() to avoid unnecessary stubbing warnings in tests where some
   * criteria methods may not be called.
   *
   * @param criteria
   *     the mocked criteria to configure
   * @param <T>
   *     the entity type
   * @return the same criteria mock for chaining
   */
  protected <T extends BaseOBObject> OBCriteria<T> setupLenientCriteriaMock(OBCriteria<T> criteria) {
    lenient().when(criteria.add(any(Criterion.class))).thenReturn(criteria);
    lenient().when(criteria.addOrder(any(Order.class))).thenReturn(criteria);
    lenient().when(criteria.addOrderBy(anyString(), any(Boolean.class))).thenReturn(criteria);
    lenient().when(criteria.setFilterOnReadableOrganization(anyBoolean())).thenReturn(criteria);
    lenient().when(criteria.setFilterOnReadableClients(anyBoolean())).thenReturn(criteria);
    lenient().when(criteria.setMaxResults(any(Integer.class))).thenReturn(criteria);
    return criteria;
  }
}
