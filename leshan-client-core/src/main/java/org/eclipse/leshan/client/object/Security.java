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
package org.eclipse.leshan.client.object;

import org.eclipse.leshan.client.resource.BaseInstanceEnabler;
import org.eclipse.leshan.client.resource.LwM2mInstanceEnabler;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.response.ExecuteResponse;
import org.eclipse.leshan.core.response.ReadResponse;
import org.eclipse.leshan.core.response.WriteResponse;

/**
 * A simple {@link LwM2mInstanceEnabler} for the Security (0) object.
 */
public class Security extends BaseInstanceEnabler {

    private String serverUri; /* coaps://host:port */
    private boolean bootstrapServer;
    // private SecurityMode securityMode;
    private int securityMode;
    private byte[] publicKeyOrIdentity;
    private byte[] serverPublicKey;
    private byte[] secretKey;

    private Integer shortServerId;

    public Security(String serverUri, boolean bootstrapServer, int securityMode, byte[] publicKeyOrIdentity,
            byte[] serverPublicKey, byte[] secretKey, Integer shortServerId) {
        this.serverUri = serverUri;
        this.bootstrapServer = bootstrapServer;
        this.securityMode = securityMode;
        this.publicKeyOrIdentity = publicKeyOrIdentity;
        this.serverPublicKey = serverPublicKey;
        this.secretKey = secretKey;
        this.shortServerId = shortServerId;
    }

    /**
     * Returns a new security instance (NoSec) for a device management server.
     */
    public static Security noSec(String serverUri, int shortServerId) {
        return new Security(serverUri, false, 3, new byte[0], new byte[0], new byte[0], shortServerId);
    }

    /**
     * Returns a new security instance (PSK) for a device management server.
     */
    public static Security psk(String serverUri, int shortServerId, byte[] pskIdentity, byte[] privateKey) {
        return new Security(serverUri, false, 0, pskIdentity.clone(), new byte[0], privateKey.clone(), shortServerId);
    }

    /**
     * Returns a new security instance (RPK) for a device management server.
     */
    public static Security rpk(String serverUri, int shortServerId, byte[] clientPublicKey, byte[] clientPrivateKey,
            byte[] serverPublicKey) {
        return new Security(serverUri, false, 1, clientPublicKey.clone(), serverPublicKey.clone(),
                clientPrivateKey.clone(), shortServerId);
    }

    @Override
    public WriteResponse write(int resourceid, LwM2mResource value) {
        // restricted to BS server?

        return super.write(resourceid, value);
    }

    @Override
    public ReadResponse read(int resourceid) {
        // only accessible for internal read?

        switch (resourceid) {
        case 0: // server uri
            return ReadResponse.success(resourceid, serverUri);

        case 1: // is bootstrap server?
            return ReadResponse.success(resourceid, bootstrapServer);

        case 2: // security mode
            return ReadResponse.success(resourceid, securityMode);

        case 3: // public key or identity
            return ReadResponse.success(resourceid, publicKeyOrIdentity);

        case 4: // server public key
            return ReadResponse.success(resourceid, serverPublicKey);

        case 5: // secret key
            return ReadResponse.success(resourceid, secretKey);

        case 10: // short server id
            return ReadResponse.success(resourceid, shortServerId);

        default:
            return super.read(resourceid);
        }
    }

    @Override
    public ExecuteResponse execute(int resourceid, String params) {
        return super.execute(resourceid, params);
    }

}
