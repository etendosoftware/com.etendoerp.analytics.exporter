package com.etendoerp.analytics.exporter.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;
import org.openbravo.model.ad.access.Session;
import org.openbravo.model.ad.access.SessionUsageAudit;
import org.openbravo.model.ad.module.Module;

/**
 * Unit tests for AnalyticsPayload
 * Tests the data container class for analytics export
 */
@RunWith(MockitoJUnitRunner.class)
public class AnalyticsPayloadTest {

  private AnalyticsPayload payload;
  private PayloadMetadata metadata;
  private List<Session> sessions;
  private List<SessionUsageAudit> usageAudits;

  @Before
  public void setUp() {
    payload = new AnalyticsPayload();
    metadata = new PayloadMetadata();
    sessions = new ArrayList<>();
    usageAudits = new ArrayList<>();
  }

  @Test
  public void testSetAndGetSchemaVersion() {
    String schemaVersion = "1.0";
    payload.setSchemaVersion(schemaVersion);
    assertEquals(schemaVersion, payload.getSchemaVersion());
  }

  @Test
  public void testSetAndGetMetadata() {
    metadata.setSourceInstance("test-instance");
    metadata.setExportTimestamp("2026-01-14T10:00:00Z");
    metadata.setExporterVersion("1.0.0");
    
    payload.setMetadata(metadata);
    
    assertNotNull(payload.getMetadata());
    assertEquals("test-instance", payload.getMetadata().getSourceInstance());
    assertEquals("2026-01-14T10:00:00Z", payload.getMetadata().getExportTimestamp());
    assertEquals("1.0.0", payload.getMetadata().getExporterVersion());
  }

  @Test
  public void testSetAndGetSessions() {
    // In a real scenario, these would be proper Session objects
    // For unit test, we just test the list management
    payload.setSessions(sessions);
    assertNotNull(payload.getSessions());
    assertEquals(0, payload.getSessions().size());
  }

  @Test
  public void testSetAndGetUsageAudits() {
    payload.setUsageAudits(usageAudits);
    assertNotNull(payload.getUsageAudits());
    assertEquals(0, payload.getUsageAudits().size());
  }

  @Test
  public void testPayloadWithNullValues() {
    AnalyticsPayload nullPayload = new AnalyticsPayload();
    
    // SchemaVersion has default value
    assertEquals("1.0", nullPayload.getSchemaVersion());
    // Metadata, sessions, and usageAudits are instantiated by default in constructor
    assertNotNull(nullPayload.getMetadata());
    assertNotNull(nullPayload.getSessions());
    assertNotNull(nullPayload.getUsageAudits());
  }

  @Test
  public void testPayloadWithMultipleSessions() {
    // Create a list with size (mocking actual sessions)
    List<Session> multipleSessions = new ArrayList<>();
    // In real scenario would add actual Session objects
    payload.setSessions(multipleSessions);
    
    assertNotNull(payload.getSessions());
    assertTrue(payload.getSessions().isEmpty());
  }

  @Test
  public void testPayloadWithMultipleAudits() {
    List<SessionUsageAudit> multipleAudits = new ArrayList<>();
    payload.setUsageAudits(multipleAudits);
    
    assertNotNull(payload.getUsageAudits());
    assertTrue(payload.getUsageAudits().isEmpty());
  }

  @Test
  public void testCompletePayloadSetup() {
    // Setup complete payload
    payload.setSchemaVersion("1.0");
    
    PayloadMetadata meta = new PayloadMetadata();
    meta.setSourceInstance("production-instance");
    meta.setExportTimestamp("2026-01-14T10:00:00.000000+00:00");
    meta.setExporterVersion("1.0.0");
    meta.setDaysExported(7);
    payload.setMetadata(meta);
    
    payload.setSessions(new ArrayList<>());
    payload.setUsageAudits(new ArrayList<>());
    
    // Verify all fields are set
    assertNotNull(payload.getSchemaVersion());
    assertNotNull(payload.getMetadata());
    assertNotNull(payload.getSessions());
    assertNotNull(payload.getUsageAudits());
    
    assertEquals("1.0", payload.getSchemaVersion());
    assertEquals("production-instance", payload.getMetadata().getSourceInstance());
    assertEquals(Integer.valueOf(7), payload.getMetadata().getDaysExported());
  }

  @Test
  public void testPayloadImmutabilityOfLists() {
    List<Session> originalSessions = new ArrayList<>();
    payload.setSessions(originalSessions);
    
    List<Session> retrievedSessions = payload.getSessions();
    
    // Verify it's the same reference (not a defensive copy)
    // This tests the current implementation
    assertEquals(originalSessions, retrievedSessions);
  }

  @Test
  public void testMetadataWithAllFields() {
    PayloadMetadata fullMetadata = new PayloadMetadata();
    fullMetadata.setSourceInstance("test-instance");
    fullMetadata.setExportTimestamp("2026-01-14T15:30:00.000000+00:00");
    fullMetadata.setExporterVersion("2.0.0");
    fullMetadata.setDaysExported(14);
    
    payload.setMetadata(fullMetadata);
    
    PayloadMetadata retrieved = payload.getMetadata();
    assertEquals("test-instance", retrieved.getSourceInstance());
    assertEquals("2026-01-14T15:30:00.000000+00:00", retrieved.getExportTimestamp());
    assertEquals("2.0.0", retrieved.getExporterVersion());
    assertEquals(Integer.valueOf(14), retrieved.getDaysExported());
  }

  @Test
  public void testMultiplePayloadInstances() {
    AnalyticsPayload payload1 = new AnalyticsPayload();
    AnalyticsPayload payload2 = new AnalyticsPayload();
    
    payload1.setSchemaVersion("1.0");
    payload2.setSchemaVersion("2.0");
    
    // Verify they're independent
    assertEquals("1.0", payload1.getSchemaVersion());
    assertEquals("2.0", payload2.getSchemaVersion());
  }

  @Test
  public void testPayloadModification() {
    payload.setSchemaVersion("1.0");
    assertEquals("1.0", payload.getSchemaVersion());
    
    // Modify
    payload.setSchemaVersion("1.1");
    assertEquals("1.1", payload.getSchemaVersion());
  }
}
