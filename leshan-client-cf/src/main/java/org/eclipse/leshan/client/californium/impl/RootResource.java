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

import java.util.ArrayList;
import java.util.List;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.servers.RegistrationEngine;
import org.eclipse.leshan.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link CoapResource} resource in charge of handling Bootstrap Delete requests targeting the "/" URI.
 */
public class RootResource extends CoapResource {

    private static final Logger LOG = LoggerFactory.getLogger(RootResource.class);

    private final List<LwM2mObjectEnabler> enablers;
    private final RegistrationEngine regEngine;

    public RootResource(final List<LwM2mObjectEnabler> objectEnablers, RegistrationEngine regEngine) {
        super("", false);
        this.enablers = new ArrayList<>(objectEnablers);
        this.regEngine = regEngine;
    }

    @Override
    public void handleDELETE(CoapExchange exchange) {
        if (StringUtils.isEmpty(exchange.getRequestOptions().getUriPathString())) {
            exchange.respond(ResponseCode.METHOD_NOT_ALLOWED);
            return;
        }

        LOG.debug("Bootstrap delete request on '/'");

        // TODO from the bootstrap server?

        if (regEngine.bootstrapping()) {

            for (LwM2mObjectEnabler enabler : enablers) {
                // TODO delete
            }

        } else {
            LOG.warn("Bootstrap delete outside a bootstrap session");
        }

        exchange.respond(ResponseCode.DELETED);
    }

}
