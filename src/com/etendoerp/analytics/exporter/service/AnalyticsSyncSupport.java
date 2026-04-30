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

/**
 * Shared formatting and timing helpers used by analytics synchronization services.
 */
final class AnalyticsSyncSupport {

  private AnalyticsSyncSupport() {
  }

  static long elapsedMillis(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000L;
  }

  static String formatTimestamp(java.util.Date date) {
    if (date == null) {
      return null;
    }
    java.text.SimpleDateFormat simpleDateFormat =
        new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX");
    simpleDateFormat.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
    return simpleDateFormat.format(date);
  }

  static String mapLoginStatus(String status, String successValue, String failedValue) {
    if (status == null) {
      return "UNKNOWN";
    }
    switch (status) {
      case "S":
        return successValue;
      case "F":
        return failedValue;
      case "L":
        return "LOCKED";
      default:
        return status;
    }
  }
}
