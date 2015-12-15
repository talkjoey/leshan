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
package org.eclipse.leshan.client.servers;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.leshan.ResponseCode;
import org.eclipse.leshan.client.request.LwM2mClientRequestSender;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.core.request.DeregisterRequest;
import org.eclipse.leshan.core.request.RegisterRequest;
import org.eclipse.leshan.core.request.UpdateRequest;
import org.eclipse.leshan.core.response.DeregisterResponse;
import org.eclipse.leshan.core.response.RegisterResponse;
import org.eclipse.leshan.core.response.UpdateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegistrationEngine {
    private static final Logger LOG = LoggerFactory.getLogger(RegistrationEngine.class);

    private String endpoint;
    private ServersInfo serversInfo;
    private LwM2mClientRequestSender sender;

    // registration update
    private String registrationID;
    private ScheduledFuture<?> regUpdateFuture;
    private final ScheduledExecutorService schedExecutor = Executors.newScheduledThreadPool(1);

    public RegistrationEngine(String endpoint, Map<Integer, LwM2mObjectEnabler> objectEnablers,
            LwM2mClientRequestSender requestSender) {
        this.endpoint = endpoint;
        serversInfo = ServersInfoExtractor.getInfo(objectEnablers);
        sender = requestSender;
    }

    public void start() {
        schedExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (!serversInfo.deviceMangements.isEmpty()) {
                    boolean success = register();
                    if (!success) {
                        bootstrap();
                    }
                } else {
                    bootstrap();
                }
            }
        });
    }

    private void bootstrap() {
        // TODO implement bootstrap
        LOG.info("Start Bootstrap session");
        System.out.println("Start Bootstrap session : not yet implemented");
    }

    public boolean register() {
        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();

        if (dmInfo == null) {
            LOG.error("Missing info to register to a DM server");
            return false;
        }

        // send register request
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        RegisterResponse response = sender.send(serverAddress, new RegisterRequest(endpoint, dmInfo.lifetime, null,
                dmInfo.binding, null, null), null);
        if (response == null) {
            registrationID = null;
            LOG.info("Registration failed: timeout");
        } else if (response.getCode() == ResponseCode.CREATED) {
            registrationID = response.getRegistrationID();

            // update every lifetime period
            regUpdateFuture = schedExecutor.schedule(new UpdateRegistration(), dmInfo.lifetime, TimeUnit.SECONDS);

            LOG.info("Registered with location '{}'", response.getRegistrationID());
        } else {
            registrationID = null;
            LOG.info("Registration failed: {}", response.getCode());
        }

        return registrationID != null;
    }

    public boolean deregister() {
        if (registrationID == null)
            return true;

        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();
        if (dmInfo == null) {
            LOG.error("Missing info to deregister to a DM server");
            return false;
        }

        // Send deregister request
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        DeregisterResponse response = sender.send(serverAddress, new DeregisterRequest(registrationID), null);
        if (response == null) {
            registrationID = null;
            LOG.info("Deregistration failed: timeout");
            return false;
        } else if (response.getCode() == ResponseCode.DELETED || response.getCode() == ResponseCode.NOT_FOUND) {
            registrationID = null;
            cancelUpdateTask();
            LOG.info("De-register response:" + response);
            return true;
        } else {
            LOG.info("Deregistration failed: {}", response);
            return false;
        }
    }

    public void update() {
        cancelUpdateTask();

        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();
        if (dmInfo == null) {
            LOG.error("Missing info to update registration to a DM server");
            return;
        }

        // Send update
        InetSocketAddress serverAddress = new InetSocketAddress(dmInfo.serverUri.getHost(), dmInfo.serverUri.getPort());
        final UpdateResponse response = sender.send(serverAddress, new UpdateRequest(registrationID, null, null, null,
                null), null);
        if (response == null) {
            // we can contact device management so we must start a new bootstrap session
            registrationID = null;
            LOG.info("Registration update failed: timeout");
            bootstrap();
        } else if (response.getCode() == ResponseCode.CHANGED) {
            // Update successful, so we reschedule new update
            regUpdateFuture = schedExecutor.schedule(new UpdateRegistration(), dmInfo.lifetime, TimeUnit.SECONDS);
            LOG.info("Registration update: {}", response.getCode());
        } else {
            // Update failed but server is here so start a new registration
            LOG.info("Registration update failed: {}", response);
            if (!register())
                bootstrap();
        }

    }

    private void cancelUpdateTask() {
        if (regUpdateFuture != null) {
            regUpdateFuture.cancel(false);
        }
    }

    private class UpdateRegistration implements Runnable {
        @Override
        public void run() {
            update();
        }
    }
}