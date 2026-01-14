# Analytics Exporter Module

Etendo module for automated extraction and submission of usage metrics and health data to the centralized observability
platform.

## Description

This module implements a complete data export system for sessions and usage audits from Etendo to the centralized
receiver at `https://receiver.otel2.etendo.cloud/process`.

The module supports two synchronized data types:

- **SESSION_USAGE_AUDITS**: Sessions and usage audit data
- **MODULE_METADATA**: Installed module metadata

## Features

### 1. Dual Data Types Support

- **SESSION_USAGE_AUDITS**: Sessions and usage audit records with complete window/process enrichment
- **MODULE_METADATA**: Installed module information with versioning and commercial status

### 2. Advanced Data Enrichment

- **Dynamic Window/Process Resolution**:
    - For windows: `object_id` → Tab → Window → Module
    - For processes: `object_id` → Process → Module directly
- Complete metadata extraction (module name, version, java package)
- Core version tracking from module `0`
- **Critical Filter**: Automatically excludes POS sessions (`NOT login_status IN ('OBPOS_POS')`)

### 3. Incremental Synchronization

- Persists last successful sync timestamp in `ETAE_ANALYTICS_SYNC` table
- Separate tracking per sync type (SESSION_USAGE_AUDITS, MODULE_METADATA)
- Prevents data duplication
- **Fallback**: If no previous sync exists, exports last 7 days (SESSION_USAGE_AUDITS only)

### 4. Robust HTTP Client

- HTTPS submission to `https://receiver.otel2.etendo.cloud/process`
- **Retry Policy**: Up to 3 retries on 5xx errors with 2-second delays
- Configured timeouts (30s connect, 60s read)
- Automatic handling of unexpected JSON fields with `@JsonIgnoreProperties`
- Logs `job_id` returned by receiver

### 5. Test Mode (DEBUG_MODE)

- Environment variable: `ANALYTICS_EXPORTER_DEBUG_MODE=true`
- Skips HTTP submission to receiver
- Displays full JSON payload in logs
- Allows validation before production deployment

### 6. Flexible Receiver URL Configuration

- Default: `https://receiver.otel2.etendo.cloud/process`
- Configurable via preference: `ETAE_RECEIVER_URL` (Property: Attribute)
- Constructor override support for testing

### 7. Schedulable Process

- Class `AnalyticsSyncProcess` extending `DalBaseProcess`
- Can be configured in Process Scheduling for daily execution
- Detailed reporting (sessions, audits, modules, job_id, duration)
- Supports both sync types as parameters

### 8. Health Check Endpoint

- Endpoint: `/etendo/com.etendoerp.analytics.exporter.HealthCheck`
- Returns last synchronization state (SESSION_USAGE_AUDITS)
- Includes: timestamp, job_id, status, detailed log

## Project Structure

```
src/com/etendoerp/analytics/exporter/
├── data/
│   ├── AnalyticsPayload.java         # Main payload container
│   ├── PayloadMetadata.java          # Payload metadata
│   └── AnalyticsSync.java            # DAL entity for ETAE_ANALYTICS_SYNC table
├── service/
│   ├── AnalyticsSyncService.java     # Main synchronization service
│   ├── DataExtractionService.java    # Data extraction using DAL
│   └── ReceiverHttpClient.java       # HTTP client with retry logic
└── process/
    ├── AnalyticsSyncProcess.java     # Schedulable background process
    └── AnalyticsHealthCheck.java     # Health check HTTP endpoint

src-db/database/
├── model/tables/
│   └── ETAE_ANALYTICS_SYNC.xml       # Sync state tracking table
└── sourcedata/
    └── ...                            # Module metadata
```

## Payload Format

### SESSION_USAGE_AUDITS Sync Type

#### JSON Structure

