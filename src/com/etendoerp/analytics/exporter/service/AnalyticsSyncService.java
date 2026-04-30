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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
import org.openbravo.erpCommon.obps.ActivationKey;
import org.openbravo.erpCommon.utility.SystemInfo;
import org.openbravo.model.ad.access.SessionUsageAudit;
import org.openbravo.model.ad.module.Module;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.ad.ui.Process;
import org.openbravo.model.ad.ui.Tab;
import org.openbravo.model.ad.ui.Window;
import org.openbravo.scheduling.ProcessLogger;

import com.etendoerp.analytics.exporter.data.AnalyticsPayload;
import com.etendoerp.analytics.exporter.data.AnalyticsSync;
import com.etendoerp.analytics.exporter.data.PayloadMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Main service for synchronizing analytics data with receiver
 * Manages the complete export cycle: extract -> send -> persist state
 */
public class AnalyticsSyncService {

  private static final Logger log = LogManager.getLogger();

  // Sync type constants (must match values in AD_REF_LIST)
  public static final String SYNC_TYPE_SESSION_USAGE_AUDITS = "SESSION_USAGE_AUDITS";
  public static final String SYNC_TYPE_MODULE_METADATA = "MODULE_METADATA";

  public static final String JOB_ID = "Job ID: ";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";
  private static final String CHUNK_LABEL_PREFIX = "Chunk ";
  private static final String RECORDS_LABEL = "Records: ";
  private static final String AUDITS_LABEL = " | Audits: ";
  private static final String RESULT_LABEL = "Result: ";
  private static final int MAX_PERSISTED_LOG_LENGTH = 3900;

  private final DataExtractionService extractionService;
  private final ReceiverHttpClient httpClient;
  private final AnalyticsExporterConfigService configService;
  private ProcessLogger processLogger;

  /**
   * Default constructor that initializes the service.
   * ReceiverHttpClient will use preference URL if configured, otherwise default URL.
   */
  public AnalyticsSyncService() {
    this.configService = new AnalyticsExporterConfigService();
    this.extractionService = new DataExtractionService();
    this.httpClient = new ReceiverHttpClient(null, this.configService);
  }

  /**
   * Constructor that allows specifying a custom receiver URL.
   *
   * @param receiverUrl
   *     the URL of the receiver service
   */
  public AnalyticsSyncService(String receiverUrl) {
    this.configService = new AnalyticsExporterConfigService();
    this.extractionService = new DataExtractionService();
    this.httpClient = new ReceiverHttpClient(receiverUrl, this.configService);
  }

  /**
   * Set process logger for background process execution
   */
  public void setProcessLogger(ProcessLogger logger) {
    this.processLogger = logger;
  }

  /**
   * Log to both log4j and process logger if available
   */
  private void logDebug(String message) {
    log.debug(message);
    if (processLogger != null) {
      processLogger.log(message + "\n");
    }
  }

  private void logError(String message) {
    log.error(message);
    if (processLogger != null) {
      processLogger.log("ERROR: " + message + "\n");
    }
  }

  /**
   * Execute synchronization process for a specific sync type
   *
   * @param syncType
   *     Type of sync to execute (SESSION, USAGE_AUDITS, or MODULE_METADATA)
   * @return SyncResult with details of the operation
   */
  public SyncResult executeSync(String syncType) {
    logDebug("=== Starting Analytics Synchronization [" + syncType + "] ===");
    SyncResult result = new SyncResult();
    result.setStartTime(Timestamp.from(Instant.now()));
    SyncExecutionTrace trace = new SyncExecutionTrace(syncType, result.getStartTime());

    try {
      String instanceName = getInstanceName();
      logDebug("Instance name: " + instanceName);
      trace.addSummary("Instance Name: " + StringUtils.defaultIfBlank(instanceName, "N/A"));

      SyncState lastSync = getLastSyncState(syncType);
      Timestamp lastSyncTimestamp = lastSync != null ? lastSync.getLastSyncTimestamp() : null;
      logLastSyncInfo(lastSyncTimestamp, syncType);
      trace.addSummary("Previous Successful Sync: "
          + (lastSyncTimestamp != null ? AnalyticsSyncSupport.formatTimestamp(lastSyncTimestamp) : "N/A"));

      AnalyticsExporterConfigService.EffectiveConfig config = configService.getEffectiveConfig();
      trace.setDetailedLoggingEnabled(config.isDetailedLoggingEnabled());
      logEffectiveConfig(config);
      trace.addSummary("Effective Config: Source=" + (config.isConfigured() ? "window" : "defaults")
          + (StringUtils.isNotBlank(config.getConfigRecordId()) ? " | ConfigID=" + config.getConfigRecordId() : "")
          + " | InitialExportDays=" + config.getInitialExportDays()
          + " | ChunkDays=" + config.getChunkDays()
          + " | MaxRetries=" + config.getMaxRetries()
          + " | RetryDelayMs=" + config.getRetryDelayMs()
          + " | ConnectTimeoutMs=" + config.getConnectTimeoutMs()
          + " | ReadTimeoutMs=" + config.getReadTimeoutMs()
          + " | DetailedLogging=" + config.isDetailedLoggingEnabled());

      if (StringUtils.equals(SYNC_TYPE_SESSION_USAGE_AUDITS, syncType)) {
        return executeSessionUsageSync(instanceName, lastSyncTimestamp, result, config, trace);
      }

      String payloadJson = executeSyncByType(syncType, instanceName, lastSyncTimestamp, result);

      int recordsCount = calculateRecordsCount(result);
      if (recordsCount == 0) {
        result.setStatus(SUCCESS);
        result.setMessage("No new data to sync for " + syncType);
        trace.addSummary(RESULT_LABEL + "No new data to synchronize");
        trace.addSummary("Modules: " + result.getModulesCount());
        logDebug("No new data found, skipping transmission");
        return result;
      }

      return sendPayloadAndSaveState(payloadJson, recordsCount, syncType, result, trace);

    } catch (Exception e) {
      return handleSyncError(result, syncType, e, trace);
    } finally {
      result.setEndTime(Timestamp.from(Instant.now()));
      trace.setEndTime(result.getEndTime());
    }
  }

