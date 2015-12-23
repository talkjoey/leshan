/*******************************************************************************
 * Copyright (c) 2015 Sierra Wireless and others.
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
 *     Sierra Wireless - initial API and implementation
 *******************************************************************************/
package org.eclipse.leshan.integration.tests;

import static org.eclipse.leshan.integration.tests.IntegrationTestHelper.ENDPOINT_IDENTIFIER;
import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import org.eclipse.leshan.bootstrap.BootstrapSecurityStore;
import org.eclipse.leshan.bootstrap.BootstrapStoreImpl;
import org.eclipse.leshan.bootstrap.ConfigurationChecker.ConfigurationException;
import org.eclipse.leshan.client.californium.LeshanClient;
import org.eclipse.leshan.client.californium.LeshanClientBuilder;
import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.client.util.LwM2mId;
import org.eclipse.leshan.server.bootstrap.BootstrapConfig;
import org.eclipse.leshan.server.bootstrap.BootstrapConfig.ServerConfig;
import org.eclipse.leshan.server.bootstrap.BootstrapConfig.ServerSecurity;
import org.eclipse.leshan.server.bootstrap.SecurityMode;
import org.eclipse.leshan.server.californium.impl.LwM2mBootstrapServerImpl;
import org.eclipse.leshan.server.security.SecurityStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class BootstrapTest {

    private final IntegrationTestHelper helper = new IntegrationTestHelper();

    @Before
    public void start() {
        // DM server
        helper.createServer();
        helper.server.start();
    }

    @After
    public void stop() {
        try {
            helper.server.stop();
        } catch (Exception e) {
            //
        }
    }

    private LwM2mBootstrapServerImpl createBootstrapServer(InetSocketAddress bsNonSecure, InetSocketAddress bsSecure,
            InetSocketAddress dmNonSecure) throws ConfigurationException {

        BootstrapStoreImpl bsStore = new BootstrapStoreImpl();
        SecurityStore securityStore = new BootstrapSecurityStore(bsStore);

        return new LwM2mBootstrapServerImpl(bsNonSecure, bsSecure, bsStore, securityStore);
    }

    private LeshanClient createClient(InetSocketAddress bsNonSecureAddress) {
        ObjectsInitializer initializer = new ObjectsInitializer();

        // set only the bootstrap server instance (security)
        initializer.setInstancesForObject(LwM2mId.SECURITY_ID,
                new Security("coap://" + bsNonSecureAddress.getHostString() + ":" + bsNonSecureAddress.getPort(), true,
                        3, new byte[0], new byte[0], new byte[0], 12345));

        initializer.setInstancesForObject(LwM2mId.DEVICE_ID, new Device("Eclipse Leshan",
                IntegrationTestHelper.MODEL_NUMBER, "12345", "U"));
        List<LwM2mObjectEnabler> objects = initializer.createMandatory();
        objects.add(initializer.create(2));

        LeshanClientBuilder builder = new LeshanClientBuilder();
        builder.setEndpoint(IntegrationTestHelper.ENDPOINT_IDENTIFIER);
        builder.setObjects(objects);
        return builder.build();
    }

    private BootstrapConfig getBsConfig(InetSocketAddress bsNonSecure) {
        BootstrapConfig bsConfig = new BootstrapConfig();

        // security for BS server
        ServerSecurity bsSecurity = new ServerSecurity();
        bsSecurity.serverId = 1111;
        bsSecurity.bootstrapServer = true;
        bsSecurity.uri = "coap://" + bsNonSecure.getHostString() + ":" + bsNonSecure.getPort();
        bsSecurity.securityMode = SecurityMode.NO_SEC;
        bsConfig.security.put(0, bsSecurity);

        // security for DM server
        ServerSecurity dmSecurity = new ServerSecurity();
        dmSecurity.uri = "coap://" + helper.server.getNonSecureAddress().getHostString() + ":"
                + helper.server.getNonSecureAddress().getPort();
        dmSecurity.serverId = 2222;
        dmSecurity.securityMode = SecurityMode.NO_SEC;
        bsConfig.security.put(1, dmSecurity);

        // DM server
        ServerConfig dmConfig = new ServerConfig();
        dmConfig.shortId = 2222;
        bsConfig.servers.put(0, dmConfig);

        return bsConfig;
    }

    @Test
    public void bootstrap() throws ConfigurationException, InterruptedException {

        // BS server
        InetSocketAddress bsNonSecureAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        InetSocketAddress bsSecureAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        LwM2mBootstrapServerImpl bsServer = createBootstrapServer(bsNonSecureAddress, bsSecureAddress,
                helper.server.getNonSecureAddress());
        bsServer.start();

        // set boostrap config for endpoint
        BootstrapStoreImpl bsStore = (BootstrapStoreImpl) bsServer.getBoostrapStore();
        bsStore.addConfig(IntegrationTestHelper.ENDPOINT_IDENTIFIER, this.getBsConfig(bsServer.getNonSecureAddress()));

        LeshanClient client = createClient(bsServer.getNonSecureAddress());
        try {
            client.start();
            client.bootstrap();

            Thread.sleep(5000);

            // check the client is registered
            assertEquals(1, helper.server.getClientRegistry().allClients().size());
            assertNotNull(helper.server.getClientRegistry().get(ENDPOINT_IDENTIFIER));
        } finally {
            client.stop();
        }
    }
}
