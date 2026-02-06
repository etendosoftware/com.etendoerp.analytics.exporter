package com.etendoerp.analytics.exporter.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for PayloadMetadata
 * Tests the metadata container class for analytics export
 */
@ExtendWith(MockitoExtension.class)
public class PayloadMetadataTest {

  private PayloadMetadata metadata;

  /**
   * Sets up test fixtures before each test execution.
   */
  @BeforeEach
  public void setUp() {
    metadata = new PayloadMetadata();
  }

  /**
   * Tests setting and getting the source instance.
   */
  @Test
  public void testSetAndGetSourceInstance() {
    String sourceInstance = "production-erp";
    metadata.setSourceInstance(sourceInstance);
    assertEquals(sourceInstance, metadata.getSourceInstance());
  }

  /**
   * Tests setting and getting the export timestamp.
   */
  @Test
  public void testSetAndGetExportTimestamp() {
    String timestamp = "2026-01-14T10:30:00.000000+00:00";
    metadata.setExportTimestamp(timestamp);
    assertEquals(timestamp, metadata.getExportTimestamp());
  }

  /**
   * Tests setting and getting the exporter version.
   */
  @Test
  public void testSetAndGetExporterVersion() {
    String version = "1.0.0";
    metadata.setExporterVersion(version);
    assertEquals(version, metadata.getExporterVersion());
  }

  /**
   * Tests setting and getting the days exported value.
   */
  @Test
  public void testSetAndGetDaysExported() {
    Integer days = 7;
    metadata.setDaysExported(days);
    assertEquals(days, metadata.getDaysExported());
  }

  /**
   * Tests that metadata handles null values correctly.
   */
  @Test
  public void testMetadataWithNullValues() {
    PayloadMetadata nullMetadata = new PayloadMetadata();
    
    assertNull(nullMetadata.getSourceInstance());
    assertNull(nullMetadata.getExportTimestamp());
    // ExporterVersion has default value in constructor
    assertEquals("1.0.0", nullMetadata.getExporterVersion());
    assertNull(nullMetadata.getDaysExported());
  }

  /**
   * Tests setting up complete metadata with all fields populated.
   */
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

  /**
   * Tests metadata with zero days exported.
   */
  @Test
  public void testMetadataWithZeroDays() {
    metadata.setDaysExported(0);
    assertEquals(Integer.valueOf(0), metadata.getDaysExported());
  }

  /**
   * Tests metadata with negative days exported value.
   */
  @Test
  public void testMetadataWithNegativeDays() {
    metadata.setDaysExported(-1);
    assertEquals(Integer.valueOf(-1), metadata.getDaysExported());
  }

  /**
   * Tests metadata with large days exported value.
   */
  @Test
  public void testMetadataWithLargeDaysValue() {
    Integer largeDays = 365;
    metadata.setDaysExported(largeDays);
    assertEquals(largeDays, metadata.getDaysExported());
  }

  /**
   * Tests source instance with special characters.
   */
  @Test
  public void testSourceInstanceWithSpecialCharacters() {
    String instanceWithSpecialChars = "production_erp-2024";
    metadata.setSourceInstance(instanceWithSpecialChars);
    assertEquals(instanceWithSpecialChars, metadata.getSourceInstance());
  }

  /**
   * Tests source instance containing spaces.
   */
  @Test
  public void testSourceInstanceWithSpaces() {
    String instanceWithSpaces = "production erp";
    metadata.setSourceInstance(instanceWithSpaces);
    assertEquals(instanceWithSpaces, metadata.getSourceInstance());
  }

  /**
   * Tests export timestamp in ISO-8601 format.
   */
  @Test
  public void testExportTimestampISO8601Format() {
    String iso8601Timestamp = "2026-01-14T10:30:00.123456+00:00";
    metadata.setExportTimestamp(iso8601Timestamp);
    assertEquals(iso8601Timestamp, metadata.getExportTimestamp());
  }

  /**
   * Tests export timestamp with different timezone.
   */
  @Test
  public void testExportTimestampWithDifferentTimezone() {
    String timestampWithTZ = "2026-01-14T10:30:00.000000+05:30";
    metadata.setExportTimestamp(timestampWithTZ);
    assertEquals(timestampWithTZ, metadata.getExportTimestamp());
  }

  /**
   * Tests exporter version with semantic versioning format.
   */
  @Test
  public void testExporterVersionSemantic() {
    String semanticVersion = "1.2.3";
    metadata.setExporterVersion(semanticVersion);
    assertEquals(semanticVersion, metadata.getExporterVersion());
  }

  /**
   * Tests exporter version with pre-release identifier.
   */
  @Test
  public void testExporterVersionWithPreRelease() {
    String preReleaseVersion = "1.0.0-beta.1";
    metadata.setExporterVersion(preReleaseVersion);
    assertEquals(preReleaseVersion, metadata.getExporterVersion());
  }

  /**
   * Tests exporter version with build metadata.
   */
  @Test
  public void testExporterVersionWithBuildMetadata() {
    String versionWithBuild = "1.0.0+20210101";
    metadata.setExporterVersion(versionWithBuild);
    assertEquals(versionWithBuild, metadata.getExporterVersion());
  }

  /**
   * Tests that metadata values can be modified after creation.
   */
  @Test
  public void testMetadataModification() {
    metadata.setSourceInstance("original");
    assertEquals("original", metadata.getSourceInstance());
    
    metadata.setSourceInstance("modified");
    assertEquals("modified", metadata.getSourceInstance());
  }

  /**
   * Tests that multiple metadata instances are independent.
   */
  @Test
  public void testMultipleMetadataInstances() {
    PayloadMetadata metadata1 = new PayloadMetadata();
    PayloadMetadata metadata2 = new PayloadMetadata();
    
    metadata1.setSourceInstance("instance1");
    metadata2.setSourceInstance("instance2");
    
    assertEquals("instance1", metadata1.getSourceInstance());
    assertEquals("instance2", metadata2.getSourceInstance());
  }

  /**
   * Tests metadata with empty string values.
   */
  @Test
  public void testMetadataWithEmptyStrings() {
    metadata.setSourceInstance("");
    metadata.setExportTimestamp("");
    metadata.setExporterVersion("");
    
    assertEquals("", metadata.getSourceInstance());
    assertEquals("", metadata.getExportTimestamp());
    assertEquals("", metadata.getExporterVersion());
  }

  /**
   * Tests that metadata values can be overwritten multiple times.
   */
  @Test
  public void testMetadataOverwrite() {
    metadata.setSourceInstance("first");
    metadata.setSourceInstance("second");
    metadata.setSourceInstance("third");
    
    assertEquals("third", metadata.getSourceInstance());
  }

  /**
   * Tests changing days exported from null to a value.
   */
  @Test
  public void testDaysExportedNullToValue() {
    assertNull(metadata.getDaysExported());
    metadata.setDaysExported(30);
    assertEquals(Integer.valueOf(30), metadata.getDaysExported());
  }

  /**
   * Tests changing days exported from a value to null.
   */
  @Test
  public void testDaysExportedValueToNull() {
    metadata.setDaysExported(30);
    assertEquals(Integer.valueOf(30), metadata.getDaysExported());
    
    metadata.setDaysExported(null);
    assertNull(metadata.getDaysExported());
  }
}
