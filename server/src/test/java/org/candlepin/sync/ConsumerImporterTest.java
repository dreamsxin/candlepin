/**
 * Copyright (c) 2009 - 2012 Red Hat, Inc.
 *
 * This software is licensed to you under the GNU General Public License,
 * version 2 (GPLv2). There is NO WARRANTY for this software, express or
 * implied, including the implied warranties of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. You should have received a copy of GPLv2
 * along with this software; if not, see
 * http://www.gnu.org/licenses/old-licenses/gpl-2.0.txt.
 *
 * Red Hat trademarks are not licensed under GPLv2. No permission is
 * granted to use or replicate Red Hat trademarks that are incorporated
 * in this software or its documentation.
 */
package org.candlepin.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.candlepin.common.config.MapConfiguration;
import org.candlepin.config.ConfigProperties;
import org.candlepin.dto.manifest.v1.ConsumerDTO;
import org.candlepin.dto.manifest.v1.ConsumerTypeDTO;
import org.candlepin.model.CertificateSerial;
import org.candlepin.model.CertificateSerialCurator;
import org.candlepin.model.IdentityCertificate;
import org.candlepin.model.IdentityCertificateCurator;
import org.candlepin.model.Owner;
import org.candlepin.model.OwnerCurator;
import org.candlepin.model.UpstreamConsumer;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.xnap.commons.i18n.I18n;
import org.xnap.commons.i18n.I18nFactory;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * ConsumerImporterTest
 */
public class ConsumerImporterTest {

    private ConsumerImporter importer;
    private ObjectMapper mapper;
    private OwnerCurator curator;
    private CertificateSerialCurator serialCurator;
    private IdentityCertificateCurator idCertCurator;
    private I18n i18n;

    @Before
    public void setUp() {
        curator = mock(OwnerCurator.class);
        serialCurator = mock(CertificateSerialCurator.class);
        idCertCurator = mock(IdentityCertificateCurator.class);
        i18n = I18nFactory.getI18n(getClass(), Locale.US, I18nFactory.FALLBACK);
        importer = new ConsumerImporter(curator, idCertCurator, i18n, serialCurator);
        mapper = TestSyncUtils.getTestSyncUtils(new MapConfiguration(
            new HashMap<String, String>() {
                {
                    put(ConfigProperties.FAIL_ON_UNKNOWN_IMPORT_PROPERTIES, "false");
                }
            }
        ));
    }

    @Test
    public void importShouldCreateAValidConsumer() throws IOException {
        ConsumerDTO consumer =
            importer.createObject(mapper, new StringReader("{\"uuid\":\"test-uuid\",\"name\":\"test-name\"}"));

        assertEquals("test-uuid", consumer.getUuid());
        assertEquals("test-name", consumer.getName());
    }

    @Test
    public void importHandlesUnknownPropertiesGracefully() throws Exception {

        // Override default config to error out on unknown properties:
        Map<String, String> configProps = new HashMap<>();
        configProps.put(ConfigProperties.FAIL_ON_UNKNOWN_IMPORT_PROPERTIES, "false");
        mapper = TestSyncUtils.getTestSyncUtils(new MapConfiguration(configProps));

        ConsumerDTO consumer = importer.createObject(
            mapper, new StringReader("{\"uuid\":\"test-uuid\", \"unknown\":\"notreal\"}"));
        assertEquals("test-uuid", consumer.getUuid());
    }

    @Test(expected = JsonMappingException.class)
    public void importFailsOnUnknownPropertiesWithNonDefaultConfig() throws Exception {
        // Override default config to error out on unknown properties:
        Map<String, String> configProps = new HashMap<>();
        configProps.put(ConfigProperties.FAIL_ON_UNKNOWN_IMPORT_PROPERTIES, "true");
        mapper = TestSyncUtils.getTestSyncUtils(new MapConfiguration(configProps));

        importer.createObject(mapper, new StringReader(
            "{\"uuid\":\"test-uuid\", \"unknown\":\"notreal\"}"));
    }

    @Test
    public void importConsumerWithNullUuidOnOwnerShouldSetUuid() throws ImporterException {
        Owner owner = mock(Owner.class);
        ConsumerDTO consumer = mock(ConsumerDTO.class);
        ConsumerTypeDTO type = mock(ConsumerTypeDTO.class);

        when(owner.getId()).thenReturn("test-owner-id");
        when(consumer.getUuid()).thenReturn("test-uuid");
        when(consumer.getOwner()).thenReturn("owner-id");
        when(consumer.getType()).thenReturn(type);

        IdentityCertificate idCert = new IdentityCertificate();
        idCert.setSerial(new CertificateSerial());

        importer.store(owner, consumer, new ConflictOverrides(), idCert);

        // now verify that the owner has the upstream consumer set
        ArgumentCaptor<UpstreamConsumer> arg =
            ArgumentCaptor.forClass(UpstreamConsumer.class);

        verify(owner).setUpstreamConsumer(arg.capture());
        assertEquals("test-uuid", arg.getValue().getUuid());
        verify(curator).merge(owner);
    }

