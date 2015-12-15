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
import org.eclipse.leshan.client.LwM2mClient;
import org.eclipse.leshan.client.californium.impl.CaliforniumLwM2mClientRequestSender;
import org.eclipse.leshan.client.californium.impl.ObjectResource;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.servers.DmServerInfo;
import org.eclipse.leshan.client.servers.RegistrationEngine;
import org.eclipse.leshan.client.servers.ServersInfo;
import org.eclipse.leshan.client.servers.ServersInfoExtractor;
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

    public LeshanClient(final String endpoint, final InetSocketAddress clientAddress,
            final List<LwM2mObjectEnabler> objectEnablers, final CoapServer serverLocal) {

        Validate.notNull(endpoint);
        Validate.notNull(clientAddress);
        Validate.notNull(serverLocal);
        Validate.notEmpty(objectEnablers);

        // Create CoAP endpoint
        final Endpoint coapEndpoint = new CoapEndpoint(clientAddress);
        serverLocal.addEndpoint(coapEndpoint);
        clientSideServer = serverLocal;

        // Create CoAP resources for each lwm2m Objects.
        this.objectEnablers = new HashMap<>();
        for (LwM2mObjectEnabler enabler : objectEnablers) {
            if (clientSideServer.getRoot().getChild(Integer.toString(enabler.getId())) != null) {
                throw new IllegalArgumentException("Trying to load Client Object of name '" + enabler.getId()
                        + "' when one was already added.");
            }
            this.objectEnablers.put(enabler.getId(), enabler);

            final ObjectResource clientObject = new ObjectResource(enabler);
            clientSideServer.add(clientObject);
        }

        // Create sender
        requestSender = new CaliforniumLwM2mClientRequestSender(serverLocal.getEndpoint(clientAddress), this);

        // Create registration engine
        engine = new RegistrationEngine(endpoint, this.objectEnablers, requestSender);

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
        return requestSender.send(serverAddress, request, null);
    }

    @Override
    public <T extends LwM2mResponse> T send(final UplinkRequest<T> request, long timeout) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }

        ServersInfo serversInfo = ServersInfoExtractor.getInfo(objectEnablers);
        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        return requestSender.send(serverAddress, request, timeout);
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
        requestSender.send(serverAddress, request, responseCallback, errorCallback);
    }

    @Override
    public List<LwM2mObjectEnabler> getObjectEnablers() {
        return new ArrayList<>(objectEnablers.values());
    }
}
