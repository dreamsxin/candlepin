/**
 * Copyright (c) 2009 - 2018 Red Hat, Inc.
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
package org.candlepin.dto.manifest.v1;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import org.candlepin.dto.AbstractTranslatorTest;
import org.candlepin.dto.ModelTranslator;
import org.candlepin.model.Consumer;
import org.candlepin.model.ConsumerType;
import org.candlepin.model.ConsumerTypeCurator;
import org.candlepin.model.Owner;
import org.junit.runner.RunWith;

import junitparams.JUnitParamsRunner;



/**
 * Test suite for the ConsumerTranslator (manifest import/export) class
 */
@RunWith(JUnitParamsRunner.class)
public class ConsumerTranslatorTest extends
    AbstractTranslatorTest<Consumer, ConsumerDTO, ConsumerTranslator> {

    protected ConsumerTypeCurator mockConsumerTypeCurator;

    protected ConsumerTranslator translator;

    protected ConsumerTypeTranslatorTest consumerTypeTranslatorTest = new ConsumerTypeTranslatorTest();

    @Override
    protected void initModelTranslator(ModelTranslator modelTranslator) {
        this.consumerTypeTranslatorTest.initModelTranslator(modelTranslator);

        this.mockConsumerTypeCurator = mock(ConsumerTypeCurator.class);
        this.translator = new ConsumerTranslator(this.mockConsumerTypeCurator);

        modelTranslator.registerTranslator(this.translator, Consumer.class, ConsumerDTO.class);
    }

    @Override
    protected ConsumerTranslator initObjectTranslator() {
        return this.translator;
    }

    @Override
    protected Consumer initSourceObject() {
        ConsumerType ctype = this.consumerTypeTranslatorTest.initSourceObject();
        Consumer consumer = new Consumer();

        consumer.setUuid("consumer_uuid");
        consumer.setName("consumer_name");
        Owner owner = new Owner();
        owner.setId("owner_id");
        consumer.setOwner(owner);
        consumer.setContentAccessMode("test_content_access_mode");
        consumer.setType(ctype);

        when(mockConsumerTypeCurator.find(eq(ctype.getId()))).thenReturn(ctype);
        when(mockConsumerTypeCurator.getConsumerType(eq(consumer))).thenReturn(ctype);

        return consumer;
    }

    @Override
    protected ConsumerDTO initDestinationObject() {
        // Nothing fancy to do here.
        return new ConsumerDTO();
    }

    @Override
    protected void verifyOutput(Consumer source, ConsumerDTO dest, boolean childrenGenerated) {

        if (source != null) {
            assertEquals(source.getUuid(), dest.getUuid());
            assertEquals(source.getName(), dest.getName());
            assertEquals(source.getContentAccessMode(), dest.getContentAccessMode());

            if (childrenGenerated) {
                Owner sourceOwner = source.getOwner();
                if (sourceOwner != null) {
                    assertEquals(sourceOwner.getId(), dest.getOwner());
                }

                ConsumerType ctype = this.mockConsumerTypeCurator.getConsumerType(source);
                this.consumerTypeTranslatorTest.verifyOutput(ctype, dest.getType(), true);
            }
            else {
                assertNull(dest.getOwner());
                assertNull(dest.getType());
            }
        }
        else {
            assertNull(dest);
        }
    }
}
