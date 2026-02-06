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

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBDal;
import org.openbravo.scheduling.Process;
import org.openbravo.scheduling.ProcessBundle;
import org.openbravo.scheduling.ProcessLogger;
import org.quartz.JobExecutionException;

import com.etendoerp.analytics.exporter.service.AnalyticsSyncService;

/**
 * Background process for synchronizing analytics data with receiver
 * Acts as orchestrator, executing sync for each configured sync type
 * Can be configured to run automatically via Process Scheduling
 */
public class AnalyticsSyncProcess implements Process {

  private static final Logger log = LogManager.getLogger();
  public static final String SEPARATOR = "==========================================";
  public static final String SEPARATOR_MIDDLE = "------------------------------------------";
  public static final String SUCCESS = "SUCCESS";

  @Override
  public void execute(ProcessBundle bundle) throws Exception {
    ProcessLogger logger = bundle.getLogger();

    log.info("Analytics Sync Process started");
    logger.log(SEPARATOR + "\n");
    logger.log("Starting Analytics Sync Orchestrator\n");
    logger.log(SEPARATOR + "\n");

    try {
      OBContext.setAdminMode(true);

      // Initialize sync service (will use preference URL if configured)
      AnalyticsSyncService syncService = new AnalyticsSyncService();

      // Set the process logger so service can log to process monitor
      syncService.setProcessLogger(logger);

      // Track overall results
      int totalSuccesses = 0;
      int totalFailures = 0;
      StringBuilder summaryMessage = new StringBuilder();

      // Execute sync for each type (SESSION_USAGE_AUDITS, MODULE_METADATA)
      logger.log(SEPARATOR_MIDDLE + "\n");
      logger.log("Sync Type 1: SESSION_USAGE_AUDITS\n");
      logger.log(SEPARATOR_MIDDLE + "\n");
      AnalyticsSyncService.SyncResult sessionUsageResult = syncService.executeSync(
          AnalyticsSyncService.SYNC_TYPE_SESSION_USAGE_AUDITS);

      if (StringUtils.equals(SUCCESS, sessionUsageResult.getStatus())) {
        totalSuccesses++;
        summaryMessage.append("✓ SESSION_USAGE_AUDITS: ")
            .append(sessionUsageResult.getSessionsCount()).append(" sessions, ")
            .append(sessionUsageResult.getAuditsCount()).append(" audits\n");
      } else {
        totalFailures++;
        summaryMessage.append("✗ SESSION_USAGE_AUDITS: ").append(sessionUsageResult.getMessage()).append("\n");
      }

      logger.log(SEPARATOR_MIDDLE + "\n");
      logger.log("Sync Type 2: MODULE_METADATA\n");
      logger.log(SEPARATOR_MIDDLE + "\n");
      AnalyticsSyncService.SyncResult metadataResult = syncService.executeSync(
          AnalyticsSyncService.SYNC_TYPE_MODULE_METADATA);

      if (StringUtils.equals(SUCCESS, metadataResult.getStatus())) {
        totalSuccesses++;
        summaryMessage.append("✓ MODULE_METADATA: ").append(metadataResult.getModulesCount())
            .append(" modules\n");
      } else {
        totalFailures++;
        summaryMessage.append("✗ MODULE_METADATA: ").append(metadataResult.getMessage()).append("\n");
      }

      // Final summary
      logger.log(SEPARATOR + "\n");
      logger.log("ORCHESTRATION SUMMARY\n");
      logger.log(SEPARATOR + "\n");
      logger.log("Total sync types executed: 2\n");
      logger.log("Successful: " + totalSuccesses + "\n");
      logger.log("Failed: " + totalFailures + "\n");
      logger.log("Details:\n");
      logger.log(summaryMessage.toString());
      logger.log(SEPARATOR + "\n");

      // If any sync type failed, mark the entire process as failed
      if (totalFailures > 0) {
        String errorMsg = "Orchestration completed with " + totalFailures + " failure(s)";
        log.error(errorMsg);
        throw new JobExecutionException(errorMsg);
      }

      log.info("Orchestration completed successfully: all sync types executed");

    } catch (Exception e) {
      log.error("Process execution failed", e);
      logger.log("*** CRITICAL ERROR ***\n");
      logger.log("Exception: " + e.getMessage() + "\n");
      throw new JobExecutionException(e.getMessage(), e);
    } finally {
      OBContext.restorePreviousMode();
      OBDal.getInstance().commitAndClose();
    }
  }
}
