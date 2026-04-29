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
import org.openbravo.model.ad.access.User;
import org.openbravo.model.ad.system.Client;
import org.openbravo.model.common.enterprise.Organization;

import com.etendoerp.analytics.exporter.data.AnalyticsExporterConfig;

/**
 * Resolves runtime configuration for the analytics exporter.
 * <p>
 * Configuration is primarily loaded from the system-level configuration window
 * backed by the {@code ETAE_Analytics_Exporter_Config} table. If no active
 * configuration record exists, safe code defaults are used. Receiver URL keeps
 * backward compatibility with the legacy ETAE_ReceiverURL preference.
 */
public class AnalyticsExporterConfigService {

  private static final Logger log = LogManager.getLogger();

  private static final String SYSTEM_CLIENT_ID = "0";
  private static final String SYSTEM_ORG_ID = "0";
  private static final String SYSTEM_USER_ID = "100";

  static final int DEFAULT_INITIAL_EXPORT_DAYS = 7;
  static final int DEFAULT_CHUNK_DAYS = 1;
  static final int DEFAULT_MAX_RETRIES = 3;
  static final int DEFAULT_RETRY_DELAY_MS = 2000;
  static final int DEFAULT_CONNECT_TIMEOUT_MS = 30000;
  static final int DEFAULT_READ_TIMEOUT_MS = 60000;
  static final boolean DEFAULT_DETAILED_LOGGING_ENABLED = false;

  /**
   * Load effective configuration.
   */
  public EffectiveConfig getEffectiveConfig() {
    EffectiveConfig config = buildDefaultConfig();

    try {
      OBContext.setAdminMode(true);
      OBCriteria<AnalyticsExporterConfig> criteria = OBDal.getInstance().createCriteria(AnalyticsExporterConfig.class);
      criteria.add(Restrictions.eq(AnalyticsExporterConfig.PROPERTY_ACTIVE, true));
      criteria.add(Restrictions.eq(AnalyticsExporterConfig.PROPERTY_CLIENT + ".id", SYSTEM_CLIENT_ID));
      criteria.add(Restrictions.eq(AnalyticsExporterConfig.PROPERTY_ORGANIZATION + ".id", SYSTEM_ORG_ID));
      criteria.addOrder(Order.desc(AnalyticsExporterConfig.PROPERTY_UPDATED));
      criteria.setFilterOnReadableClients(false);
      criteria.setFilterOnReadableOrganization(false);

      List<AnalyticsExporterConfig> configs = criteria.list();
      if (configs.isEmpty()) {
        return createDefaultSystemConfig(config);
      }

      if (configs.size() > 1) {
        log.warn(
            "Multiple active analytics exporter system configurations found ({}). Using the most recently updated one.",
            configs.size());
      }

      AnalyticsExporterConfig record = configs.get(0);
      config.setConfigRecordId(record.getId());
      config.setConfigured(true);
      config.setReceiverUrl(firstNonBlank(record.getReceiverURL(), config.getReceiverUrl()));
      config.setInitialExportDays(asInteger(record.getInitialExportDays(), config.getInitialExportDays()));
      config.setChunkDays(asInteger(record.getChunkDays(), config.getChunkDays()));
      config.setMaxRetries(asInteger(record.getMaxRetries(), config.getMaxRetries()));
      config.setRetryDelayMs(asInteger(record.getRetryDelayMs(), config.getRetryDelayMs()));
      config.setConnectTimeoutMs(asInteger(record.getConnectTimeoutMs(), config.getConnectTimeoutMs()));
      config.setReadTimeoutMs(asInteger(record.getReadTimeoutMs(), config.getReadTimeoutMs()));
      config.setDetailedLoggingEnabled(asBoolean(record.isDetailedLoggingEnabled(), config.isDetailedLoggingEnabled()));
      return sanitize(config);
    } catch (Exception e) {
      log.warn("Could not load analytics exporter configuration window data. Falling back to defaults.", e);
      return config;
    } finally {
      OBContext.restorePreviousMode();
    }
  }

  private EffectiveConfig createDefaultSystemConfig(EffectiveConfig config) {
    AnalyticsExporterConfig record = OBProvider.getInstance().get(AnalyticsExporterConfig.class);
    User systemUser = OBDal.getInstance().get(User.class, SYSTEM_USER_ID);

    record.setClient(OBDal.getInstance().get(Client.class, SYSTEM_CLIENT_ID));
    record.setOrganization(OBDal.getInstance().get(Organization.class, SYSTEM_ORG_ID));
    record.setActive(true);
    record.setCreatedBy(systemUser);
    record.setUpdatedBy(systemUser);
    record.setConfigurationName("Default System Configuration");
    record.setReceiverURL(config.getReceiverUrl());
    record.setInitialExportDays(Long.valueOf(config.getInitialExportDays()));
    record.setChunkDays(Long.valueOf(config.getChunkDays()));
    record.setMaxRetries(Long.valueOf(config.getMaxRetries()));
    record.setRetryDelayMs(Long.valueOf(config.getRetryDelayMs()));
    record.setConnectTimeoutMs(Long.valueOf(config.getConnectTimeoutMs()));
    record.setReadTimeoutMs(Long.valueOf(config.getReadTimeoutMs()));
    record.setDetailedLoggingEnabled(config.isDetailedLoggingEnabled());

    OBDal.getInstance().save(record);
    OBDal.getInstance().flush();

    config.setConfigRecordId(record.getId());
    config.setConfigured(true);
    log.info("Created default analytics exporter system configuration with ID {}", record.getId());
    return config;
  }

