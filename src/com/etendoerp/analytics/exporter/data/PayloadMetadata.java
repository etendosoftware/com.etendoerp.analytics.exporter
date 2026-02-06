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

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Metadata section of the analytics payload
 */
public class PayloadMetadata {
  
  @JsonProperty("source_instance")
  private String sourceInstance;
  
  @JsonProperty("export_timestamp")
  private String exportTimestamp;
  
  @JsonProperty("exporter_version")
  private String exporterVersion;
  
  @JsonProperty("days_exported")
  private Integer daysExported;
  
  /**
   * Default constructor that initializes metadata with default exporter version.
   */
  public PayloadMetadata() {
    this.exporterVersion = "1.0.0";
  }
  
  public String getSourceInstance() {
    return sourceInstance;
  }
  
  public void setSourceInstance(String sourceInstance) {
    this.sourceInstance = sourceInstance;
  }
  
  public String getExportTimestamp() {
    return exportTimestamp;
  }
  
  public void setExportTimestamp(String exportTimestamp) {
    this.exportTimestamp = exportTimestamp;
  }
  
  public String getExporterVersion() {
    return exporterVersion;
  }
  
  public void setExporterVersion(String exporterVersion) {
    this.exporterVersion = exporterVersion;
  }
  
  public Integer getDaysExported() {
    return daysExported;
  }
  
  public void setDaysExported(Integer daysExported) {
    this.daysExported = daysExported;
  }
}
