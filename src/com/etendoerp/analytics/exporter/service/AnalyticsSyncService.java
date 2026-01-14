package com.etendoerp.analytics.exporter.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.dal.core.OBContext;
import org.openbravo.dal.service.OBCriteria;
import org.openbravo.dal.service.OBDal;
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

  private static final int DEFAULT_DAYS_EXPORT = 7;
  public static final String JOB_ID = "Job ID: ";
  public static final String SUCCESS = "SUCCESS";

  private final DataExtractionService extractionService;
  private final ReceiverHttpClient httpClient;
  private ProcessLogger processLogger;

  public AnalyticsSyncService() {
    this.extractionService = new DataExtractionService();
    this.httpClient = new ReceiverHttpClient();
  }

  public AnalyticsSyncService(String receiverUrl) {
    this.extractionService = new DataExtractionService();
    this.httpClient = new ReceiverHttpClient(receiverUrl);
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

  private void logWarn(String message) {
    log.warn(message);
    if (processLogger != null) {
      processLogger.log("WARNING: " + message + "\n");
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

    try {
      // Get instance name
      String instanceName = getInstanceName();
      logDebug("Instance name: " + instanceName);

      // Get last sync timestamp for this sync type
      SyncState lastSync = getLastSyncState(syncType);
      Timestamp lastSyncTimestamp = lastSync != null ? lastSync.getLastSyncTimestamp() : null;

      if (lastSyncTimestamp != null) {
        logDebug("Found last successful sync at: " + lastSyncTimestamp);
      } else {
        logDebug("No previous successful sync found for " + syncType);
      }

      String payloadJson;
      int recordsCount = 0;

      // Execute different extraction based on sync type
      if (StringUtils.equals(SYNC_TYPE_SESSION_USAGE_AUDITS, syncType)) {
        // Extract sessions and audits (combined in single payload)
        Integer daysToExport = null;
        if (lastSyncTimestamp == null) {
          daysToExport = DEFAULT_DAYS_EXPORT;
          logDebug("No previous sync found, exporting last " + daysToExport + " days");
        } else {
          logDebug("Incremental sync from: " + lastSyncTimestamp + " to now");
        }

        logDebug("Extracting sessions and usage audits...");
        AnalyticsPayload payload = extractionService.extractAnalyticsData(
            instanceName,
            lastSyncTimestamp,
            daysToExport
        );

        result.setSessionsCount(payload.getSessions().size());
        result.setAuditsCount(payload.getUsageAudits().size());
        recordsCount = result.getSessionsCount() + result.getAuditsCount();
        logDebug("Extraction complete: " + result.getSessionsCount() + " sessions, " +
            result.getAuditsCount() + " audits");

        // Build JSON manually from Etendo entities
        payloadJson = buildSessionsPayload(payload);

      } else if (StringUtils.equals(SYNC_TYPE_MODULE_METADATA, syncType)) {
        // Extract module metadata
        if (lastSyncTimestamp == null) {
          logDebug("No previous sync found, exporting all active modules");
        } else {
          logDebug("Incremental sync: exporting modules installed after " + lastSyncTimestamp);
        }

        logDebug("Extracting module metadata...");
        List<Module> modules = extractionService.extractModuleMetadata(lastSyncTimestamp);
        result.setModulesCount(modules.size());
        recordsCount = modules.size();
        logDebug("Extraction complete: " + recordsCount + " modules");

        // Build MODULE_METADATA payload
        payloadJson = buildModuleMetadataPayload(instanceName, modules);

      } else {
        throw new IllegalArgumentException("Unknown sync type: " + syncType);
      }

      // Log the JSON payload for debugging
      logDebug("========== JSON PAYLOAD START ==========");
      logDebug(payloadJson);
      logDebug("========== JSON PAYLOAD END ==========");

      // Send data to receiver
      logDebug("Sending data to receiver...");
      ReceiverHttpClient.ReceiverResponse response = httpClient.sendPayload(payloadJson);
      result.setJobId(response.getJobId());
      logDebug("Data sent successfully. Job ID: " + result.getJobId());

      result.setStatus(SUCCESS);
      result.setMessage(
          "[" + syncType + "] Successfully sent " + recordsCount + " records. Job ID: " + result.getJobId());

      // Set endTime before persisting
      result.setEndTime(Timestamp.from(Instant.now()));

      // Persist sync state
      logDebug("Persisting sync state...");
      persistSyncState(syncType, result);
      logDebug("Sync state persisted");

    } catch (Exception e) {
      logError("Synchronization failed: " + e.getMessage());
      log.error("Synchronization failed", e);
      result.setEndTime(Timestamp.from(Instant.now())); // Set endTime before persisting
      result.setStatus("FAILED");
      result.setMessage("[" + syncType + "] Error: " + e.getMessage());
      result.setError(e);

      // Try to persist failed state
      try {
        persistSyncState(syncType, result);
      } catch (Exception persistError) {
        logError("Failed to persist error state: " + persistError.getMessage());
        log.error("Failed to persist error state", persistError);
      }
    } finally {
      if (result.getEndTime() == null) {
        result.setEndTime(Timestamp.from(Instant.now()));
      }
      long duration = result.getEndTime().getTime() - result.getStartTime().getTime();
      logDebug("=== Synchronization Complete [" + syncType + "] === Status: " + result.getStatus() +
          " | Duration: " + duration + " ms");
    }

    return result;
  }

  /**
   * Build JSON payload for SESSION/USAGE_AUDITS sync types
   * Converts Etendo entities to the expected JSON format
   */
  private String buildSessionsPayload(AnalyticsPayload payload) throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode root = mapper.createObjectNode();

    // Schema and metadata
    root.put("schema_version", payload.getSchemaVersion());

    ObjectNode metadata = root.putObject("metadata");
    metadata.put("source_instance", payload.getMetadata().getSourceInstance());
    metadata.put("export_timestamp", payload.getMetadata().getExportTimestamp());
    metadata.put("exporter_version", payload.getMetadata().getExporterVersion());
    if (payload.getMetadata().getDaysExported() != null) {
      metadata.put("days_exported", payload.getMetadata().getDaysExported());
    }

    // Sessions array
    ArrayNode sessionsArray = root.putArray("sessions");
    for (org.openbravo.model.ad.access.Session session : payload.getSessions()) {
      ObjectNode sessionNode = mapper.createObjectNode();
      sessionNode.put("session_id", session.getId());
      sessionNode.put("username", session.getUsername());
      sessionNode.put("user_id", session.getCreatedBy() != null ? session.getCreatedBy().getId() : null);
      sessionNode.put("login_time", formatTimestamp(session.getCreationDate()));
      sessionNode.put("logout_time", !session.isSessionActive() ? formatTimestamp(session.getLastPing()) : null);
      sessionNode.put("session_active", session.isSessionActive());
      sessionNode.put("login_status", mapLoginStatus(session.getLoginStatus()));
      sessionNode.put("server_url", session.getServerUrl());
      sessionNode.put("created", formatTimestamp(session.getCreationDate()));
      sessionNode.put("created_by", session.getCreatedBy() != null ? session.getCreatedBy().getId() : null);
      sessionNode.put("updated", formatTimestamp(session.getUpdated()));
      sessionNode.put("updated_by", session.getUpdatedBy() != null ? session.getUpdatedBy().getId() : null);
      sessionNode.put("ip", session.getRemoteAddress());
      sessionsArray.add(sessionNode);
    }

    // Usage audits array  
    ArrayNode auditsArray = root.putArray("usage_audits");
    for (SessionUsageAudit audit : payload.getUsageAudits()) {
      ObjectNode auditNode = mapper.createObjectNode();
      auditNode.put("usage_audit_id", audit.getId());
      auditNode.put("session_id", audit.getSession() != null ? audit.getSession().getId() : null);
      auditNode.put("username", audit.getSession() != null ? audit.getSession().getUsername() : null);
      auditNode.put("command", audit.getCommand());
      auditNode.put("execution_time", formatTimestamp(audit.getCreationDate()));
      auditNode.put("process_time_ms", audit.getProcessTime() != null ? audit.getProcessTime().doubleValue() : null);

      // Determine object type and fetch window/process information
      // Based on SQL: command='DEFAULT' means Process (P), otherwise Window (W)
      String objectType = StringUtils.equals("DEFAULT", audit.getCommand()) ? "P" : "W";

      String moduleId = null;
      String moduleName = null;
      String moduleJavapackage = null;
      String moduleVersion = null;
      String windowId = null;
      String windowName = null;
      String processId = null;
      String processName = null;

      if (StringUtils.equals("W", objectType)) {
        // It's a window - object_id points to ad_tab_id
        // Need to: Tab -> Window -> Module
        try {
          Tab tab = OBDal.getInstance().get(Tab.class, audit.getObject());
          if (tab != null && tab.getWindow() != null) {
            Window window = tab.getWindow();
            windowId = window.getId();
            windowName = window.getName();
            // Get module from window
            if (window.getModule() != null) {
              moduleId = window.getModule().getId();
              moduleName = window.getModule().getName();
              moduleJavapackage = window.getModule().getJavaPackage();
              moduleVersion = window.getModule().getVersion();
            }
          }
        } catch (Exception e) {
          log.warn("Could not fetch window info for tab object_id: " + audit.getObject(), e);
        }
      } else if (StringUtils.equals("P", objectType)) {
        // It's a process - object_id points directly to ad_process_id
        try {
          Process process = OBDal.getInstance().get(Process.class, audit.getObject());
          if (process != null) {
            processId = process.getId();
            processName = process.getName();
            // Get module from process
            if (process.getModule() != null) {
              moduleId = process.getModule().getId();
              moduleName = process.getModule().getName();
              moduleJavapackage = process.getModule().getJavaPackage();
              moduleVersion = process.getModule().getVersion();
            }
          }
        } catch (Exception e) {
          log.warn("Could not fetch process info for object_id: " + audit.getObject(), e);
        }
      }

      // Set module fields (from window/process module, not audit module)
      auditNode.put("module_id", moduleId);
      auditNode.put("module_name", moduleName);
      auditNode.put("module_javapackage", moduleJavapackage);
      auditNode.put("module_version", moduleVersion);

      // Get core version
      try {
        Module coreModule = OBDal.getInstance().get(Module.class, "0");
        auditNode.put("core_version", coreModule != null ? coreModule.getVersion() : "");
      } catch (Exception e) {
        auditNode.put("core_version", "");
      }

      auditNode.put("object_id", audit.getObject());
      auditNode.put("object_type", objectType);
      auditNode.put("window_id", windowId);
      auditNode.put("window_name", windowName);
      auditNode.put("process_id", processId);
      auditNode.put("process_name", processName);
      auditNode.put("record_count", 0);
      auditNode.put("created", formatTimestamp(audit.getCreationDate()));
      auditNode.put("created_by", audit.getCreatedBy() != null ? audit.getCreatedBy().getId() : null);
      auditNode.put("ip", audit.getSession() != null ? audit.getSession().getRemoteAddress() : null);
      auditsArray.add(auditNode);
    }

    return mapper.writeValueAsString(root);
  }

  /**
   * Format date to ISO 8601 format
   */
  private String formatTimestamp(java.util.Date date) {
    if (date == null) return null;
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return sdf.format(date);
  }

  /**
   * Map internal login status codes to external format
   */
  private String mapLoginStatus(String status) {
    if (status == null) return "UNKNOWN";
    switch (status) {
      case "S":
        return SUCCESS;
      case "F":
        return "FAILED";
      case "L":
        return "LOCKED";
      default:
        return status;
    }
  }

  /**
   * Build JSON payload for MODULE_METADATA sync type
   * Format follows module_metadata_v1 schema
   */
  private String buildModuleMetadataPayload(String instanceName, List<Module> modules) throws JsonProcessingException {
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

  /**
   * Get the Etendo instance name
   */
  private String getInstanceName() {
    String accountID = "";
    try {
      // Try to get from system info or client name
      OBContext.setAdminMode(true);
      accountID = SystemInfo.getSystemIdentifier();
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
        state.setLastJobId(null);
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
  private void persistSyncState(String syncType, SyncResult result) {
    try {
      OBContext.setAdminMode(true);

      // Create new AnalyticsSync object
      AnalyticsSync syncRecord = OBProvider.getInstance().get(AnalyticsSync.class);

      // Set required fields
      syncRecord.setClient(OBDal.getInstance().get(Client.class, "0"));
      syncRecord.setOrganization(
          OBDal.getInstance().get(org.openbravo.model.common.enterprise.Organization.class, "0"));
      syncRecord.setActive(true);

      // Set sync specific fields
      syncRecord.setSyncType(syncType);
      // Convert Timestamp to Date for entity
      java.util.Date lastSyncDate = result.getEndTime() != null ? new java.util.Date(
          result.getEndTime().getTime()) : null;
      log.info("Setting lastSync date: {} (from endTime: {})", lastSyncDate, result.getEndTime());
      syncRecord.setLastSync(lastSyncDate);
      syncRecord.setLastStatus(result.getStatus());

      // Build log message
      StringBuilder logMessage = new StringBuilder();
      logMessage.append(JOB_ID).append(result.getJobId() != null ? result.getJobId() : "N/A").append("\n");
      if (result.getSessionsCount() > 0) {
        logMessage.append("Sessions: ").append(result.getSessionsCount()).append("\n");
      }
      if (result.getAuditsCount() > 0) {
        logMessage.append("Audits: ").append(result.getAuditsCount()).append("\n");
      }
      if (result.getModulesCount() > 0) {
        logMessage.append("Modules: ").append(result.getModulesCount()).append("\n");
      }
      logMessage.append("Message: ").append(result.getMessage());
      if (result.getError() != null) {
        logMessage.append("\nError: ").append(result.getError().getMessage());
      }

      syncRecord.setLog(logMessage.toString());

      // Save to database
      OBDal.getInstance().save(syncRecord);
      OBDal.getInstance().flush();

      // Verify the value was persisted
      java.util.Date verifyDate = syncRecord.getLastSync();
      log.info("Persisted sync state record with ID: {} | LastSync after save: {}", syncRecord.getId(), verifyDate);
      if (verifyDate == null) {
        log.warn("WARNING: LastSync is still NULL after persist!");
      }

    } finally {
      OBContext.restorePreviousMode();
    }
  }

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

    // Extract job_id from log
    String log = lastSync.getLog();
    if (log != null && log.contains(JOB_ID)) {
      String jobId = log.substring(log.indexOf(JOB_ID) + 8);
      if (jobId.contains("\n")) {
        jobId = jobId.substring(0, jobId.indexOf("\n"));
      }
      state.setLastJobId(jobId.trim().equals("N/A") ? null : jobId.trim());
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
