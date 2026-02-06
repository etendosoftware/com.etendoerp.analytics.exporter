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

import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openbravo.base.secureApp.HttpSecureAppServlet;
import org.openbravo.base.secureApp.VariablesSecureApp;

import com.etendoerp.analytics.exporter.service.AnalyticsSyncService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Health check endpoint for analytics sync status
 * Returns the last sync state including job ID and status
 * <p>
 * Endpoint: /etendo/com.etendoerp.analytics.exporter.HealthCheck
 */
public class AnalyticsHealthCheck extends HttpSecureAppServlet {

  private static final Logger log = LogManager.getLogger();
  static final long serialVersionUID = 1L;
  public static final String STATUS = "status";
  public static final String HEALTH = "health";
  public static final String ERROR = "error";

  @Override
  public void doGet(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {

    VariablesSecureApp vars = new VariablesSecureApp(request);

    try {
      log.debug("Health check requested");

      // Check permissions
      if (!StringUtils.equals("Y", vars.getSessionValue("#LOGGININ"))) {
        sendUnauthorized(response);
        return;
      }

      // Get health status
      AnalyticsSyncService syncService = new AnalyticsSyncService();
      AnalyticsSyncService.SyncState lastSync = syncService.getHealthStatus();

      // Build response
      Map<String, Object> healthData = new HashMap<>();

      if (lastSync != null) {
        healthData.put(STATUS, "healthy");
        healthData.put("last_sync_timestamp", lastSync.getLastSyncTimestamp() != null
            ? lastSync.getLastSyncTimestamp().toInstant().toString()
            : null);
        healthData.put("last_job_id", lastSync.getLastJobId());
        healthData.put("last_status", lastSync.getLastStatus());
        healthData.put("log", lastSync.getLog());

        // Determine overall health
        if (StringUtils.equals("SUCCESS", lastSync.getLastStatus())) {
          healthData.put(HEALTH, "ok");
        } else {
          healthData.put(HEALTH, "degraded");
        }
      } else {
        healthData.put(STATUS, "no_data");
        healthData.put(HEALTH, "unknown");
        healthData.put("message", "No synchronization has been performed yet");
      }

      // Send JSON response
      sendJsonResponse(response, HttpServletResponse.SC_OK, healthData);

    } catch (Exception e) {
      log.error("Error checking health status", e);

      Map<String, Object> errorData = new HashMap<>();
      errorData.put(STATUS, ERROR);
      errorData.put(HEALTH, "unhealthy");
      errorData.put(ERROR, e.getMessage());

      sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, errorData);
    }
  }

  @Override
  public void doPost(HttpServletRequest request, HttpServletResponse response)
      throws ServletException, IOException {
    doGet(request, response);
  }

  /**
   * Send unauthorized response
   */
  private void sendUnauthorized(HttpServletResponse response) throws IOException {
    Map<String, Object> errorData = new HashMap<>();
    errorData.put(STATUS, ERROR);
    errorData.put(ERROR, "Unauthorized");

    sendJsonResponse(response, HttpServletResponse.SC_UNAUTHORIZED, errorData);
  }

  /**
   * Send JSON response
   */
  private void sendJsonResponse(HttpServletResponse response, int statusCode, Map<String, Object> data)
      throws IOException {
    response.setStatus(statusCode);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");

    ObjectMapper mapper = new ObjectMapper();
    String json = mapper.writeValueAsString(data);

    PrintWriter out = response.getWriter();
    out.print(json);
    out.flush();
  }
}