```json
{
  "schema_version": "1.0",
  "metadata": {
    "source_instance": "instance_name",
    "export_timestamp": "2025-01-13T10:30:00.000000+00:00",
    "exporter_version": "1.0.0",
    "days_exported": 7
  },
  "sessions": [
    {
      "session_id": "FF8080814D2A3B97014D2A3C89850001",
      "username": "admin",
      "user_id": "100",
      "login_time": "2025-01-13T10:00:00.000000+00:00",
      "logout_time": "2025-01-13T14:30:00.000000+00:00",
      "session_active": false,
      "login_status": "SUCCESS",
      "server_url": "https://erp.example.com",
      "created": "2025-01-13T10:00:00.000000+00:00",
      "created_by": "100",
      "updated": "2025-01-13T14:30:00.000000+00:00",
      "updated_by": "100",
      "ip": "192.168.1.100"
    }
  ],
  "usage_audits": [
    {
      "usage_audit_id": "FF8080814D2A3B97014D2A3C89850002",
      "session_id": "FF8080814D2A3B97014D2A3C89850001",
      "username": "admin",
      "command": "SAVE",
      "execution_time": "2025-01-13T10:15:00.000000+00:00",
      "process_time_ms": 245.50,
      "module_id": "4BA8CBFD94364892805C648555FF1262",
      "module_name": "Sales Management",
      "module_javapackage": "org.openbravo.module.sales",
      "module_version": "1.5.0",
      "core_version": "24.1.0",
      "object_id": "143",
      "object_type": "W",
      "window_id": "143",
      "window_name": "Sales Order",
      "process_id": null,
      "process_name": null,
      "record_count": 0,
      "created": "2025-01-13T10:15:00.000000+00:00",
      "created_by": "100",
      "ip": "192.168.1.100"
    }
  ]
}
```

### MODULE_METADATA Sync Type

#### JSON Structure

```json
{
  "schema_version": "module_metadata_v1",
  "metadata": {
    "source_instance": "instance_name",
    "check_type": "module_metadata_v1",
    "storage_only": true,
    "exported_at": "2025-01-13T10:30:00.000000+00:00"
  },
  "records": [
    {
      "ad_module_id": "4BA8CBFD94364892805C648555FF1262",
      "javapackage": "org.openbravo.module.sales",
      "name": "Sales Management",
      "version": "1.5.0",
      "type": "M",
      "iscommercial": false,
      "enabled": true
    }
  ]
}
```

## Receiver Response

### Success (202 Accepted)

```json
{
  "status": "received",
  "job_id": "b9df465e-5abd-4e58-9fc2-6b075b959ba8",
  "message": "Data received and queued for processing",
  "queue_position": 1,
  "data_summary": {
    "records": 40,
    "sessions": 0,
    "source_instance": "system",
    "usage_audits": 0
  },
  "received_at": "2026-01-14T15:06:08.245763"
}
```

### Error (400/500)

```json
{
  "status": "error",
  "error": "Error description"
}
```

> **Note**: The module uses `@JsonIgnoreProperties(ignoreUnknown = true)` to handle additional fields like
`data_summary` and `received_at` that may be returned by the receiver.

## Installation and Configuration

### 1. Install Module

```bash
./gradlew smartbuild
```

### 2. Configure Receiver URL (Optional)

To use a custom receiver URL:

1. Go to: **General Setup** > **Application** > **Preference**
2. Create new preference:
    - **Property**: Attribute
    - **Attribute**: `ETAE_RECEIVER_URL`
    - **Search Key**: Custom value (e.g., `CUSTOM_RECEIVER`)
    - **Value**: Your custom receiver URL (e.g., `https://custom-receiver.example.com/process`)
    - **Visibility**: System level (Client=*, Organization=*)

> **Note**: If no preference is configured, the module uses the default URL:
`https://receiver.otel2.etendo.cloud/process`

### 3. Enable Test Mode (Optional)

For testing without sending data to the receiver:

```bash
export ANALYTICS_EXPORTER_DEBUG_MODE=true
```

Or in Tomcat's `setenv.sh`:

```bash
CATALINA_OPTS="$CATALINA_OPTS -DANALYTICS_EXPORTER_DEBUG_MODE=true"
```

### 4. Configure Scheduled Process (Optional)

1. Go to: **General Setup** > **Process Scheduling** > **Process Request**
2. Create new process:
    - **Process**: Analytics Sync Process
    - **Parameter**: Select sync type (`SESSION_USAGE_AUDITS` or `MODULE_METADATA`)
    - **Frequency**: Daily
    - **Timing**: 02:00 AM (or as preferred)

### 5. Manual Execution

From **General Setup** > **Process Scheduling** > **Process Request**, execute the created process or run directly from
the process definition window.

## Health Check

### Endpoint

```
GET /etendo/com.etendoerp.analytics.exporter.HealthCheck
```

### Sample Response