  private void logLastSyncInfo(Timestamp lastSyncTimestamp, String syncType) {
    if (lastSyncTimestamp != null) {
      logDebug("Found last successful sync at: " + lastSyncTimestamp);
    } else {
      logDebug("No previous successful sync found for " + syncType);
    }
  }

  private String executeSyncByType(String syncType, String instanceName, Timestamp lastSyncTimestamp, SyncResult result)
      throws JsonProcessingException {
    if (StringUtils.equals(SYNC_TYPE_MODULE_METADATA, syncType)) {
      return executeModuleMetadataSync(instanceName, lastSyncTimestamp, result);
    }
    throw new IllegalArgumentException("Unknown sync type: " + syncType);
  }

  private SyncResult executeSessionUsageSync(String instanceName, Timestamp lastSyncTimestamp, SyncResult result,
      AnalyticsExporterConfigService.EffectiveConfig config, SyncExecutionTrace trace) throws Exception {
    List<TimeWindow> windows = buildSessionUsageWindows(lastSyncTimestamp, config);
    logDebug("Prepared " + windows.size() + " session usage chunk(s) for export");
    trace.addSummary("Prepared Chunks: " + windows.size());

    List<String> jobIds = new ArrayList<>();
    int sentChunks = 0;
    int skippedChunks = 0;

    for (int i = 0; i < windows.size(); i++) {
      TimeWindow window = windows.get(i);
      logDebug("Extracting chunk " + (i + 1) + "/" + windows.size() + ": " + window.describe());
      trace.addDetail("Preparing " + CHUNK_LABEL_PREFIX + (i + 1) + "/" + windows.size() + " | " + window.describe());

      long extractionStart = System.nanoTime();
      AnalyticsPayload payload = extractionService.extractAnalyticsDataForWindow(
          instanceName,
          window.getStartExclusive(),
          window.getEndInclusive(),
          window.getDaysExportedMetadata()
      );
      long extractionMs = AnalyticsSyncSupport.elapsedMillis(extractionStart);

      int chunkSessions = payload.getSessions().size();
      int chunkAudits = payload.getUsageAudits().size();
      int chunkRecords = chunkSessions + chunkAudits;

      if (chunkRecords == 0) {
        skippedChunks++;
        String emptyChunkMessage = CHUNK_LABEL_PREFIX + (i + 1) + "/" + windows.size()
            + " has no new data, skipping transmission";
        logDebug(emptyChunkMessage);
        trace.addDetail(emptyChunkMessage);
        continue;
      }

      long payloadBuildStart = System.nanoTime();
      String payloadJson = buildSessionsPayload(payload);
      long payloadBuildMs = AnalyticsSyncSupport.elapsedMillis(payloadBuildStart);
      int payloadBytes = payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
      logDebug("Sending " + CHUNK_LABEL_PREFIX.toLowerCase() + (i + 1) + "/" + windows.size() + " with " + chunkSessions +
          " sessions and " + chunkAudits + " audits");

      ReceiverHttpClient.ReceiverResponse response = httpClient.sendPayload(payloadJson);
      sentChunks++;
      jobIds.add(response.getJobId());
      result.setSessionsCount(result.getSessionsCount() + chunkSessions);
      result.setAuditsCount(result.getAuditsCount() + chunkAudits);
      result.setJobId(response.getJobId());

      String chunkMetrics = CHUNK_LABEL_PREFIX + (i + 1) + "/" + windows.size() + " | " + window.describe() +
          " | Sessions: " + chunkSessions + AUDITS_LABEL + chunkAudits +
          " | PayloadBytes: " + payloadBytes + " | ExtractMs: " + extractionMs +
          " | PayloadBuildMs: " + payloadBuildMs + " | PostMs: " + response.getRequestDurationMs() +
          " | StatusCode: " + response.getHttpStatusCode() + " | QueuePosition: " + response.getQueuePosition();
      logDebug(chunkMetrics);
      trace.addDetail(chunkMetrics);
      if (config.isDetailedLoggingEnabled()) {
        String responseDetails = CHUNK_LABEL_PREFIX + (i + 1) + "/" + windows.size() + " receiver response | Job ID: "
            + response.getJobId() + " | Status: " + response.getStatus() + " | Message: " + response.getMessage();
        logDebug(responseDetails);
        trace.addDetail(responseDetails);
      }

      trace.addSummary("Last Successful Chunk: " + CHUNK_LABEL_PREFIX + (i + 1) + "/" + windows.size()
          + " | Job ID: " + response.getJobId());
      saveSuccessfulSync(
          SYNC_TYPE_SESSION_USAGE_AUDITS,
          response.getJobId(),
          chunkRecords,
          window.getEndInclusive(),
          trace.renderSuccess(result, chunkRecords, sentChunks, skippedChunks)
      );
    }

    result.setStatus(SUCCESS);
    if (sentChunks == 0) {
      result.setMessage("No new data to sync for " + SYNC_TYPE_SESSION_USAGE_AUDITS);
      trace.addSummary(RESULT_LABEL + "No new data to synchronize");
      trace.addSummary("Chunks Sent: 0 | Skipped Empty Chunks: " + skippedChunks);
      logDebug("No new data found across all prepared chunks, skipping transmission");
    } else {
      result.setMessage("Data exported successfully in " + sentChunks + " chunk(s). Skipped empty chunks: " + skippedChunks
          + ". Last Job ID: " + result.getJobId());
      trace.addSummary(RESULT_LABEL + result.getMessage());
      trace.addSummary("Sessions: " + result.getSessionsCount() + AUDITS_LABEL + result.getAuditsCount());
      trace.addSummary("Chunks Sent: " + sentChunks + " | Skipped Empty Chunks: " + skippedChunks);
      trace.addSummary("Job IDs: " + jobIds);
      logDebug("Chunked export completed successfully. Chunks sent: " + sentChunks + ", skipped: " + skippedChunks
          + ", job IDs: " + jobIds);
    }

    return result;
  }

