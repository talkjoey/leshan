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

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.leshan.ResponseCode;
import org.eclipse.leshan.client.request.LwM2mClientRequestSender;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.core.request.BootstrapRequest;
import org.eclipse.leshan.core.request.DeregisterRequest;
import org.eclipse.leshan.core.request.RegisterRequest;
import org.eclipse.leshan.core.request.UpdateRequest;
import org.eclipse.leshan.core.response.BootstrapResponse;
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

    private AtomicBoolean bootstrapping = new AtomicBoolean(false);
    private CountDownLatch bootstrappingLatch = new CountDownLatch(1);

    /**
     * Notifies the engine that the bootstrap sequence is finished
     */
    public void bootstrapFinished() {
        bootstrappingLatch.countDown();
    }

    /**
     * @return <code>true</code> if the client is currently bootstrapping
     */
    public boolean bootstrapping() {
        return bootstrapping.get();
    }

    private void bootstrap() {

        if (serversInfo.bootstrap == null) {
            LOG.error("Missing info to boostrap the client");
            return;
        }

        if (bootstrapping.compareAndSet(false, true)) {
            try {
                LOG.info("Starting bootstrap session");

                // send bootstrap request
                BootstrapResponse response = sender.send(serversInfo.bootstrap.getAddress(), new BootstrapRequest(
                        endpoint), null);
                if (response == null) {
                    LOG.error("Bootstrap failed: timeout");
                } else if (response.getCode() == ResponseCode.CHANGED) {

                    // wait until it is finished (or too late)
                    bootstrappingLatch = new CountDownLatch(1);

                    boolean timeout = bootstrappingLatch.await(10, TimeUnit.SECONDS);
                    if (timeout) {
                        LOG.error("Bootstrap sequence timeout");
                    } else {
                        LOG.info("Bootstrap finished");
                    }

                } else {
                    LOG.error("Bootstrap failed: {}", response.getCode());
                }
            } catch (InterruptedException e) {
                LOG.error("Error while waiting for bootstrap finish", e);
            } finally {
                bootstrapping.set(false);
            }
        } else {
            LOG.info("Bootstrap sequence already started");
        }
    }

    public boolean register() {
        DmServerInfo dmInfo = serversInfo.deviceMangements.values().iterator().next();

        if (dmInfo == null) {
            LOG.error("Missing info to register to a DM server");
            return false;
        }

        // send register request
        RegisterResponse response = sender.send(dmInfo.getAddress(), new RegisterRequest(endpoint, dmInfo.lifetime,
                null, dmInfo.binding, null, null), null);
        if (response == null) {
            registrationID = null;
            LOG.error("Registration failed: timeout");
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
        DeregisterResponse response = sender.send(dmInfo.getAddress(), new DeregisterRequest(registrationID), null);
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
        final UpdateResponse response = sender.send(dmInfo.getAddress(), new UpdateRequest(registrationID, null, null,
                null, null), null);
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