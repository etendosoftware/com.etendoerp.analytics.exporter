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

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
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
 * Service for extracting analytics data from Etendo database
 * Uses Etendo DAL with generated entities
 */
public class DataExtractionService {

  private static final Logger log = LogManager.getLogger();

  public DataExtractionService() {
    // No need for ConnectionProvider, using DAL
  }

  /**
   * Extract all analytics data (sessions and usage audits)
   *
   * @param sourceInstance
   *     Name of the Etendo instance
   * @param lastSyncTimestamp
   *     Last successful sync timestamp (null for first sync)
   * @param daysToExport
   *     Number of days to export (if lastSyncTimestamp is null, defaults to 7)
   * @return AnalyticsPayload with extracted data
   */
  public AnalyticsPayload extractAnalyticsData(String sourceInstance, Timestamp lastSyncTimestamp,
      Integer daysToExport) {
    log.debug("Starting data extraction for instance: {}", sourceInstance);

    try {
      OBContext.setAdminMode(true);

      AnalyticsPayload payload = new AnalyticsPayload();

      // Set metadata
      PayloadMetadata metadata = payload.getMetadata();
      metadata.setSourceInstance(sourceInstance);
      metadata.setExportTimestamp(formatTimestamp(Timestamp.from(Instant.now())));
      if (daysToExport != null) {
        metadata.setDaysExported(daysToExport);
      }

      // Extract sessions using DAL
      List<Session> sessions = extractSessions(lastSyncTimestamp, daysToExport);
      payload.setSessions(sessions);
      log.debug("Extracted {} sessions", sessions.size());

      // Extract usage audits using DAL
      List<SessionUsageAudit> audits = extractUsageAudits(lastSyncTimestamp, daysToExport);
      payload.setUsageAudits(audits);
      log.debug("Extracted {} usage audits", audits.size());

      return payload;

    } catch (Exception e) {
      log.error("Error during data extraction", e);
      throw new OBException("Failed to extract analytics data", e);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Extract session data using Etendo DAL
   */
  private List<Session> extractSessions(Timestamp lastSyncTimestamp, Integer daysToExport) {
    OBCriteria<Session> criteria = OBDal.getInstance().createCriteria(Session.class);

    // Filter out POS sessions
    criteria.add(Restrictions.not(Restrictions.eq(Session.PROPERTY_LOGINSTATUS, "OBPOS_POS")));

    // Apply timestamp filter
    if (lastSyncTimestamp != null) {
      criteria.add(Restrictions.gt(Session.PROPERTY_CREATIONDATE, lastSyncTimestamp));
      log.debug("Using incremental export for sessions since: {}", lastSyncTimestamp);
    } else if (daysToExport != null && daysToExport > 0) {
      Date cutoffDate = Date.from(Instant.now().minusSeconds(daysToExport * 24L * 3600L));
      criteria.add(Restrictions.ge(Session.PROPERTY_CREATIONDATE, cutoffDate));
      log.debug("Exporting last {} days of sessions", daysToExport);
    }

    criteria.addOrder(Order.desc(Session.PROPERTY_CREATIONDATE));
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnReadableClients(false);

    return criteria.list();
  }

  /**
   * Extract usage audit data using Etendo DAL
   */
  private List<SessionUsageAudit> extractUsageAudits(Timestamp lastSyncTimestamp, Integer daysToExport) {
    OBCriteria<SessionUsageAudit> criteria = OBDal.getInstance().createCriteria(SessionUsageAudit.class);

    // Join with session to filter out POS
    criteria.createAlias(SessionUsageAudit.PROPERTY_SESSION, "session");
    criteria.add(Restrictions.not(Restrictions.eq("session." + Session.PROPERTY_LOGINSTATUS, "OBPOS_POS")));

    // Apply timestamp filter
    if (lastSyncTimestamp != null) {
      criteria.add(Restrictions.gt(SessionUsageAudit.PROPERTY_CREATIONDATE, lastSyncTimestamp));
      log.debug("Using incremental export for audits since: {}", lastSyncTimestamp);
    } else if (daysToExport != null && daysToExport > 0) {
      Date cutoffDate = Date.from(Instant.now().minusSeconds(daysToExport * 24L * 3600L));
      criteria.add(Restrictions.ge(SessionUsageAudit.PROPERTY_CREATIONDATE, cutoffDate));
      log.debug("Exporting last {} days of audits", daysToExport);
    }

    criteria.addOrder(Order.desc(SessionUsageAudit.PROPERTY_CREATIONDATE));
    criteria.setFilterOnReadableOrganization(false);
    criteria.setFilterOnReadableClients(false);

    return criteria.list();
  }

  /**
   * Extract module metadata using Etendo DAL
   *
   * @param lastSyncTimestamp
   *     Last successful sync timestamp (null for first sync)
   * @return List of modules (all modules if null, only new modules since timestamp if provided)
   */
  public List<Module> extractModuleMetadata(Timestamp lastSyncTimestamp) {
    log.debug("Starting module metadata extraction");

    try {
      OBContext.setAdminMode(true);

      OBCriteria<Module> criteria = OBDal.getInstance().createCriteria(Module.class);
      criteria.add(Restrictions.eq(Module.PROPERTY_ENABLED, true));

      // Apply incremental filter if we have a last sync timestamp
      if (lastSyncTimestamp != null) {
        criteria.add(Restrictions.gt(Module.PROPERTY_CREATIONDATE, lastSyncTimestamp));
        log.debug("Using incremental export for modules since: {}", lastSyncTimestamp);
      } else {
        log.debug("Exporting all active modules (first sync)");
      }

      criteria.addOrder(Order.asc(Module.PROPERTY_NAME));
      criteria.setFilterOnReadableOrganization(false);
      criteria.setFilterOnReadableClients(false);

      List<Module> modules = criteria.list();

      log.debug("Extracted {} active modules", modules.size());
      return modules;

    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Format timestamp to ISO 8601 with UTC timezone
   */
  private String formatTimestamp(Timestamp timestamp) {
    if (timestamp == null) {
      return null;
    }
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
    return sdf.format(timestamp);
  }
}