  private EffectiveConfig buildDefaultConfig() {
    EffectiveConfig config = new EffectiveConfig();
    config.setReceiverUrl(ReceiverHttpClient.getReceiverUrlFromPreference());
    config.setInitialExportDays(DEFAULT_INITIAL_EXPORT_DAYS);
    config.setChunkDays(DEFAULT_CHUNK_DAYS);
    config.setMaxRetries(DEFAULT_MAX_RETRIES);
    config.setRetryDelayMs(DEFAULT_RETRY_DELAY_MS);
    config.setConnectTimeoutMs(DEFAULT_CONNECT_TIMEOUT_MS);
    config.setReadTimeoutMs(DEFAULT_READ_TIMEOUT_MS);
    config.setDetailedLoggingEnabled(DEFAULT_DETAILED_LOGGING_ENABLED);
    return sanitize(config);
  }

  private EffectiveConfig sanitize(EffectiveConfig config) {
    config.setInitialExportDays(atLeast(config.getInitialExportDays(), 1, DEFAULT_INITIAL_EXPORT_DAYS));
    config.setChunkDays(atLeast(config.getChunkDays(), 1, DEFAULT_CHUNK_DAYS));
    config.setMaxRetries(atLeast(config.getMaxRetries(), 1, DEFAULT_MAX_RETRIES));
    config.setRetryDelayMs(atLeast(config.getRetryDelayMs(), 0, DEFAULT_RETRY_DELAY_MS));
    config.setConnectTimeoutMs(atLeast(config.getConnectTimeoutMs(), 1000, DEFAULT_CONNECT_TIMEOUT_MS));
    config.setReadTimeoutMs(atLeast(config.getReadTimeoutMs(), 1000, DEFAULT_READ_TIMEOUT_MS));
    return config;
  }

  private int atLeast(Integer value, int minValue, int fallback) {
    if (value == null) {
      return fallback;
    }
    return value >= minValue ? value : fallback;
  }

  private String firstNonBlank(String primary, String fallback) {
    return StringUtils.isNotBlank(primary) ? primary : fallback;
  }

  private String asString(Object value) {
    return value != null ? String.valueOf(value) : null;
  }

  private Integer asInteger(Number value, Integer defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    return value.intValue();
  }

  private boolean asBoolean(Boolean value, boolean defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    return value;
  }

  /**
   * Effective exporter configuration after applying stored values and defaults.
   */
  public static class EffectiveConfig {
    private String configRecordId;
    private boolean configured;
    private String receiverUrl;
    private Integer initialExportDays;
    private Integer chunkDays;
    private Integer maxRetries;
    private Integer retryDelayMs;
    private Integer connectTimeoutMs;
    private Integer readTimeoutMs;
    private boolean detailedLoggingEnabled;

    public String getConfigRecordId() {
      return configRecordId;
    }

    public void setConfigRecordId(String configRecordId) {
      this.configRecordId = configRecordId;
    }

    public boolean isConfigured() {
      return configured;
    }

    public void setConfigured(boolean configured) {
      this.configured = configured;
    }

    public String getReceiverUrl() {
      return receiverUrl;
    }

    public void setReceiverUrl(String receiverUrl) {
      this.receiverUrl = receiverUrl;
    }

    public Integer getInitialExportDays() {
      return initialExportDays;
    }

    public void setInitialExportDays(Integer initialExportDays) {
      this.initialExportDays = initialExportDays;
    }

    public Integer getChunkDays() {
      return chunkDays;
    }

    public void setChunkDays(Integer chunkDays) {
      this.chunkDays = chunkDays;
    }

    public Integer getMaxRetries() {
      return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
      this.maxRetries = maxRetries;
    }

    public Integer getRetryDelayMs() {
      return retryDelayMs;
    }

    public void setRetryDelayMs(Integer retryDelayMs) {
      this.retryDelayMs = retryDelayMs;
    }

    public Integer getConnectTimeoutMs() {
      return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(Integer connectTimeoutMs) {
      this.connectTimeoutMs = connectTimeoutMs;
    }

    public Integer getReadTimeoutMs() {
      return readTimeoutMs;
    }

    public void setReadTimeoutMs(Integer readTimeoutMs) {
      this.readTimeoutMs = readTimeoutMs;
    }

    public boolean isDetailedLoggingEnabled() {
      return detailedLoggingEnabled;
    }

    public void setDetailedLoggingEnabled(boolean detailedLoggingEnabled) {
      this.detailedLoggingEnabled = detailedLoggingEnabled;
    }
  }
}