  private List<TimeWindow> buildSessionUsageWindows(Timestamp lastSyncTimestamp,
      AnalyticsExporterConfigService.EffectiveConfig config) {
    List<TimeWindow> windows = new ArrayList<>();
    Timestamp now = Timestamp.from(Instant.now());

    int initialExportDays = config.getInitialExportDays();
    int chunkDays = config.getChunkDays();

    if (lastSyncTimestamp == null) {
      logDebug("No previous sync found, exporting last " + initialExportDays + " day(s) in chunk(s) of " + chunkDays
          + " day(s)");
      Timestamp cursorInclusive = Timestamp.from(Instant.now().minusSeconds(initialExportDays * 24L * 3600L));

      while (cursorInclusive.before(now)) {
        Timestamp nextCursorInclusive = Timestamp.from(
            cursorInclusive.toInstant().plusSeconds(chunkDays * 24L * 3600L));
        Timestamp endInclusive = nextCursorInclusive.before(now)
            ? Timestamp.from(nextCursorInclusive.toInstant().minusMillis(1))
            : now;
        windows.add(new TimeWindow(
            Timestamp.from(cursorInclusive.toInstant().minusMillis(1)),
            endInclusive,
            chunkDays));
        cursorInclusive = nextCursorInclusive;
      }
      return windows;
    }

    logDebug("Incremental sync from: " + lastSyncTimestamp + " to now using chunk(s) of " + chunkDays + " day(s)");
    Timestamp cursorInclusive = Timestamp.from(lastSyncTimestamp.toInstant().plusMillis(1));
    while (cursorInclusive.before(now)) {
      Timestamp nextCursorInclusive = Timestamp.from(
          cursorInclusive.toInstant().plusSeconds(chunkDays * 24L * 3600L));
      Timestamp endInclusive = nextCursorInclusive.before(now)
          ? Timestamp.from(nextCursorInclusive.toInstant().minusMillis(1))
          : now;
      windows.add(new TimeWindow(
          Timestamp.from(cursorInclusive.toInstant().minusMillis(1)),
          endInclusive,
          null));
      cursorInclusive = nextCursorInclusive;
    }
    return windows;
  }

  private void logEffectiveConfig(AnalyticsExporterConfigService.EffectiveConfig config) {
    logDebug("Effective analytics exporter config | Source: " + (config.isConfigured() ? "window" : "defaults")
        + (StringUtils.isNotBlank(config.getConfigRecordId()) ? " | Config ID: " + config.getConfigRecordId() : "")
        + " | InitialExportDays: " + config.getInitialExportDays()
        + " | ChunkDays: " + config.getChunkDays()
        + " | MaxRetries: " + config.getMaxRetries()
        + " | RetryDelayMs: " + config.getRetryDelayMs()
        + " | ConnectTimeoutMs: " + config.getConnectTimeoutMs()
        + " | ReadTimeoutMs: " + config.getReadTimeoutMs()
        + " | DetailedLogging: " + config.isDetailedLoggingEnabled());
  }

