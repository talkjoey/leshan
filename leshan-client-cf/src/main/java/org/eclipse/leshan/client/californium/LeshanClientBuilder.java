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
package org.eclipse.leshan.client.californium;

import java.net.InetSocketAddress;
import java.util.List;

import org.eclipse.leshan.client.object.Device;
import org.eclipse.leshan.client.object.Security;
import org.eclipse.leshan.client.object.Server;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.ObjectsInitializer;
import org.eclipse.leshan.client.util.LwM2mId;
import org.eclipse.leshan.core.request.BindingMode;

/**
 * Helper class to build and configure a Californium based Leshan Lightweight M2M client.
 */
public class LeshanClientBuilder {

    private String endpoint;
    private InetSocketAddress localAddress;
    private InetSocketAddress localSecureAddress;
    private List<LwM2mObjectEnabler> objectEnablers;

    public LeshanClientBuilder setEndpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    /**
     * Sets the local end-point address
     */
    public LeshanClientBuilder setLocalAddress(String hostname, int port) {
        this.localAddress = new InetSocketAddress(hostname, port);
        return this;
    }

    /**
     * Sets the local secure end-point address
     */
    public LeshanClientBuilder setLocalSecureAddress(String hostname, int port) {
        this.localSecureAddress = new InetSocketAddress(hostname, port);
        return this;
    }

    /**
     * Sets the list of objects enablers
     */
    public LeshanClientBuilder setObjects(List<LwM2mObjectEnabler> objectEnablers) {
        this.objectEnablers = objectEnablers;
        return this;
    }

    public LeshanClient build() {

        if (endpoint == null) {
            endpoint = "leshan-client";
        }
        if (localAddress == null) {
            localAddress = new InetSocketAddress(0);
        }
        if (localSecureAddress == null) {
            localSecureAddress = new InetSocketAddress(0);
        }
        if (objectEnablers == null) {
            ObjectsInitializer initializer = new ObjectsInitializer();
            initializer.setInstancesForObject(LwM2mId.SECURITY_ID,
                    Security.noSec("coap://leshan.eclipse.org:5683", 12345));
            initializer.setInstancesForObject(LwM2mId.SERVER_ID, new Server(12345, 30, BindingMode.U, false));
            initializer
                    .setInstancesForObject(LwM2mId.DEVICE_ID, new Device("Eclipse Leshan", "TEST-123", "12345", "U"));
            objectEnablers = initializer.createMandatory();
        }
        return new LeshanClient(endpoint, localAddress, localSecureAddress, objectEnablers);
    }
}