```json
{
  "status": "healthy",
  "health": "ok",
  "last_sync_timestamp": "2025-01-13T10:30:00Z",
  "last_job_id": "b9df465e-5abd-4e58-9fc2-6b075b959ba8",
  "last_status": "SUCCESS",
  "log": "Job ID: b9df465e-5abd-4e58-9fc2-6b075b959ba8\nSessions: 150\nAudits: 3420\nMessage: [SESSION_USAGE_AUDITS] Successfully sent 3570 records. Job ID: b9df465e-5abd-4e58-9fc2-6b075b959ba8"
}
```

## Data Enrichment Logic

### Window/Process Resolution

The module implements sophisticated logic to resolve window and process information from usage audits:

#### For Windows (`command != 'DEFAULT'`, object_type = 'W')

```
object_id (Tab ID) → AD_Tab → AD_Window → Module
```

1. Fetch Tab using `object_id`
2. Get Window from Tab relationship
3. Extract module information from Window
4. Populate: `window_id`, `window_name`, `module_id`, `module_name`, `module_javapackage`, `module_version`

#### For Processes (`command = 'DEFAULT'`, object_type = 'P')

```
object_id (Process ID) → AD_Process → Module
```

1. Fetch Process directly using `object_id`
2. Extract module information from Process
3. Populate: `process_id`, `process_name`, `module_id`, `module_name`, `module_javapackage`, `module_version`

#### SQL Query Reference

The enrichment logic is based on this SQL structure:

```sql
SELECT 
    ua.ad_session_usage_audit_id,
    ua.object_id,
    ua.command,
    CASE WHEN ua.command = 'DEFAULT' THEN 'P' ELSE 'W' END as object_type,
    -- For windows: join via tab
    w.ad_window_id as window_id,
    w.name as window_name,
    m_window.ad_module_id,
    m_window.name as module_name,
    -- For processes: direct join
    p.ad_process_id as process_id,
    p.name as process_name,
    m_process.ad_module_id,
    m_process.name as module_name
FROM ad_session_usage_audit ua
LEFT JOIN ad_tab t ON t.ad_tab_id = ua.object_id AND ua.command != 'DEFAULT'
LEFT JOIN ad_window w ON w.ad_window_id = t.ad_window_id
LEFT JOIN ad_module m_window ON m_window.ad_module_id = w.ad_module_id
LEFT JOIN ad_process p ON p.ad_process_id = ua.object_id AND ua.command = 'DEFAULT'
LEFT JOIN ad_module m_process ON m_process.ad_module_id = p.ad_module_id
```

## Acceptance Criteria

- [x] **Correct Extraction**: DAL-based queries with proper entity relationships
- [x] **Window/Process Enrichment**: Automatic resolution via Tab→Window and Process lookups
- [x] **Module Metadata**: Extracts module information from Window/Process, not audit record
- [x] **Incremental Sync**: No duplicate records, uses `ETAE_ANALYTICS_SYNC` table per sync type
- [x] **Security**: HTTPS submission to `receiver.otel2.etendo.cloud`
- [x] **Retry Policy**: Up to 3 retries on 5xx errors with 2-second delays
- [x] **Job ID Logging**: Captures and persists receiver's job_id
- [x] **Health Check**: Endpoint returning state and last job_id
- [x] **Scheduled Process**: Compatible with Etendo's Process Scheduling
- [x] **Test Mode**: DEBUG_MODE support for payload validation
- [x] **Flexible Configuration**: Supports custom receiver URLs via preferences
- [x] **Dual Sync Types**: SESSION_USAGE_AUDITS and MODULE_METADATA support

## Architecture

### Synchronization Flow

```
┌─────────────────────┐
│ Scheduled Process   │
│ (Daily/Manual)      │
│ - Sync Type Param   │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ AnalyticsSyncService│
│ - Coordinates all   │
│ - Two sync types    │
└──────────┬──────────┘
           │
           ├──────────────────┐
           ▼                  ▼
┌──────────────────┐  ┌────────────────┐
│DataExtraction    │  │ SyncState      │
│Service           │  │ Persistence    │
│- DAL Queries     │  │- ETAE_ANALYTICS│
│- Session + Audit │  │  _SYNC table   │
│- Module Metadata │  │- Per sync type │
│- Enrichment:     │  └────────────────┘
│  * Tab→Window    │
│  * Process       │
│  * Module        │
└────────┬─────────┘
         │
         ▼
┌────────────────────┐
│ JSON Builder       │
│ - Manual construct │
│ - Jackson mapper   │
│ - ISO timestamps   │
└────────┬───────────┘
         │
         ▼ (if not DEBUG_MODE)
┌────────────────────┐
│ ReceiverHttpClient │
│ - HTTPS POST       │
│ - Retry (3x/2s)    │
│ - @JsonIgnore...   │
│ - Capture job_id   │
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ Receiver OTEL      │
│ receiver.otel2     │
│ .etendo.cloud      │
└────────────────────┘
```

