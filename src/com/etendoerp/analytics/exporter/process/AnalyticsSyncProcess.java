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

      // Get receiver URL from preference if configured
      String receiverUrl = getReceiverUrlFromPreference();

      // Initialize sync service
      AnalyticsSyncService syncService;
      if (receiverUrl != null && !receiverUrl.trim().isEmpty()) {
        syncService = new AnalyticsSyncService(receiverUrl);
        logger.log("Using custom receiver URL: " + receiverUrl + "\n");
      } else {
        syncService = new AnalyticsSyncService();
        logger.log("Using default receiver URL\n");
      }

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

  /**
   * Get receiver URL from preferences
   */
  private String getReceiverUrlFromPreference() {
    try {
      return null;
    } catch (Exception e) {
      log.warn("Could not load receiver URL from preferences", e);
      return null;
    }
  }
}
