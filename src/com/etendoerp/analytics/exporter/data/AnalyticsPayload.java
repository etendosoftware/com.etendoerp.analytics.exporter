package com.etendoerp.analytics.exporter.data;

import java.util.ArrayList;
import java.util.List;

import org.openbravo.model.ad.access.Session;
import org.openbravo.model.ad.access.SessionUsageAudit;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Main payload structure for analytics data
 * Uses Etendo generated entities
 */
public class AnalyticsPayload {

  @JsonProperty("schema_version")
  private String schemaVersion;

  private PayloadMetadata metadata;

  private List<Session> sessions;

  @JsonProperty("usage_audits")
  private List<SessionUsageAudit> usageAudits;

  /**
   * Default constructor that initializes the payload with schema version and empty collections.
   */
  public AnalyticsPayload() {
    this.schemaVersion = "1.0";
    this.metadata = new PayloadMetadata();
    this.sessions = new ArrayList<>();
    this.usageAudits = new ArrayList<>();
  }

  public String getSchemaVersion() {
    return schemaVersion;
  }

  public void setSchemaVersion(String schemaVersion) {
    this.schemaVersion = schemaVersion;
  }

  public PayloadMetadata getMetadata() {
    return metadata;
  }

  public void setMetadata(PayloadMetadata metadata) {
    this.metadata = metadata;
  }

  public List<Session> getSessions() {
    return sessions;
  }

  public void setSessions(List<Session> sessions) {
    this.sessions = sessions;
  }

  public List<SessionUsageAudit> getUsageAudits() {
    return usageAudits;
  }

  public void setUsageAudits(List<SessionUsageAudit> usageAudits) {
    this.usageAudits = usageAudits;
  }
}
