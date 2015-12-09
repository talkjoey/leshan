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

import static org.eclipse.leshan.client.util.LwM2mId.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
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
import org.eclipse.leshan.client.util.LwM2mId;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.request.DeregisterRequest;
import org.eclipse.leshan.core.request.ReadRequest;
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

    private RegisterInfo registerInfo;

    public LeshanClient(final String endpoint, final InetSocketAddress clientAddress,
            final List<LwM2mObjectEnabler> objectEnablers, final CoapServer serverLocal) {

        Validate.notNull(endpoint);
        Validate.notNull(clientAddress);
        Validate.notNull(serverLocal);
        Validate.notEmpty(objectEnablers);

        this.endpoint = endpoint;

        final Endpoint coapEndpoint = new CoapEndpoint(clientAddress);
        serverLocal.addEndpoint(coapEndpoint);

        clientSideServer = serverLocal;

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

        // extract DM info from server/security objects
        registerInfo = this.getRegisterInfo();
        if (registerInfo == null) {
            throw new IllegalArgumentException("Could not extract registration info from objects");
        }
        LOG.debug("Registration info: {}", registerInfo);

        requestSender = new CaliforniumLwM2mClientRequestSender(serverLocal.getEndpoint(clientAddress),
                new InetSocketAddress(registerInfo.serverUri.getHost(), registerInfo.serverUri.getPort()), this);
    }

    @Override
    public void start() {
        clientSideServer.start();
        clientServerStarted.set(true);
    }

    public boolean register() {

        if (registerInfo == null) {
            LOG.error("Missing info to register to a DM server");
            return false;
        }

        RegisterResponse response = this.send(new RegisterRequest(endpoint, registerInfo.lifetime, null,
                registerInfo.binding, null, null));

        if (response.getCode() == ResponseCode.CREATED) {
            LOG.info("Registered with location '{}'", response.getRegistrationID());
            registrationID = response.getRegistrationID();

            // update every lifetime period
            regUpdateFuture = schedExecutor.scheduleAtFixedRate(new UpdateRegistration(), registerInfo.lifetime,
                    registerInfo.lifetime, TimeUnit.SECONDS);
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
        return requestSender.send(request, null);
    }

    @Override
    public <T extends LwM2mResponse> T send(final UplinkRequest<T> request, long timeout) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        return requestSender.send(request, timeout);
    }

    @Override
    public <T extends LwM2mResponse> void send(final UplinkRequest<T> request,
            final ResponseCallback<T> responseCallback, final ErrorCallback errorCallback) {
        if (!clientServerStarted.get()) {
            throw new RuntimeException("Internal CoapServer is not started.");
        }
        requestSender.send(request, responseCallback, errorCallback);
    }

    @Override
    public List<LwM2mObjectEnabler> getObjectEnablers() {
        return new ArrayList<>(objectEnablers.values());
    }

    private static class RegisterInfo {
        private URI serverUri;
        private long lifetime;
        private BindingMode binding;

        // TODO sms number

        @Override
        public String toString() {
            return String.format("serverUri=%s, lifetime=%s, binding=%s", serverUri, lifetime, binding);
        }
    }

    private RegisterInfo getRegisterInfo() {

        LwM2mObjectEnabler securityEnabler = this.objectEnablers.get(SECURITY_ID);
        LwM2mObjectEnabler serverEnabler = this.objectEnablers.get(SERVER_ID);

        if (securityEnabler != null && serverEnabler != null) {

            if (serverEnabler.getAvailableInstanceIds().size() > 1) {
                throw new IllegalStateException("Only one DM server supported for now");
            }

            LwM2mObjectInstance serverInstance = (LwM2mObjectInstance) serverEnabler.read(
                    new ReadRequest(SERVER_ID, serverEnabler.getAvailableInstanceIds().get(0)), true).getContent();

            LwM2mObject secObject = (LwM2mObject) securityEnabler.read(new ReadRequest(SECURITY_ID), true).getContent();

            // find security info for the DM server (identified by its short server ID)
            for (LwM2mObjectInstance secInstance : secObject.getInstances().values()) {

                // is it the DM server?
                if (secInstance.getResource(SEC_SERVER_ID).getValue()
                        .equals(serverInstance.getResource(SRV_SERVER_ID).getValue()) //
                        && (Boolean) secInstance.getResource(LwM2mId.SEC_BOOTSTRAP).getValue() == false) {
                    RegisterInfo info = new RegisterInfo();
                    try {
                        info.serverUri = new URI((String) secInstance.getResource(SEC_SERVER_URI).getValue());
                        info.lifetime = (long) serverInstance.getResource(SRV_LIFETIME).getValue();
                        // TODO check supported binding (from resource /3/0/16)
                        info.binding = BindingMode.valueOf((String) serverInstance.getResource(SRV_BINDING).getValue());
                        return info;
                    } catch (URISyntaxException e) {
                        LOG.error("Invalid DM server URI", e);
                        return null;
                    }
                }
            }
        }
        return null;
    }

}