  private String executeModuleMetadataSync(String instanceName, Timestamp lastSyncTimestamp, SyncResult result)
      throws JsonProcessingException {
    if (lastSyncTimestamp == null) {
      logDebug("No previous sync found, exporting all active modules");
    } else {
      logDebug("Incremental sync: exporting modules installed after " + lastSyncTimestamp);
    }

    logDebug("Extracting module metadata...");
    List<Module> modules = extractionService.extractModuleMetadata(lastSyncTimestamp);
    result.setModulesCount(modules.size());
    logDebug("Extraction complete: " + result.getModulesCount() + " modules");
    return buildModulesPayload(modules, instanceName);
  }

  private int calculateRecordsCount(SyncResult result) {
    return result.getSessionsCount() + result.getAuditsCount() + result.getModulesCount();
  }

  private SyncResult sendPayloadAndSaveState(String payloadJson, int recordsCount, String syncType, SyncResult result,
      SyncExecutionTrace trace) throws Exception {
    logDebug("Sending " + recordsCount + " records to receiver...");
    ReceiverHttpClient.ReceiverResponse response = httpClient.sendPayload(payloadJson);

    result.setJobId(response.getJobId());
    result.setStatus(SUCCESS);
    result.setMessage("Data exported successfully. Job ID: " + response.getJobId());
    trace.addSummary(RESULT_LABEL + result.getMessage());
    trace.addSummary(RECORDS_LABEL + recordsCount + " | Modules: " + result.getModulesCount());
    trace.addSummary("Receiver Status Code: " + response.getHttpStatusCode() + " | PostMs: "
        + response.getRequestDurationMs() + " | QueuePosition: " + response.getQueuePosition());
    if (trace.isDetailedLoggingEnabled()) {
      trace.addDetail("Receiver response | Job ID: " + response.getJobId() + " | Status: " + response.getStatus()
          + " | Message: " + response.getMessage());
    }
    logDebug("Data sent successfully. Job ID: " + response.getJobId());

    saveSuccessfulSync(syncType, response.getJobId(), recordsCount, Timestamp.from(Instant.now()), trace.renderSuccess(result,
        recordsCount, null, null));
    return result;
  }

  private SyncResult handleSyncError(SyncResult result, String syncType, Exception e, SyncExecutionTrace trace) {
    log.error("Error during " + syncType + " sync", e);
    result.setStatus(FAILED);
    result.setMessage("Sync failed: " + e.getMessage());
    result.setError(e);
    trace.addSummary(RESULT_LABEL + result.getMessage());
    trace.addSummary("Error Type: " + e.getClass().getSimpleName());
    trace.addDetail("Failure details: " + StringUtils.defaultIfBlank(e.getMessage(), "No error message available"));
    saveFailedSync(syncType, e.getMessage(), trace.renderFailure(result));
    return result;
  }

  private String buildSessionsPayload(AnalyticsPayload payload) throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();

    // Schema and metadata
    root.put("schema_version", payload.getSchemaVersion());
    buildMetadataNode(root, payload.getMetadata());
    buildSessionsArray(root, payload.getSessions(), mapper);
    buildAuditsArray(root, payload.getUsageAudits(), mapper);

