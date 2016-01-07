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
package org.eclipse.leshan.client.californium.impl;

import static org.eclipse.leshan.client.util.LwM2mId.SECURITY_ID;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_PUBKEY_IDENTITY;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_SECRET_KEY;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_SECURITY_MODE;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_SERVER_URI;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;

import org.eclipse.californium.scandium.dtls.pskstore.PskStore;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.request.ReadRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SecurityObjectPskStore implements PskStore {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityObjectPskStore.class);

    private final LwM2mObjectEnabler securityEnabler;

    public SecurityObjectPskStore(LwM2mObjectEnabler securityEnabler) {
        this.securityEnabler = securityEnabler;
    }

    @Override
    public byte[] getKey(String identity) {
        if (identity == null)
            return null;

        LwM2mObject securities = (LwM2mObject) securityEnabler.read(new ReadRequest(SECURITY_ID), null).getContent();
        for (LwM2mObjectInstance security : securities.getInstances().values()) {
            long securityMode = (long) security.getResource(SEC_SECURITY_MODE).getValue();
            // TODO use SecurityMode from serve.core ?
            if (securityMode == 0) // psk
            {
                byte[] pskIdentity = (byte[]) security.getResource(SEC_PUBKEY_IDENTITY).getValue();
                if (Arrays.equals(identity.getBytes(), pskIdentity))
                    return (byte[]) security.getResource(SEC_SECRET_KEY).getValue();
            }
        }
        return null;
    }

    @Override
    public String getIdentity(InetSocketAddress inetAddress) {
        if (inetAddress == null)
            return null;

        LwM2mObject securities = (LwM2mObject) securityEnabler.read(new ReadRequest(SECURITY_ID), null).getContent();
        for (LwM2mObjectInstance security : securities.getInstances().values()) {
            long securityMode = (long) security.getResource(SEC_SECURITY_MODE).getValue();
            // TODO use SecurityMode from serve.core ?
            if (securityMode == 0) // psk
            {
                try {
                    URI uri = new URI((String) security.getResource(SEC_SERVER_URI).getValue());
                    if (inetAddress.equals(new InetSocketAddress(uri.getHost(), uri.getPort()))) {
                        byte[] pskIdentity = (byte[]) security.getResource(SEC_PUBKEY_IDENTITY).getValue();
                        return new String(pskIdentity);
                    }
                } catch (URISyntaxException e) {
                    LOG.error(
                            String.format("Invalid URI %s", (String) security.getResource(SEC_SERVER_URI).getValue()),
                            e);
                }
            }
        }
        return null;
    }

}