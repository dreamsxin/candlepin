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
package org.candlepin.audit;

import static org.junit.Assert.*;
import static org.mockito.Matchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;


/**
 * EventSourceTest
 */
@RunWith(MockitoJUnitRunner.class)
public class EventSourceTest {

    @Mock private ClientSessionFactory clientSessionFactory;
    @Mock private ClientSession clientSession;

    @Before
    public void init() throws Exception {
        when(clientSessionFactory.createSession(eq(true), eq(true), eq(0)))
            .thenReturn(clientSession);
    }
    /**
     * @return
     */
    private EventSource createEventSourceStubbedWithFactoryCreation() {
        return new EventSource(new ObjectMapper()) {
            protected ClientSessionFactory createSessionFactory() {
                return clientSessionFactory;
            }
        };
    }

    @Test
    public void shouldStartSessionWhenCreatingEventSource() throws Exception {
        createEventSourceStubbedWithFactoryCreation();
        verify(this.clientSession).start();
    }

    @Test(expected = RuntimeException.class)
    public void shouldThrowRunTimeExceptionWhenSessionCreateFailsDuringEventSourceCreation()
        throws Exception {
        doThrow(new ActiveMQException()).when(clientSession).start();
        createEventSourceStubbedWithFactoryCreation();
        fail("Should have thrown runtime exception");
    }

    @Test
    public void shouldNotThrowExceptionWhenQueueCreationFails() throws Exception {
        EventSource eventSource = createEventSourceStubbedWithFactoryCreation();
        doThrow(new ActiveMQException(ActiveMQExceptionType.QUEUE_DOES_NOT_EXIST))
            .when(clientSession).createQueue(anyString(), anyString(), eq(true));
        EventListener eventListener = mock(EventListener.class);
        eventSource.registerListener(eventListener);

        verify(clientSession, never()).createConsumer(anyString()); //should not be invoked
    }

    @Test
    public void shouldCreateNewConsumerWhenQueueDoesNotExistOnRegisterListenerCall()
        throws Exception {
        EventSource eventSource = createEventSourceStubbedWithFactoryCreation();
        ClientConsumer mockCC = mock(ClientConsumer.class);
        when(clientSession.createConsumer(anyString()))
            .thenReturn(mockCC);
        EventListener eventListener = mock(EventListener.class);
        eventSource.registerListener(eventListener);

        //make sure queue is created.
        verify(clientSession).createQueue(anyString(), anyString(), eq(true));
        verify(mockCC).setMessageHandler(any(ListenerWrapper.class));
    }

    @Test
    public void shouldCreateNewConsumerWhenQueueExistsOnRegisterListenerCall()
        throws Exception {
        EventSource eventSource = createEventSourceStubbedWithFactoryCreation();
        ClientConsumer mockCC = mock(ClientConsumer.class);
        when(clientSession.createConsumer(anyString()))
            .thenReturn(mockCC);
        doThrow(new ActiveMQException(ActiveMQExceptionType.QUEUE_EXISTS))
            .when(clientSession).createQueue(anyString(), anyString());
        EventListener eventListener = mock(EventListener.class);
        //invoke
        eventSource.registerListener(eventListener);

        //verify listener is still added.
        verify(mockCC).setMessageHandler(any(ListenerWrapper.class));
    }

    @Test
    public void shouldStopAndCloseSessionOnShutdown() throws Exception {
        EventSource eventSource = createEventSourceStubbedWithFactoryCreation();

        eventSource.shutDown();

        verify(clientSession).stop();
        verify(clientSession).close();
    }

    @Test
    public void shouldStopAndCloseSessionOnShutdownWhenExceptionThrownOnStop()
        throws Exception {
        EventSource eventSource = createEventSourceStubbedWithFactoryCreation();
        doThrow(new ActiveMQException()).when(clientSession).stop();

        eventSource.shutDown();

        assertTrue(true); //so sorry!
    }

    @Test
    public void shouldStopAndCloseSessionOnShutdownWhenExceptionThrownOnClose()
        throws Exception {
        EventSource eventSource = createEventSourceStubbedWithFactoryCreation();
        doThrow(new ActiveMQException()).when(clientSession).close();

        eventSource.shutDown();
        verify(this.clientSession).stop();
    }

}
