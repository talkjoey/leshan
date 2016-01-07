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
package org.eclipse.leshan.client.californium;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig;
import org.eclipse.californium.scandium.config.DtlsConnectorConfig.Builder;
import org.eclipse.leshan.client.LwM2mClient;
import org.eclipse.leshan.client.californium.impl.BootstrapResource;
import org.eclipse.leshan.client.californium.impl.CaliforniumLwM2mClientRequestSender;
import org.eclipse.leshan.client.californium.impl.ObjectResource;
import org.eclipse.leshan.client.californium.impl.RootResource;
import org.eclipse.leshan.client.californium.impl.SecurityObjectPskStore;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.servers.BootstrapHandler;
import org.eclipse.leshan.client.servers.RegistrationEngine;
import org.eclipse.leshan.client.util.LwM2mId;
import org.eclipse.leshan.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Lightweight M2M client.
 */
public class LeshanClient implements LwM2mClient {

    private static final Logger LOG = LoggerFactory.getLogger(LeshanClient.class);

    private final Map<Integer, LwM2mObjectEnabler> objectEnablers;

    private final CoapServer clientSideServer;
    private final CaliforniumLwM2mClientRequestSender requestSender;
    private final RegistrationEngine engine;
    private final BootstrapHandler bootstrapHandler;

    public LeshanClient(final String endpoint, final InetSocketAddress localAddress,
            InetSocketAddress localSecureAddress, final List<LwM2mObjectEnabler> objectEnablers) {

        Validate.notNull(endpoint);
        Validate.notNull(localAddress);
        Validate.notNull(localSecureAddress);
        Validate.notEmpty(objectEnablers);

        // Create Object enablers
        this.objectEnablers = new HashMap<>();
        for (LwM2mObjectEnabler enabler : objectEnablers) {
            if (this.objectEnablers.containsKey(enabler.getId())) {
                throw new IllegalArgumentException(String.format(
                        "There is several objectEnablers with the same id %d.", enabler.getId()));
            }
            this.objectEnablers.put(enabler.getId(), enabler);
        }

        // Create CoAP non secure endpoint
        final Endpoint nonSecureEndpoint = new CoapEndpoint(localAddress);

        // Create CoAP secure endpoint
        LwM2mObjectEnabler securityEnabler = this.objectEnablers.get(LwM2mId.SECURITY_ID);
        if (securityEnabler == null)
            throw new IllegalArgumentException("Security object is mandatory");

        Builder builder = new DtlsConnectorConfig.Builder(localSecureAddress);
        builder.setPskStore(new SecurityObjectPskStore(securityEnabler));
        final Endpoint secureEndpoint = new CoapEndpoint(new DTLSConnector(builder.build()),
                NetworkConfig.getStandard());

        // Create sender
        requestSender = new CaliforniumLwM2mClientRequestSender(secureEndpoint, nonSecureEndpoint, this);

        // Create registration engine
        bootstrapHandler = new BootstrapHandler(this.objectEnablers);

        // Create registration engine
        engine = new RegistrationEngine(endpoint, this.objectEnablers, requestSender, bootstrapHandler);

        // Create CoAP Server
        clientSideServer = new CoapServer() {
            @Override
            protected Resource createRoot() {
                // Use to handle Delete on "/"
                return new RootResource(bootstrapHandler);
            }
        };
        clientSideServer.addEndpoint(secureEndpoint);
        clientSideServer.addEndpoint(nonSecureEndpoint);

        // Create CoAP resources for each lwm2m Objects.
        for (LwM2mObjectEnabler enabler : objectEnablers) {
            final ObjectResource clientObject = new ObjectResource(enabler, bootstrapHandler);
            clientSideServer.add(clientObject);
        }

        // Create CoAP resources needed for the bootstrap sequence
        clientSideServer.add(new BootstrapResource(bootstrapHandler));

        // De-register on shutdown and stop client.
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                LOG.debug("Deregistering on shutdown");
                LeshanClient.this.destroy();
            }
        });
    }

    @Override
    public void start() {
        clientSideServer.start();
        engine.start();
    }

    @Override
    public void stop() {
        engine.stop();
        clientSideServer.stop();
    }

    @Override
    public void destroy() {
        engine.stop();
        clientSideServer.destroy();
    }

    @Override
    public List<LwM2mObjectEnabler> getObjectEnablers() {
        return new ArrayList<>(objectEnablers.values());
    }

    public CoapServer getCoapServer() {
        return clientSideServer;
    }
}
