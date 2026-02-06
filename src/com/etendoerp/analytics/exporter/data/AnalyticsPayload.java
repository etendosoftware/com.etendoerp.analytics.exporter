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