    @Test
    public void importConsumerWithSameUuidOnOwnerShouldDoNothing() throws ImporterException {
        Owner owner = mock(Owner.class);
        ConsumerDTO consumer = mock(ConsumerDTO.class);
        ConsumerTypeDTO type = mock(ConsumerTypeDTO.class);
        when(owner.getUpstreamUuid()).thenReturn("test-uuid");
        when(consumer.getUuid()).thenReturn("test-uuid");
        when(consumer.getOwner()).thenReturn("owner-id");
        when(consumer.getType()).thenReturn(type);

        IdentityCertificate idCert = new IdentityCertificate();
        idCert.setSerial(new CertificateSerial());

        importer.store(owner, consumer, new ConflictOverrides(), idCert);

        // now verify that the owner didn't change
        // arg.getValue() returns the Owner being stored
        ArgumentCaptor<Owner> arg = ArgumentCaptor.forClass(Owner.class);

        verify(curator).merge(arg.capture());
        assertEquals("test-uuid", arg.getValue().getUpstreamUuid());
    }

    @Test(expected = SyncDataFormatException.class)
    public void importConsumerWithSameUuidOnAnotherOwnerShouldThrowException()
        throws ImporterException {
        Owner owner = new Owner();
        UpstreamConsumer uc = new UpstreamConsumer("test-uuid");
        owner.setUpstreamConsumer(uc);
        ConsumerDTO consumer = new ConsumerDTO();
        consumer.setUuid("test-uuid");

        Owner anotherOwner = new Owner("other", "Other");
        anotherOwner.setId("blah");
        anotherOwner.setUpstreamConsumer(uc);
        when(curator.lookupWithUpstreamUuid(consumer.getUuid())).thenReturn(anotherOwner);

        importer.store(owner, consumer, new ConflictOverrides(), null);
    }

    @Test
    public void importConsumerWithMismatchedUuidShouldThrowException() throws ImporterException {
        Owner owner = mock(Owner.class);
        ConsumerDTO consumer = mock(ConsumerDTO.class);
        when(owner.getUpstreamUuid()).thenReturn("another-test-uuid");
        when(consumer.getUuid()).thenReturn("test-uuid");
        when(consumer.getOwner()).thenReturn("owner-id");

        try {
            importer.store(owner, consumer, new ConflictOverrides(), null);
            fail();
        }
        catch (ImportConflictException e) {
            assertFalse(e.message().getConflicts().isEmpty());
            assertTrue(e.message().getConflicts().contains(
                Importer.Conflict.DISTRIBUTOR_CONFLICT));
        }
    }

    @Test
    public void importConsumerWithMismatchedUuidShouldNotThrowExceptionIfForced() throws ImporterException {
        Owner owner = mock(Owner.class);
        ConsumerDTO consumer = mock(ConsumerDTO.class);
        ConsumerTypeDTO type = mock(ConsumerTypeDTO.class);
        when(owner.getUpstreamUuid()).thenReturn("another-test-uuid");
        when(consumer.getUuid()).thenReturn("test-uuid");
        when(consumer.getOwner()).thenReturn("owner-id");
        when(consumer.getType()).thenReturn(type);

        IdentityCertificate idCert = new IdentityCertificate();
        idCert.setSerial(new CertificateSerial());

        importer.store(owner, consumer,
            new ConflictOverrides(Importer.Conflict.DISTRIBUTOR_CONFLICT), idCert);

        // now verify that the owner has the upstream consumer set
        ArgumentCaptor<UpstreamConsumer> arg =
            ArgumentCaptor.forClass(UpstreamConsumer.class);

        verify(owner).setUpstreamConsumer(arg.capture());
        assertEquals("test-uuid", arg.getValue().getUuid());
        verify(curator).merge(owner);
    }

    @Test(expected = ImporterException.class)
    public void importConsumerWithNullUuidOnConsumerShouldThrowException() throws ImporterException {
        Owner owner = new Owner();
        ConsumerDTO consumer = new ConsumerDTO();
        consumer.setUuid(null);

        importer.store(owner, consumer, new ConflictOverrides(), null);
    }

    /*
     * BZ#966860
     */
    @Test
    public void importConsumerWithNullIdCertShouldNotFail() throws ImporterException {
        Owner owner = mock(Owner.class);
        ConsumerDTO consumer = mock(ConsumerDTO.class);
        ConsumerTypeDTO type = mock(ConsumerTypeDTO.class);
        when(owner.getUpstreamUuid()).thenReturn("test-uuid");
        when(consumer.getUuid()).thenReturn("test-uuid");
        when(consumer.getOwner()).thenReturn("owner-id");
        when(consumer.getType()).thenReturn(type);

        importer.store(owner, consumer, new ConflictOverrides(), null);

        // now verify that the owner has the upstream consumer set
        ArgumentCaptor<UpstreamConsumer> arg =
            ArgumentCaptor.forClass(UpstreamConsumer.class);

        verify(owner).setUpstreamConsumer(arg.capture());
        assertEquals("test-uuid", arg.getValue().getUuid());
        verify(curator).merge(owner);
    }
}
