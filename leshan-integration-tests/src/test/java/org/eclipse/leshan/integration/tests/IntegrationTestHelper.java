/*******************************************************************************
 * Copyright (c) 2013-2015 Sierra Wireless and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *     Zebra Technologies - initial API and implementation
 *******************************************************************************/

package org.eclipse.leshan.integration.tests;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.eclipse.leshan.client.LwM2mClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.client.util.LwM2mId;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.server.californium.LeshanServerBuilder;
import org.eclipse.leshan.server.californium.impl.LeshanServer;
import org.eclipse.leshan.server.client.Client;
import org.eclipse.leshan.server.impl.SecurityRegistryImpl;

/**
 * Helper for running a server and executing a client against it.
 * 
 */
public class IntegrationTestHelper {

    static final String ENDPOINT_IDENTIFIER = "kdfflwmtm";
    static final String MODEL_NUMBER = "IT-TEST-123";

    LeshanServer server;
    LwM2mClient client;

    public void createClient() {
        ObjectsInitializer initializer = new ObjectsInitializer();
        initializer.setInstancesForObject(
                LwM2mId.SECURITY_ID,
                Security.noSec("coap://" + server.getNonSecureAddress().getHostString() + ":"
                        + server.getNonSecureAddress().getPort(), 12345));
        initializer.setInstancesForObject(LwM2mId.SERVER_ID, new Server(12345, 30, BindingMode.U, false));
        initializer.setInstancesForObject(LwM2mId.DEVICE_ID, new Device("Eclipse Leshan", MODEL_NUMBER, "12345", "U"));
        List<LwM2mObjectEnabler> objects = initializer.createMandatory();
        objects.add(initializer.create(2));

        LeshanClientBuilder builder = new LeshanClientBuilder();
        builder.setEndpoint(ENDPOINT_IDENTIFIER);
        builder.setObjects(objects);
        client = builder.build();
    }

    public void createServer() {
        LeshanServerBuilder builder = new LeshanServerBuilder();
        builder.setLocalAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        builder.setLocalAddressSecure(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        builder.setSecurityRegistry(new SecurityRegistryImpl() {
            // TODO we should separate SecurityRegistryImpl in 2 registries :
            // InMemorySecurityRegistry and PersistentSecurityRegistry

            @Override
            protected void loadFromFile() {
                // do not load From File
            }

            @Override
            protected void saveToFile() {
                // do not save to file
            }
        });
        server = builder.build();
    }

    Client getClient() {
        return server.getClientRegistry().get(ENDPOINT_IDENTIFIER);
    }
}
