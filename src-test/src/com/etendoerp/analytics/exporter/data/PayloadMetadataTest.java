package com.etendoerp.analytics.exporter.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * Unit tests for PayloadMetadata
 * Tests the metadata container class for analytics export
 */
@RunWith(MockitoJUnitRunner.class)
public class PayloadMetadataTest {

  private PayloadMetadata metadata;

  @Before
  public void setUp() {
    metadata = new PayloadMetadata();
  }

  @Test
  public void testSetAndGetSourceInstance() {
    String sourceInstance = "production-erp";
    metadata.setSourceInstance(sourceInstance);
    assertEquals(sourceInstance, metadata.getSourceInstance());
  }

  @Test
  public void testSetAndGetExportTimestamp() {
    String timestamp = "2026-01-14T10:30:00.000000+00:00";
    metadata.setExportTimestamp(timestamp);
    assertEquals(timestamp, metadata.getExportTimestamp());
  }

  @Test
  public void testSetAndGetExporterVersion() {
    String version = "1.0.0";
    metadata.setExporterVersion(version);
    assertEquals(version, metadata.getExporterVersion());
  }

  @Test
  public void testSetAndGetDaysExported() {
    Integer days = 7;
    metadata.setDaysExported(days);
    assertEquals(days, metadata.getDaysExported());
  }

  @Test
  public void testMetadataWithNullValues() {
    PayloadMetadata nullMetadata = new PayloadMetadata();
    
    assertNull(nullMetadata.getSourceInstance());
    assertNull(nullMetadata.getExportTimestamp());
    // ExporterVersion has default value in constructor
    assertEquals("1.0.0", nullMetadata.getExporterVersion());
    assertNull(nullMetadata.getDaysExported());
  }

  @Test
  public void testCompleteMetadataSetup() {
    metadata.setSourceInstance("test-instance");
    metadata.setExportTimestamp("2026-01-14T15:00:00.000000+00:00");
    metadata.setExporterVersion("2.0.0");
    metadata.setDaysExported(14);
    
    assertNotNull(metadata.getSourceInstance());
    assertNotNull(metadata.getExportTimestamp());
    assertNotNull(metadata.getExporterVersion());
    assertNotNull(metadata.getDaysExported());
    
    assertEquals("test-instance", metadata.getSourceInstance());
    assertEquals("2026-01-14T15:00:00.000000+00:00", metadata.getExportTimestamp());
    assertEquals("2.0.0", metadata.getExporterVersion());
    assertEquals(Integer.valueOf(14), metadata.getDaysExported());
  }

  @Test
  public void testMetadataWithZeroDays() {
    metadata.setDaysExported(0);
    assertEquals(Integer.valueOf(0), metadata.getDaysExported());
  }

  @Test
  public void testMetadataWithNegativeDays() {
    metadata.setDaysExported(-1);
    assertEquals(Integer.valueOf(-1), metadata.getDaysExported());
  }

  @Test
  public void testMetadataWithLargeDaysValue() {
    Integer largeDays = 365;
    metadata.setDaysExported(largeDays);
    assertEquals(largeDays, metadata.getDaysExported());
  }

  @Test
  public void testSourceInstanceWithSpecialCharacters() {
    String instanceWithSpecialChars = "production_erp-2024";
    metadata.setSourceInstance(instanceWithSpecialChars);
    assertEquals(instanceWithSpecialChars, metadata.getSourceInstance());
  }

  @Test
  public void testSourceInstanceWithSpaces() {
    String instanceWithSpaces = "production erp";
    metadata.setSourceInstance(instanceWithSpaces);
    assertEquals(instanceWithSpaces, metadata.getSourceInstance());
  }

  @Test
  public void testExportTimestampISO8601Format() {
    String iso8601Timestamp = "2026-01-14T10:30:00.123456+00:00";
    metadata.setExportTimestamp(iso8601Timestamp);
    assertEquals(iso8601Timestamp, metadata.getExportTimestamp());
  }

  @Test
  public void testExportTimestampWithDifferentTimezone() {
    String timestampWithTZ = "2026-01-14T10:30:00.000000+05:30";
    metadata.setExportTimestamp(timestampWithTZ);
    assertEquals(timestampWithTZ, metadata.getExportTimestamp());
  }

  @Test
  public void testExporterVersionSemantic() {
    String semanticVersion = "1.2.3";
    metadata.setExporterVersion(semanticVersion);
    assertEquals(semanticVersion, metadata.getExporterVersion());
  }

  @Test
  public void testExporterVersionWithPreRelease() {
    String preReleaseVersion = "1.0.0-beta.1";
    metadata.setExporterVersion(preReleaseVersion);
    assertEquals(preReleaseVersion, metadata.getExporterVersion());
  }

  @Test
  public void testExporterVersionWithBuildMetadata() {
    String versionWithBuild = "1.0.0+20210101";
    metadata.setExporterVersion(versionWithBuild);
    assertEquals(versionWithBuild, metadata.getExporterVersion());
  }

  @Test
  public void testMetadataModification() {
    metadata.setSourceInstance("original");
    assertEquals("original", metadata.getSourceInstance());
    
    metadata.setSourceInstance("modified");
    assertEquals("modified", metadata.getSourceInstance());
  }

  @Test
  public void testMultipleMetadataInstances() {
    PayloadMetadata metadata1 = new PayloadMetadata();
    PayloadMetadata metadata2 = new PayloadMetadata();
    
    metadata1.setSourceInstance("instance1");
    metadata2.setSourceInstance("instance2");
    
    assertEquals("instance1", metadata1.getSourceInstance());
    assertEquals("instance2", metadata2.getSourceInstance());
  }

  @Test
  public void testMetadataWithEmptyStrings() {
    metadata.setSourceInstance("");
    metadata.setExportTimestamp("");
    metadata.setExporterVersion("");
    
    assertEquals("", metadata.getSourceInstance());
    assertEquals("", metadata.getExportTimestamp());
    assertEquals("", metadata.getExporterVersion());
  }

  @Test
  public void testMetadataOverwrite() {
    metadata.setSourceInstance("first");
    metadata.setSourceInstance("second");
    metadata.setSourceInstance("third");
    
    assertEquals("third", metadata.getSourceInstance());
  }

  @Test
  public void testDaysExportedNullToValue() {
    assertNull(metadata.getDaysExported());
    metadata.setDaysExported(30);
    assertEquals(Integer.valueOf(30), metadata.getDaysExported());
  }

  @Test
  public void testDaysExportedValueToNull() {
    metadata.setDaysExported(30);
    assertEquals(Integer.valueOf(30), metadata.getDaysExported());
    
    metadata.setDaysExported(null);
    assertNull(metadata.getDaysExported());
  }
}
