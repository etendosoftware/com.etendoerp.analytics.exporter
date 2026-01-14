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
