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
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.californium.core.network.config.NetworkConfig;
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
import org.eclipse.leshan.client.servers.DmServerInfo;
import org.eclipse.leshan.client.servers.RegistrationEngine;
import org.eclipse.leshan.client.servers.ServersInfo;
import org.eclipse.leshan.client.servers.ServersInfoExtractor;
import org.eclipse.leshan.client.util.LwM2mId;
import org.eclipse.leshan.core.request.UplinkRequest;
import org.eclipse.leshan.core.response.ErrorCallback;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.ResponseCallback;
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
    private final AtomicBoolean clientServerStarted = new AtomicBoolean(false);
    private final CaliforniumLwM2mClientRequestSender requestSender;

    private final RegistrationEngine engine;

    public LeshanClient(final String endpoint, final InetSocketAddress localAddress,
            InetSocketAddress localSecureAddress, final List<LwM2mObjectEnabler> objectEnablers,
            final CoapServer serverLocal) {

        Validate.notNull(endpoint);
        Validate.notNull(localAddress);
        Validate.notNull(localSecureAddress);
        Validate.notNull(serverLocal);
        Validate.notEmpty(objectEnablers);

        // Object enablers
        this.objectEnablers = new HashMap<>();
        for (LwM2mObjectEnabler enabler : objectEnablers) {
            if (this.objectEnablers.containsKey(enabler.getId())) {
                throw new IllegalArgumentException(String.format(
                        "There is several objectEnablers with the same id %d.", enabler.getId()));
            }
            this.objectEnablers.put(enabler.getId(), enabler);
        }

        // Create CoAP endpoints
        Endpoint nonSecureEndpoint = serverLocal.getEndpoint(localAddress);
        if (nonSecureEndpoint == null) {
            nonSecureEndpoint = new CoapEndpoint(localAddress);
            serverLocal.addEndpoint(nonSecureEndpoint);
        }

        LwM2mObjectEnabler securityEnabler = this.objectEnablers.get(LwM2mId.SECURITY_ID);
        if (securityEnabler == null)
            throw new IllegalArgumentException("Security object is mandatory");

        Builder builder = new DtlsConnectorConfig.Builder(localSecureAddress);
        builder.setPskStore(new SecurityObjectPskStore(securityEnabler));
        Endpoint secureEndpoint = new CoapEndpoint(new DTLSConnector(builder.build()), NetworkConfig.getStandard());
        serverLocal.addEndpoint(secureEndpoint);
        clientSideServer = serverLocal;

        // Create sender
        requestSender = new CaliforniumLwM2mClientRequestSender(secureEndpoint, nonSecureEndpoint, this);

        // Create registration engine
        engine = new RegistrationEngine(endpoint, this.objectEnablers, requestSender);

        // Create CoAP resources for each lwm2m Objects.
        for (LwM2mObjectEnabler enabler : objectEnablers) {
            if (clientSideServer.getRoot().getChild(Integer.toString(enabler.getId())) != null) {
                throw new IllegalArgumentException("Trying to load Client Object of name '" + enabler.getId()
                        + "' when one was already added.");
            }
            final ObjectResource clientObject = new ObjectResource(enabler, engine);
            clientSideServer.add(clientObject);
        }

        // Create CoAP resources needed for the bootstrap sequence
        if (clientSideServer.getRoot() instanceof RootResource) {
            RootResource root = (RootResource) clientSideServer.getRoot();
            root.setEnablers(this.objectEnablers);
            root.setRegEngine(engine);
        } else {
            LOG.warn("The CoAP server has no leshan Root Resource, so it'is possible it can not handle BootstrapDelete request");
        }
        clientSideServer.add(new BootstrapResource(engine));

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
        clientServerStarted.set(true);
    }

    public void bootstrap() {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        engine.start();
    }

    public boolean register() {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        return engine.register();
    }

    public void deregister() {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        engine.deregister();
    }

    public void update() {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        engine.update();
    }

    @Override
    public void stop() {
        deregister();
        clientSideServer.stop();
        clientServerStarted.set(false);
    }

    @Override
    public void destroy() {
        deregister();
        clientSideServer.destroy();
        clientServerStarted.set(false);
    }

    @Override
    public <T extends LwM2mResponse> T send(final UplinkRequest<T> request) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }

        ServersInfo serversInfo = ServersInfoExtractor.getInfo(objectEnablers);
        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        return requestSender.send(serverAddress, false, request, null);
    }

    @Override
    public <T extends LwM2mResponse> T send(final UplinkRequest<T> request, long timeout) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }

        ServersInfo serversInfo = ServersInfoExtractor.getInfo(objectEnablers);
        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        return requestSender.send(serverAddress, false, request, timeout);
    }

    @Override
    public <T extends LwM2mResponse> void send(final UplinkRequest<T> request,
            final ResponseCallback<T> responseCallback, final ErrorCallback errorCallback) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        ServersInfo serversInfo = ServersInfoExtractor.getInfo(objectEnablers);
        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        requestSender.send(serverAddress, false, request, responseCallback, errorCallback);
    }

    @Override
    public List<LwM2mObjectEnabler> getObjectEnablers() {
        return new ArrayList<>(objectEnablers.values());
    }
}
