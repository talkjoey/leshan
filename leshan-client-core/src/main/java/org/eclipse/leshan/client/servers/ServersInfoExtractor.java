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

import static org.eclipse.leshan.client.util.LwM2mId.SECURITY_ID;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_BOOTSTRAP;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_SERVER_ID;
import static org.eclipse.leshan.client.util.LwM2mId.SEC_SERVER_URI;
import static org.eclipse.leshan.client.util.LwM2mId.SERVER_ID;
import static org.eclipse.leshan.client.util.LwM2mId.SRV_BINDING;
import static org.eclipse.leshan.client.util.LwM2mId.SRV_LIFETIME;
import static org.eclipse.leshan.client.util.LwM2mId.SRV_SERVER_ID;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;

import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.core.node.LwM2mObject;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.request.BindingMode;
import org.eclipse.leshan.core.request.ReadRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ServersInfoExtractor {
    private static final Logger LOG = LoggerFactory.getLogger(ServersInfoExtractor.class);

    public static ServersInfo getInfo(Map<Integer, LwM2mObjectEnabler> objectEnablers) {
        LwM2mObjectEnabler securityEnabler = objectEnablers.get(SECURITY_ID);
        LwM2mObjectEnabler serverEnabler = objectEnablers.get(SERVER_ID);

        if (securityEnabler == null || serverEnabler == null)
            return null;

        ServersInfo infos = new ServersInfo();
        LwM2mObject securities = (LwM2mObject) securityEnabler.read(new ReadRequest(SECURITY_ID), true).getContent();
        LwM2mObject servers = (LwM2mObject) serverEnabler.read(new ReadRequest(SERVER_ID), true).getContent();

        for (LwM2mObjectInstance security : securities.getInstances().values()) {
            try {
                if ((boolean) security.getResource(SEC_BOOTSTRAP).getValue()) {
                    // create bootstrap info
                    ServerInfo info = new ServerInfo();
                    info.serverUri = new URI((String) security.getResource(SEC_SERVER_URI).getValue());

                    infos.bootstraps.add(info);
                } else {
                    // create device management info
                    DmServerInfo info = new DmServerInfo();
                    info.serverUri = new URI((String) security.getResource(SEC_SERVER_URI).getValue());
                    long serverId = (long) security.getResource(SEC_SERVER_ID).getValue();

                    // search corresponding device management server
                    for (LwM2mObjectInstance server : servers.getInstances().values()) {
                        if (serverId == (Long) server.getResource(SRV_SERVER_ID).getValue()) {
                            info.lifetime = (long) server.getResource(SRV_LIFETIME).getValue();
                            // TODO check supported binding (from resource /3/0/16)
                            info.binding = BindingMode.valueOf((String) server.getResource(SRV_BINDING).getValue());
                            break;
                        }
                    }

                    infos.deviceMangements.add(info);
                }
            } catch (URISyntaxException e) {
                LOG.error(String.format("Invalid URI %s", (String) security.getResource(SEC_SERVER_URI).getValue()), e);
            }
        }
        return infos;
    }
}
