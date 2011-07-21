/**
 * Copyright (c) 2009 Red Hat, Inc.
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
package org.fedoraproject.candlepin.sync;

import static org.mockito.Mockito.*;

import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.fedoraproject.candlepin.config.Config;
import org.fedoraproject.candlepin.config.ConfigProperties;
import org.fedoraproject.candlepin.model.Owner;
import org.fedoraproject.candlepin.model.OwnerCurator;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ConsumerImporterTest
 */
public class ConsumerImporterTest {
    
    private ConsumerImporter importer;
    private ObjectMapper mapper;
    private OwnerCurator curator; 

    @Before
    public void setUp() {
        curator = mock(OwnerCurator.class);
        importer = new ConsumerImporter(curator);
        mapper = SyncUtils.getObjectMapper(new Config(new HashMap<String, String>()));
    }

    @Test
    public void importShouldCreateAValidConsumer() throws IOException, ImporterException {
        ConsumerDto consumer = 
            importer.createObject(mapper, new StringReader("{\"uuid\":\"test-uuid\"}"));
        
        assertEquals("test-uuid", consumer.getUuid());
    }
    
    @Test
    public void importHandlesUnknownPropertiesGracefully() throws Exception {

        // Override default config to error out on unknown properties:
        Map<String, String> configProps = new HashMap<String, String>();
        configProps.put(ConfigProperties.FAIL_ON_UNKNOWN_IMPORT_PROPERTIES, "false");
        mapper = SyncUtils.getObjectMapper(new Config(configProps));

        ConsumerDto consumer =
            importer.createObject(mapper, new StringReader(
                "{\"uuid\":\"test-uuid\", \"unknown\":\"notreal\"}"));
        assertEquals("test-uuid", consumer.getUuid());
    }

    @Test(expected = JsonMappingException.class)
    public void importFailsOnUnknownPropertiesWithDefaultConfig() throws Exception {
        importer.createObject(mapper, new StringReader(
            "{\"uuid\":\"test-uuid\", \"unknown\":\"notreal\"}"));
    }

    @Test
    public void importConsumerWithNullUuidOnOwnerShouldSetUuid() throws IOException, ImporterException {
        Owner owner = new Owner();
        ConsumerDto consumer = new ConsumerDto();
        consumer.setUuid("test-uuid");
        
        importer.store(owner, consumer);
        
        assertEquals("test-uuid", owner.getUpstreamUuid());
        verify(curator).merge(owner);
    }
    
    @Test
    public void importConsumerWithSameUuidOnOwnerShouldDoNothing() throws ImporterException {
        Owner owner = new Owner();
        owner.setUpstreamUuid("test-uuid");
        ConsumerDto consumer = new ConsumerDto();
        consumer.setUuid("test-uuid");
        
        importer.store(owner, consumer);
        
        assertEquals("test-uuid", owner.getUpstreamUuid());
    }
    
    @Test(expected = ImporterException.class)
    public void importConsumerWithMismatchedUuidShouldThrowException() throws ImporterException {
        Owner owner = new Owner();
        owner.setUpstreamUuid("another-test-uuid");
        ConsumerDto consumer = new ConsumerDto();
        consumer.setUuid("test-uuid");
        
        importer.store(owner, consumer);
    }

    @Test(expected = ImporterException.class)
    public void importConsumerWithNullUuidOnConsumerShouldThrowException() throws ImporterException {
        Owner owner = new Owner();
        ConsumerDto consumer = new ConsumerDto();
        consumer.setUuid(null);
        
        importer.store(owner, consumer);
    }
}
