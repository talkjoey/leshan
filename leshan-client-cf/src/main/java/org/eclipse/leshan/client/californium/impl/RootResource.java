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

import java.util.Map;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.servers.RegistrationEngine;
import org.eclipse.leshan.core.request.DeleteRequest;
import org.eclipse.leshan.core.request.Identity;
import org.eclipse.leshan.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CoapResource} resource in charge of handling Bootstrap Delete requests targeting the "/" URI.
 */
public class RootResource extends CoapResource {

    private static final Logger LOG = LoggerFactory.getLogger(RootResource.class);

    private Map<Integer, LwM2mObjectEnabler> enablers;
    private RegistrationEngine regEngine;

    public RootResource() {
        super("", false);
    }

    public void setEnablers(Map<Integer, LwM2mObjectEnabler> objectEnablers) {
        this.enablers = objectEnablers;
    }

    public void setRegEngine(RegistrationEngine regEngine) {
        this.regEngine = regEngine;
    }

    @Override
    public void handleDELETE(CoapExchange exchange) {
        if (!StringUtils.isEmpty(exchange.getRequestOptions().getUriPathString())) {
            exchange.respond(ResponseCode.METHOD_NOT_ALLOWED);
            return;
        }

        Identity identity = ResourceUtil.extractIdentity(exchange);
        LOG.debug("Bootstrap delete request on '/' from {}", identity.getPeerAddress());

        // only if the request is from the bootstrap server
        if (!regEngine.isBootstrapServer(identity)) {
            exchange.respond(ResponseCode.METHOD_NOT_ALLOWED);
            return;
        }

        if (regEngine.bootstrapping()) {
            // TODO do not delete boostrap server (see 5.2.5.2 Bootstrap Delete)
            for (LwM2mObjectEnabler enabler : enablers.values()) {
                for (Integer instanceId : enabler.getAvailableInstanceIds()) {
                    enabler.delete(new DeleteRequest(enabler.getId(), instanceId), identity);
                }
            }
        } else {
            LOG.warn("Bootstrap delete outside a bootstrap session");
        }

        exchange.respond(ResponseCode.DELETED);
    }
}