    return mapper.writeValueAsString(root);
  }

  private void buildMetadataNode(ObjectNode root, PayloadMetadata metadata) {
    ObjectNode metadataNode = root.putObject("metadata");
    metadataNode.put("source_instance", metadata.getSourceInstance());
    metadataNode.put("export_timestamp", metadata.getExportTimestamp());
    metadataNode.put("exporter_version", metadata.getExporterVersion());
    if (metadata.getDaysExported() != null) {
      metadataNode.put("days_exported", metadata.getDaysExported());
    }
  }

  private void buildSessionsArray(ObjectNode root, List<org.openbravo.model.ad.access.Session> sessions,
      ObjectMapper mapper) {
    ArrayNode sessionsArray = root.putArray("sessions");
    for (org.openbravo.model.ad.access.Session session : sessions) {
      ObjectNode sessionNode = mapper.createObjectNode();
      sessionNode.put("session_id", session.getId());
      sessionNode.put("username", session.getUsername());
      sessionNode.put("user_id", session.getCreatedBy() != null ? session.getCreatedBy().getId() : null);
      sessionNode.put("login_time", AnalyticsSyncSupport.formatTimestamp(session.getCreationDate()));
      sessionNode.put("logout_time", !session.isSessionActive() ? AnalyticsSyncSupport.formatTimestamp(session.getLastPing()) : null);
      sessionNode.put("session_active", session.isSessionActive());
      sessionNode.put("login_status", AnalyticsSyncSupport.mapLoginStatus(session.getLoginStatus(), SUCCESS, FAILED));
      sessionNode.put("server_url", session.getServerUrl());
      sessionNode.put("created", AnalyticsSyncSupport.formatTimestamp(session.getCreationDate()));
      sessionNode.put("created_by", session.getCreatedBy() != null ? session.getCreatedBy().getId() : null);
      sessionNode.put("updated", AnalyticsSyncSupport.formatTimestamp(session.getUpdated()));
      sessionNode.put("updated_by", session.getUpdatedBy() != null ? session.getUpdatedBy().getId() : null);
      sessionNode.put("ip", session.getRemoteAddress());
      sessionsArray.add(sessionNode);
    }
  }

  private void buildAuditsArray(ObjectNode root, List<SessionUsageAudit> audits, ObjectMapper mapper) {
    ArrayNode auditsArray = root.putArray("usage_audits");
    for (SessionUsageAudit audit : audits) {
      ObjectNode auditNode = createAuditNode(audit, mapper);
      auditsArray.add(auditNode);
    }
  }

  private ObjectNode createAuditNode(SessionUsageAudit audit, ObjectMapper mapper) {
    ObjectNode auditNode = mapper.createObjectNode();
    auditNode.put("usage_audit_id", audit.getId());
    auditNode.put("session_id", audit.getSession() != null ? audit.getSession().getId() : null);
    auditNode.put("username", audit.getSession() != null ? audit.getSession().getUsername() : null);
    auditNode.put("command", audit.getCommand());
    auditNode.put("execution_time", AnalyticsSyncSupport.formatTimestamp(audit.getCreationDate()));
    auditNode.put("process_time_ms", audit.getProcessTime() != null ? audit.getProcessTime().doubleValue() : null);

    // Determine object type and fetch window/process information
    String objectType = determineObjectType(audit.getCommand());
    AuditObjectInfo objectInfo = fetchAuditObjectInfo(audit.getObject(), objectType);

    populateAuditNodeWithObjectInfo(auditNode, objectInfo, objectType);
    addAuditMetadata(auditNode, audit);

    return auditNode;
  }

  private String determineObjectType(String command) {
    return StringUtils.equals("DEFAULT", command) ? "P" : "W";
  }

  private AuditObjectInfo fetchAuditObjectInfo(String objectId, String objectType) {
    if (StringUtils.equals("W", objectType)) {
      return fetchWindowInfo(objectId);
    } else if (StringUtils.equals("P", objectType)) {
      return fetchProcessInfo(objectId);
    }
    return new AuditObjectInfo();
  }

  private AuditObjectInfo fetchWindowInfo(String tabId) {
    try {
      Tab tab = OBDal.getInstance().get(Tab.class, tabId);
      if (tab != null && tab.getWindow() != null) {
        Window window = tab.getWindow();
        AuditObjectInfo info = new AuditObjectInfo();
        info.windowId = window.getId();
        info.windowName = window.getName();
        if (window.getModule() != null) {
          info.moduleId = window.getModule().getId();
          info.moduleName = window.getModule().getName();
          info.moduleJavapackage = window.getModule().getJavaPackage();
          info.moduleVersion = window.getModule().getVersion();
        }
        return info;
      }
    } catch (Exception e) {
      log.warn("Could not fetch window info for tab object_id: " + tabId, e);
    }
    return new AuditObjectInfo();
  }

  private AuditObjectInfo fetchProcessInfo(String processId) {
    try {
      Process process = OBDal.getInstance().get(Process.class, processId);
      if (process != null) {
        AuditObjectInfo info = new AuditObjectInfo();
        info.processId = process.getId();
        info.processName = process.getName();
        if (process.getModule() != null) {
          info.moduleId = process.getModule().getId();
          info.moduleName = process.getModule().getName();
          info.moduleJavapackage = process.getModule().getJavaPackage();
          info.moduleVersion = process.getModule().getVersion();
        }
        return info;
      }
    } catch (Exception e) {
      log.warn("Could not fetch process info for object_id: " + processId, e);
    }
    return new AuditObjectInfo();
  }

  private void populateAuditNodeWithObjectInfo(ObjectNode auditNode, AuditObjectInfo info, String objectType) {
    auditNode.put("module_id", info.moduleId);
    auditNode.put("module_name", info.moduleName);
    auditNode.put("module_javapackage", info.moduleJavapackage);
    auditNode.put("module_version", info.moduleVersion);
    auditNode.put("object_type", objectType);
    auditNode.put("window_id", info.windowId);
    auditNode.put("window_name", info.windowName);
    auditNode.put("process_id", info.processId);
    auditNode.put("process_name", info.processName);
  }

  private void addAuditMetadata(ObjectNode auditNode, SessionUsageAudit audit) {
    try {
      Module coreModule = OBDal.getInstance().get(Module.class, "0");
      auditNode.put("core_version", coreModule != null ? coreModule.getVersion() : "");
    } catch (Exception e) {
      auditNode.put("core_version", "");
    }

    auditNode.put("object_id", audit.getObject());
    auditNode.put("record_count", 0);
    auditNode.put("created", AnalyticsSyncSupport.formatTimestamp(audit.getCreationDate()));
    auditNode.put("created_by", audit.getCreatedBy() != null ? audit.getCreatedBy().getId() : null);
    auditNode.put("ip", audit.getSession() != null ? audit.getSession().getRemoteAddress() : null);
  }

  /**
   * Helper class to hold audit object information
   */
  private static class AuditObjectInfo {
    String moduleId;
    String moduleName;
    String moduleJavapackage;
    String moduleVersion;
    String windowId;
    String windowName;
    String processId;
    String processName;
  }

  /**
   * Build JSON payload for MODULE_METADATA sync type
   * Format follows module_metadata_v1 schema
   */
  private String buildModulesPayload(List<Module> modules, String instanceName) throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();

    // Schema and metadata
    root.put("schema_version", "module_metadata_v1");

    ObjectNode metadata = root.putObject("metadata");
    metadata.put("source_instance", instanceName);
    metadata.put("check_type", "module_metadata_v1");
    metadata.put("storage_only", true);
    metadata.put("exported_at", java.time.Instant.now().toString());

    // Records array
    ArrayNode records = root.putArray("records");
    for (Module module : modules) {
      ObjectNode moduleNode = mapper.createObjectNode();
      moduleNode.put("ad_module_id", module.getId());
      moduleNode.put("javapackage", module.getJavaPackage());
      moduleNode.put("name", module.getName());
      moduleNode.put("version", module.getVersion());
      moduleNode.put("type", module.getType());
      moduleNode.put("iscommercial", module.isCommercial());
      moduleNode.put("enabled", module.isEnabled());
      records.add(moduleNode);
    }

    return mapper.writeValueAsString(root);
  }

  private String truncatePersistedLog(String logMessage) {
    if (StringUtils.length(logMessage) <= MAX_PERSISTED_LOG_LENGTH) {
      return logMessage;
    }
    return logMessage.substring(0, MAX_PERSISTED_LOG_LENGTH)
        + "\n...[detailed log truncated]";
  }

  private void saveSuccessfulSync(String syncType, String jobId, int recordsCount) {
    saveSuccessfulSync(syncType, jobId, recordsCount, Timestamp.from(Instant.now()), null);
  }

  private void saveSuccessfulSync(String syncType, String jobId, int recordsCount, Timestamp syncTimestamp,
      String details) {
    try {
      OBContext.setAdminMode(true);
      AnalyticsSync syncRecord = OBProvider.getInstance().get(AnalyticsSync.class);

      syncRecord.setClient(OBDal.getInstance().get(Client.class, "0"));
      syncRecord.setOrganization(
          OBDal.getInstance().get(org.openbravo.model.common.enterprise.Organization.class, "0"));
      syncRecord.setActive(true);
      syncRecord.setSyncType(syncType);
      syncRecord.setLastSync(syncTimestamp != null ? new Date(syncTimestamp.getTime()) : new Date());
      syncRecord.setLastJob(jobId);
      syncRecord.setLastStatus(SUCCESS);

      StringBuilder logMessage = new StringBuilder();
      logMessage.append(JOB_ID).append(jobId).append("\n");
      logMessage.append(RECORDS_LABEL).append(recordsCount);
      if (StringUtils.isNotBlank(details)) {
        logMessage.append("\n").append(details);
      }
      syncRecord.setLog(truncatePersistedLog(logMessage.toString()));

      OBDal.getInstance().save(syncRecord);
      OBDal.getInstance().flush();
      logDebug("Sync state persisted with job ID: " + jobId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void saveFailedSync(String syncType, String errorMessage, String details) {
    try {
      OBContext.setAdminMode(true);
      AnalyticsSync syncRecord = OBProvider.getInstance().get(AnalyticsSync.class);

      syncRecord.setClient(OBDal.getInstance().get(Client.class, "0"));
      syncRecord.setOrganization(
          OBDal.getInstance().get(org.openbravo.model.common.enterprise.Organization.class, "0"));
      syncRecord.setActive(true);
      syncRecord.setSyncType(syncType);
      syncRecord.setLastSync(new Date());
      syncRecord.setLastJob(null);
      syncRecord.setLastStatus(FAILED);
      StringBuilder logMessage = new StringBuilder();
      logMessage.append("Error: ").append(errorMessage);
      if (StringUtils.isNotBlank(details)) {
        logMessage.append("\n").append(details);
      }
      syncRecord.setLog(truncatePersistedLog(logMessage.toString()));

      OBDal.getInstance().save(syncRecord);
      OBDal.getInstance().flush();
    } catch (Exception e) {
      logError("Failed to save error state: " + e.getMessage());
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Get the Etendo instance name
   */
  private String getInstanceName() {
    String accountID = "";
    try {
      OBContext.setAdminMode(true);
      // First try ActivationKey customer. If ActivationKey cannot be initialized in this runtime,
      // continue with SystemInfo fallback instead of returning an empty instance name.
      try {
        ActivationKey ak = ActivationKey.getInstance();
        if (ActivationKey.isActiveInstance()) {
          accountID = ak.getProperty("customer");
        }
      } catch (Throwable e) {
        log.warn("Could not load activation key customer, trying System Identifier fallback", e);
      }

      if (StringUtils.isBlank(accountID)) {
        accountID = SystemInfo.getSystemIdentifier();
      }
      if (StringUtils.isBlank(accountID)) {
        log.warn("Empty System Identifier, Instance Name will be empty");
      }
    } catch (Exception e) {
      log.error("Could not determine instance name, using default", e);
    } finally {
      OBContext.restorePreviousMode();
    }
    return accountID;
  }

  /**
   * Get the last successful sync state for a specific sync type
   */
  private SyncState getLastSyncState(String syncType) {
    try {
      OBContext.setAdminMode(true);

      OBCriteria<AnalyticsSync> criteria = OBDal.getInstance().createCriteria(AnalyticsSync.class);
      criteria.add(Restrictions.eq(AnalyticsSync.PROPERTY_SYNCTYPE, syncType));
      criteria.add(Restrictions.eq(AnalyticsSync.PROPERTY_LASTSTATUS, SUCCESS));
      criteria.add(Restrictions.isNotNull(AnalyticsSync.PROPERTY_LASTSYNC)); // Only records with lastSync set
      criteria.addOrder(Order.desc(AnalyticsSync.PROPERTY_LASTSYNC));
      criteria.setMaxResults(1);

      List<AnalyticsSync> results = criteria.list();
      if (!results.isEmpty()) {
        AnalyticsSync lastSync = results.get(0);
        SyncState state = new SyncState();
        state.setLastSyncTimestamp(
            lastSync.getLastSync() != null ? new Timestamp(lastSync.getLastSync().getTime()) : null);
        state.setLastJobId(StringUtils.trimToNull(lastSync.getLastJob()));
        state.setLastStatus(lastSync.getLastStatus());
        log.info("Found last successful sync for {}: {}", syncType, state.getLastSyncTimestamp());
        return state;
      }

      log.info("No previous successful sync found for {}", syncType);
      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  /**
   * Persist sync state to database
   */
  /**
   * Health check - get last sync status
   */
  public SyncState getHealthStatus() {
    try {
      OBContext.setAdminMode(true);

      OBCriteria<AnalyticsSync> criteria = OBDal.getInstance().createCriteria(AnalyticsSync.class);
      criteria.add(Restrictions.eq(AnalyticsSync.PROPERTY_SYNCTYPE, SYNC_TYPE_SESSION_USAGE_AUDITS));
      criteria.addOrder(Order.desc(AnalyticsSync.PROPERTY_LASTSYNC));
      criteria.setMaxResults(1);

      List<AnalyticsSync> results = criteria.list();
      if (!results.isEmpty()) {
        return getSyncState(results);
      }

      return null;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private static SyncState getSyncState(List<AnalyticsSync> results) {
    AnalyticsSync lastSync = results.get(0);
    SyncState state = new SyncState();
    state.setLastSyncTimestamp(lastSync.getLastSync() != null ? new Timestamp(lastSync.getLastSync().getTime()) : null);
    state.setLastStatus(lastSync.getLastStatus());
    state.setLog(lastSync.getLog());

    state.setLastJobId(StringUtils.trimToNull(lastSync.getLastJob()));

    // Backward-compatible extraction for older records persisted before LAST_JOB was populated.
    if (state.getLastJobId() == null) {
      String log = lastSync.getLog();
      if (log != null && log.contains(JOB_ID)) {
        String jobId = log.substring(log.indexOf(JOB_ID) + 8);
        if (jobId.contains("\n")) {
          jobId = jobId.substring(0, jobId.indexOf("\n"));
        }
        state.setLastJobId(jobId.trim().equals("N/A") ? null : jobId.trim());
      }
    }
    return state;
  }

  /**
   * Result of a sync operation
   */
  public static class SyncResult {
    private Timestamp startTime;
    private Timestamp endTime;
    private String status;
    private String message;
    private String jobId;
    private int sessionsCount;
    private int auditsCount;
    private int modulesCount;
    private Exception error;

    public Timestamp getStartTime() {
      return startTime;
    }

    public void setStartTime(Timestamp startTime) {
      this.startTime = startTime;
    }

    public Timestamp getEndTime() {
      return endTime;
    }

    public void setEndTime(Timestamp endTime) {
      this.endTime = endTime;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getJobId() {
      return jobId;
    }

    public void setJobId(String jobId) {
      this.jobId = jobId;
    }

    public int getSessionsCount() {
      return sessionsCount;
    }

    public void setSessionsCount(int sessionsCount) {
      this.sessionsCount = sessionsCount;
    }

    public int getAuditsCount() {
      return auditsCount;
    }

    public void setAuditsCount(int auditsCount) {
      this.auditsCount = auditsCount;
    }

    public int getModulesCount() {
      return modulesCount;
    }

    public void setModulesCount(int modulesCount) {
      this.modulesCount = modulesCount;
    }

    public Exception getError() {
      return error;
    }

    public void setError(Exception error) {
      this.error = error;
    }
  }

  private static class SyncExecutionTrace {
    private final String syncType;
    private final Timestamp startTime;
    private Timestamp endTime;
    private boolean detailedLoggingEnabled;
    private final StringBuilder summary = new StringBuilder();
    private final StringBuilder details = new StringBuilder();

    private SyncExecutionTrace(String syncType, Timestamp startTime) {
      this.syncType = syncType;
      this.startTime = startTime;
      addSummary("Sync Type: " + syncType);
      addSummary("Started At: " + AnalyticsSyncSupport.formatTimestamp(startTime));
    }

    private boolean isDetailedLoggingEnabled() {
      return detailedLoggingEnabled;
    }

    private void setDetailedLoggingEnabled(boolean detailedLoggingEnabled) {
      this.detailedLoggingEnabled = detailedLoggingEnabled;
    }

    private void setEndTime(Timestamp endTime) {
      this.endTime = endTime;
    }

    private void addSummary(String line) {
      appendLine(summary, line);
    }

    private void addDetail(String line) {
      if (detailedLoggingEnabled) {
        appendLine(details, line);
      }
    }

    private String renderSuccess(SyncResult result, int recordsCount, Integer sentChunks, Integer skippedChunks) {
      return renderCommon(result, recordsCount, sentChunks, skippedChunks);
    }

    private String renderFailure(SyncResult result) {
      return renderCommon(result, result.getSessionsCount() + result.getAuditsCount() + result.getModulesCount(), null, null);
    }

    private String renderCommon(SyncResult result, int recordsCount, Integer sentChunks, Integer skippedChunks) {
      StringBuilder log = new StringBuilder();
      log.append(summary);
      Timestamp effectiveEndTime = endTime != null ? endTime : Timestamp.from(Instant.now());
      appendLine(log, "Finished At: " + AnalyticsSyncSupport.formatTimestamp(effectiveEndTime));
      appendLine(log, "DurationMs: " + Math.max(0L, effectiveEndTime.getTime() - startTime.getTime()));
      appendLine(log, "Status: " + StringUtils.defaultIfBlank(result.getStatus(), "N/A"));
      appendLine(log, RECORDS_LABEL + recordsCount);
      if (result.getSessionsCount() > 0 || StringUtils.equals(syncType, SYNC_TYPE_SESSION_USAGE_AUDITS)) {
        appendLine(log, "Sessions: " + result.getSessionsCount() + AUDITS_LABEL + result.getAuditsCount());
      }
      if (result.getModulesCount() > 0 || StringUtils.equals(syncType, SYNC_TYPE_MODULE_METADATA)) {
        appendLine(log, "Modules: " + result.getModulesCount());
      }
      if (sentChunks != null || skippedChunks != null) {
        appendLine(log, "Chunks Sent: " + defaultInt(sentChunks) + " | Skipped Empty Chunks: " + defaultInt(skippedChunks));
      }
      if (StringUtils.isNotBlank(result.getMessage())) {
        appendLine(log, "Message: " + result.getMessage());
      }
      if (detailedLoggingEnabled && details.length() > 0) {
        log.append("Detailed Execution Log:\n");
        log.append(details);
      }
      return log.toString().trim();
    }

    private int defaultInt(Integer value) {
      return value != null ? value : 0;
    }

    private void appendLine(StringBuilder builder, String line) {
      if (StringUtils.isBlank(line)) {
        return;
      }
      if (builder.length() > 0) {
        builder.append("\n");
      }
      builder.append(line);
    }
  }

  private static class TimeWindow {
    private final Timestamp startExclusive;
    private final Timestamp endInclusive;
    private final Integer daysExportedMetadata;

    private TimeWindow(Timestamp startExclusive, Timestamp endInclusive, Integer daysExportedMetadata) {
      this.startExclusive = startExclusive;
      this.endInclusive = endInclusive;
      this.daysExportedMetadata = daysExportedMetadata;
    }

    public Timestamp getStartExclusive() {
      return startExclusive;
    }

    public Timestamp getEndInclusive() {
      return endInclusive;
    }

    public Integer getDaysExportedMetadata() {
      return daysExportedMetadata;
    }

    /**
     * Describe the time window using inclusive/exclusive bounds for debug logging.
     *
     * @return string in the form ">start .. <=end"
     */
    public String describe() {
      return ">" + startExclusive + " .. <=" + endInclusive;
    }
  }

  /**
   * Persisted sync state
   */
  public static class SyncState {
    private Timestamp lastSyncTimestamp;
    private String lastJobId;
    private String lastStatus;
    private String log;

    public Timestamp getLastSyncTimestamp() {
      return lastSyncTimestamp;
    }

    public void setLastSyncTimestamp(Timestamp lastSyncTimestamp) {
      this.lastSyncTimestamp = lastSyncTimestamp;
    }

    public String getLastJobId() {
      return lastJobId;
    }

    public void setLastJobId(String lastJobId) {
      this.lastJobId = lastJobId;
    }

    public String getLastStatus() {
      return lastStatus;
    }

    public void setLastStatus(String lastStatus) {
      this.lastStatus = lastStatus;
    }

    public String getLog() {
      return log;
    }

    public void setLog(String log) {
      this.log = log;
    }
  }
}