### Data Flow for Usage Audits

```
SessionUsageAudit (DAL Entity)
    ↓
Check command type
    ↓
┌─────────────┬─────────────┐
↓             ↓             ↓
Window Path   Process Path  Core Version
    ↓             ↓             ↓
object_id     object_id     Module "0"
    ↓             ↓             ↓
get(Tab)      get(Process)  get(Module)
    ↓             ↓             ↓
tab.getWindow() process.*    core.getVersion()
    ↓             ↓
window.*      module.*
    ↓
module.*
    ↓
JSON Node with full enrichment
```

## Troubleshooting

### Error: "Failed to send data after 3 attempts"

- Verify connectivity with `receiver.otel2.etendo.cloud`
- Check receiver logs
- Validate JSON payload is well-formed
- Disable DEBUG_MODE if enabled

### Error: "Client error: 400"

- Verify payload format matches expected schema
- Check required fields in metadata
- Validate timestamps are in ISO-8601 format
- Review receiver logs for specific validation errors

### No data being exported

- Verify sessions/audits exist in the date range
- Check filter `NOT login_status IN ('OBPOS_POS')`
- Verify database permissions
- Check last sync timestamp in `ETAE_ANALYTICS_SYNC` table

### JSON shows nulls for window_id/process_id

- Verify `object_id` exists in `AD_Tab` (for windows) or `AD_Process` (for processes)
- Check that Tab has a valid Window relationship
- Review logs for "Could not fetch window/process info" warnings
- Confirm module relationships are properly set

### Test Mode (DEBUG_MODE)

To validate payloads without sending:

```bash
export ANALYTICS_EXPORTER_DEBUG_MODE=true
# Restart Tomcat
# Run sync process
# Check logs for full JSON payload
```

### Custom Receiver URL not working

- Verify preference exists: `ETAE_RECEIVER_URL`
- Check property type is "Attribute"
- Confirm visibility level (Client=*, Organization=*)
- Validate URL format (must include protocol: `https://...`)
- Check logs for "Using custom receiver URL: ..." message

## Dependencies

- **Jackson**: JSON serialization (`com.fasterxml.jackson.*`)
- **Apache Commons Lang**: String utilities
- **Etendo Core**: DAL, ConnectionProvider, Process scheduling
- **Java 8+**: Required for date/time handling

## Technical Notes

### Entity Relationships

- `SessionUsageAudit` → `Session` (many-to-one)
- `SessionUsageAudit` → `Module` (many-to-one, via window/process)
- `Tab` → `Window` (many-to-one)
- `Window` → `Module` (many-to-one)
- `Process` → `Module` (many-to-one)

### Timestamp Format

All timestamps are exported in ISO-8601 format with microseconds:

```
yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX
Example: 2025-01-13T10:30:00.000000+00:00
```

### Database Table: ETAE_ANALYTICS_SYNC

Tracks synchronization state per sync type:

- `ETAE_Analytics_Sync_ID`: Primary key (UUID)
- `Sync_Type`: Type of sync (SESSION_USAGE_AUDITS, MODULE_METADATA)
- `Last_Sync`: Timestamp of last successful sync
- `Last_Status`: Status (SUCCESS/FAILED)
- `Log`: Detailed execution log including job_id

## License

This module is part of the Etendo ERP project.

## Support

For issues or questions:

1. Review logs in Etendo: `catalina.out` or `etendo.log`
2. Check health check endpoint: `/etendo/com.etendoerp.analytics.exporter.HealthCheck`
3. Verify receiver status at `receiver.otel2.etendo.cloud`
4. Enable DEBUG_MODE for detailed payload inspection

## Version History

### 1.0.0

- Initial release
- SESSION_USAGE_AUDITS sync type support
- MODULE_METADATA sync type support
- Incremental synchronization
- Window/Process enrichment via DAL
- Retry logic with 3 attempts
- Health check endpoint
- Custom receiver URL configuration
- DEBUG_MODE for testing
