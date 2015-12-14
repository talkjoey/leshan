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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.californium.core.CoapServer;
import org.eclipse.californium.core.network.CoapEndpoint;
import org.eclipse.californium.core.network.Endpoint;
import org.eclipse.leshan.ResponseCode;
import org.eclipse.leshan.client.LwM2mClient;
import org.eclipse.leshan.client.californium.impl.CaliforniumLwM2mClientRequestSender;
import org.eclipse.leshan.client.californium.impl.ObjectResource;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.servers.DmServerInfo;
import org.eclipse.leshan.client.servers.ServersInfo;
import org.eclipse.leshan.client.servers.ServersInfoExtractor;
import org.eclipse.leshan.core.request.DeregisterRequest;
import org.eclipse.leshan.core.request.RegisterRequest;
import org.eclipse.leshan.core.request.UpdateRequest;
import org.eclipse.leshan.core.request.UplinkRequest;
import org.eclipse.leshan.core.response.DeregisterResponse;
import org.eclipse.leshan.core.response.ErrorCallback;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.RegisterResponse;
import org.eclipse.leshan.core.response.ResponseCallback;
import org.eclipse.leshan.util.Validate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Lightweight M2M client.
 */
public class LeshanClient implements LwM2mClient {

    private static final Logger LOG = LoggerFactory.getLogger(LeshanClient.class);

    private final String endpoint;
    private final Map<Integer, LwM2mObjectEnabler> objectEnablers;

    private final CoapServer clientSideServer;
    private final AtomicBoolean clientServerStarted = new AtomicBoolean(false);
    private final CaliforniumLwM2mClientRequestSender requestSender;

    private ServersInfo serversInfo;

    public LeshanClient(final String endpoint, final InetSocketAddress clientAddress,
            final List<LwM2mObjectEnabler> objectEnablers, final CoapServer serverLocal) {

        Validate.notNull(endpoint);
        Validate.notNull(clientAddress);
        Validate.notNull(serverLocal);
        Validate.notEmpty(objectEnablers);

        this.endpoint = endpoint;

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

        // Extract DM info from server/security objects
        serversInfo = ServersInfoExtractor.getInfo(this.objectEnablers);
        if (serversInfo == null || serversInfo.deviceMangements.isEmpty()) {
            throw new IllegalArgumentException("Could not extract registration info from objects");
        }
        LOG.debug("Registration info: {}", serversInfo);

        // Create sender
        requestSender = new CaliforniumLwM2mClientRequestSender(serverLocal.getEndpoint(clientAddress), this);
    }

    @Override
    public void start() {
        clientSideServer.start();
        clientServerStarted.set(true);
    }

    public boolean register() {
        DmServerInfo dmInfo = serversInfo.deviceMangements.get(0);

        if (dmInfo == null) {
            LOG.error("Missing info to register to a DM server");
            return false;
        }

        RegisterResponse response = this.send(new RegisterRequest(endpoint, dmInfo.lifetime, null, dmInfo.binding,
                null, null));

        if (response.getCode() == ResponseCode.CREATED) {
            LOG.info("Registered with location '{}'", response.getRegistrationID());
            registrationID = response.getRegistrationID();

            // update every lifetime period
            regUpdateFuture = schedExecutor.scheduleAtFixedRate(new UpdateRegistration(), dmInfo.lifetime,
                    dmInfo.lifetime, TimeUnit.SECONDS);
        } else {
            LOG.error("Registration failed: {}", response.getCode());
        }

        // De-register on shutdown and stop client.
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                if (registrationID != null) {
                    LOG.debug("Deregistering on shutdown");
                    LeshanClient.this.deregister();
                    LeshanClient.this.destroy();
                }
            }
        });

        return registrationID != null;
    }

    public void deregister() {
        if (registrationID != null) {
            DeregisterResponse resp = this.send(new DeregisterRequest(registrationID));
            LOG.debug("De-register response:" + resp);
            registrationID = null;
        }
    }

    // registration update
    private String registrationID;
    private ScheduledFuture<?> regUpdateFuture;
    private final ScheduledExecutorService schedExecutor = Executors.newScheduledThreadPool(1);

    private class UpdateRegistration implements Runnable {

        @Override
        public void run() {
            final LwM2mResponse response = LeshanClient.this.send(new UpdateRequest(registrationID, null, null, null,
                    null));
            LOG.debug("Registration update: {}", response.getCode());
        }
    }

    @Override
    public void stop() {
        if (regUpdateFuture != null) {
            regUpdateFuture.cancel(true);
        }
        deregister();
        clientSideServer.stop();
        clientServerStarted.set(false);
    }

    @Override
    public void destroy() {
        if (regUpdateFuture != null) {
            regUpdateFuture.cancel(true);
        }
        deregister();
        clientSideServer.destroy();
        clientServerStarted.set(false);
    }

    @Override
    public <T extends LwM2mResponse> T send(final UplinkRequest<T> request) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }

        DmServerInfo dmInfo = serversInfo.deviceMangements.get(0);
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        return requestSender.send(serverAddress, request, null);
    }

    @Override
    public <T extends LwM2mResponse> T send(final UplinkRequest<T> request, long timeout) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        DmServerInfo dmInfo = serversInfo.deviceMangements.get(0);
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        return requestSender.send(serverAddress, request, timeout);
    }

    @Override
    public <T extends LwM2mResponse> void send(final UplinkRequest<T> request,
            final ResponseCallback<T> responseCallback, final ErrorCallback errorCallback) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        DmServerInfo dmInfo = serversInfo.deviceMangements.get(0);
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        requestSender.send(serverAddress, request, responseCallback, errorCallback);
    }

    @Override
    public List<LwM2mObjectEnabler> getObjectEnablers() {
        return new ArrayList<>(objectEnablers.values());
    }
}
