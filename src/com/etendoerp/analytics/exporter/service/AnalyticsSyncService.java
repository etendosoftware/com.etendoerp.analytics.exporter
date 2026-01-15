package com.etendoerp.analytics.exporter.service;

import java.sql.Timestamp;
import java.time.Instant;
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

  private static final int DEFAULT_DAYS_EXPORT = 7;
  public static final String JOB_ID = "Job ID: ";
  public static final String SUCCESS = "SUCCESS";
  public static final String FAILED = "FAILED";

  private final DataExtractionService extractionService;
  private final ReceiverHttpClient httpClient;
  private ProcessLogger processLogger;

  /**
   * Default constructor that initializes the service with default receiver URL.
   */
  public AnalyticsSyncService() {
    this.extractionService = new DataExtractionService();
    this.httpClient = new ReceiverHttpClient();
  }

  /**
   * Constructor that allows specifying a custom receiver URL.
   * @param receiverUrl the URL of the receiver service
   */
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
      String instanceName = getInstanceName();
      logDebug("Instance name: " + instanceName);

      SyncState lastSync = getLastSyncState(syncType);
      Timestamp lastSyncTimestamp = lastSync != null ? lastSync.getLastSyncTimestamp() : null;
      logLastSyncInfo(lastSyncTimestamp, syncType);

      String payloadJson = executeSyncByType(syncType, instanceName, lastSyncTimestamp, result);
      
      int recordsCount = calculateRecordsCount(result);
      if (recordsCount == 0) {
        result.setStatus(SUCCESS);
        result.setMessage("No new data to sync for " + syncType);
        logDebug("No new data found, skipping transmission");
        return result;
      }

      return sendPayloadAndSaveState(payloadJson, recordsCount, syncType, result);

    } catch (Exception e) {
      return handleSyncError(result, syncType, e);
    } finally {
      result.setEndTime(Timestamp.from(Instant.now()));
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
    if (StringUtils.equals(SYNC_TYPE_SESSION_USAGE_AUDITS, syncType)) {
      return executeSessionUsageSync(instanceName, lastSyncTimestamp, result);
    } else if (StringUtils.equals(SYNC_TYPE_MODULE_METADATA, syncType)) {
      return executeModuleMetadataSync(instanceName, lastSyncTimestamp, result);
    } else {
      throw new IllegalArgumentException("Unknown sync type: " + syncType);
    }
  }

  private String executeSessionUsageSync(String instanceName, Timestamp lastSyncTimestamp, SyncResult result) 
      throws JsonProcessingException {
    Integer daysToExport = determineDaysToExport(lastSyncTimestamp);
    
    logDebug("Extracting sessions and usage audits...");
    AnalyticsPayload payload = extractionService.extractAnalyticsData(
        instanceName,
        lastSyncTimestamp,
        daysToExport
    );

    result.setSessionsCount(payload.getSessions().size());
    result.setAuditsCount(payload.getUsageAudits().size());
    logDebug("Extraction complete: " + result.getSessionsCount() + " sessions, " +
        result.getAuditsCount() + " audits");

    return buildSessionsPayload(payload);
  }

  private Integer determineDaysToExport(Timestamp lastSyncTimestamp) {
    if (lastSyncTimestamp == null) {
      logDebug("No previous sync found, exporting last " + DEFAULT_DAYS_EXPORT + " days");
      return DEFAULT_DAYS_EXPORT;
    } else {
      logDebug("Incremental sync from: " + lastSyncTimestamp + " to now");
      return null;
    }
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

  private SyncResult sendPayloadAndSaveState(String payloadJson, int recordsCount, String syncType, SyncResult result) 
      throws Exception {
    logDebug("Sending " + recordsCount + " records to receiver...");
    ReceiverHttpClient.ReceiverResponse response = httpClient.sendPayload(payloadJson);

    result.setJobId(response.getJobId());
    result.setStatus(SUCCESS);
    result.setMessage("Data exported successfully. Job ID: " + response.getJobId());
    logDebug("Data sent successfully. Job ID: " + response.getJobId());

    saveSuccessfulSync(syncType, response.getJobId(), recordsCount);
    return result;
  }

  private SyncResult handleSyncError(SyncResult result, String syncType, Exception e) {
    log.error("Error during " + syncType + " sync", e);
    result.setStatus(FAILED);
    result.setMessage("Sync failed: " + e.getMessage());
    result.setError(e);
    saveFailedSync(syncType, e.getMessage());
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

  private void buildSessionsArray(ObjectNode root, List<org.openbravo.model.ad.access.Session> sessions, ObjectMapper mapper) {
    ArrayNode sessionsArray = root.putArray("sessions");
    for (org.openbravo.model.ad.access.Session session : sessions) {
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
    auditNode.put("execution_time", formatTimestamp(audit.getCreationDate()));
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
    auditNode.put("created", formatTimestamp(audit.getCreationDate()));
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
        return FAILED;
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

  private void saveSuccessfulSync(String syncType, String jobId, int recordsCount) {
    try {
      OBContext.setAdminMode(true);
      AnalyticsSync syncRecord = OBProvider.getInstance().get(AnalyticsSync.class);
      
      syncRecord.setClient(OBDal.getInstance().get(Client.class, "0"));
      syncRecord.setOrganization(
          OBDal.getInstance().get(org.openbravo.model.common.enterprise.Organization.class, "0"));
      syncRecord.setActive(true);
      syncRecord.setSyncType(syncType);
      syncRecord.setLastSync(new java.util.Date());
      syncRecord.setLastStatus(SUCCESS);
      
      StringBuilder logMessage = new StringBuilder();
      logMessage.append(JOB_ID).append(jobId).append("\n");
      logMessage.append("Records: ").append(recordsCount);
      syncRecord.setLog(logMessage.toString());
      
      OBDal.getInstance().save(syncRecord);
      OBDal.getInstance().flush();
      logDebug("Sync state persisted with job ID: " + jobId);
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private void saveFailedSync(String syncType, String errorMessage) {
    try {
      OBContext.setAdminMode(true);
      AnalyticsSync syncRecord = OBProvider.getInstance().get(AnalyticsSync.class);
      
      syncRecord.setClient(OBDal.getInstance().get(Client.class, "0"));
      syncRecord.setOrganization(
          OBDal.getInstance().get(org.openbravo.model.common.enterprise.Organization.class, "0"));
      syncRecord.setActive(true);
      syncRecord.setSyncType(syncType);
      syncRecord.setLastSync(new java.util.Date());
      syncRecord.setLastStatus(FAILED);
      syncRecord.setLog("Error: " + errorMessage);
      
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
