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

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.leshan.client.servers.RegistrationEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CoAP {@link Resource} in charge of handling the Bootstrap Finish indication from the bootstrap server.
 */
public class BootstrapResource extends CoapResource {

    private static final Logger LOG = LoggerFactory.getLogger(BootstrapResource.class);

    private final RegistrationEngine regEngine;

    public BootstrapResource(RegistrationEngine regEngine) {
        super("bs", false);
        this.regEngine = regEngine;
    }

    @Override
    public void handlePOST(CoapExchange exchange) {
        LOG.debug("Bootstrap finish received");

        // TODO from the bootstrap server?

        if (regEngine.bootstrapping()) {

            // finish bootstrap session
            regEngine.bootstrapFinished();

        } else {
            LOG.warn("The client is not in a boostrap sequence");
        }

        exchange.respond(ResponseCode.CHANGED);
    }

}
