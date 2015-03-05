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

import java.util.List;

import org.eclipse.californium.core.CoapResource;
import org.eclipse.californium.core.coap.CoAP.ResponseCode;
import org.eclipse.californium.core.coap.LinkFormat;
import org.eclipse.californium.core.coap.MediaTypeRegistry;
import org.eclipse.californium.core.observe.ObserveRelation;
import org.eclipse.californium.core.observe.ObserveRelationContainer;
import org.eclipse.californium.core.server.resources.CoapExchange;
import org.eclipse.californium.core.server.resources.Resource;
import org.eclipse.leshan.ObserveSpec;
import org.eclipse.leshan.client.resource.LinkFormattable;
import org.eclipse.leshan.client.resource.LwM2mObjectEnabler;
import org.eclipse.leshan.client.resource.NotifySender;
import org.eclipse.leshan.client.util.LinkFormatUtils;
import org.eclipse.leshan.client.util.ObserveSpecParser;
import org.eclipse.leshan.core.node.LwM2mNode;
import org.eclipse.leshan.core.node.LwM2mObjectInstance;
import org.eclipse.leshan.core.node.LwM2mPath;
import org.eclipse.leshan.core.node.LwM2mResource;
import org.eclipse.leshan.core.node.Value.DataType;
import org.eclipse.leshan.core.node.codec.InvalidValueException;
import org.eclipse.leshan.core.node.codec.LwM2mNodeDecoder;
import org.eclipse.leshan.core.node.codec.LwM2mNodeEncoder;
import org.eclipse.leshan.core.objectspec.ResourceSpec;
import org.eclipse.leshan.core.objectspec.ResourceSpec.Type;
import org.eclipse.leshan.core.objectspec.Resources;
import org.eclipse.leshan.core.request.ContentFormat;
import org.eclipse.leshan.core.request.CreateRequest;
import org.eclipse.leshan.core.request.DeleteRequest;
import org.eclipse.leshan.core.request.DiscoverRequest;
import org.eclipse.leshan.core.request.ExecuteRequest;
import org.eclipse.leshan.core.request.ObserveRequest;
import org.eclipse.leshan.core.request.ReadRequest;
import org.eclipse.leshan.core.request.WriteAttributesRequest;
import org.eclipse.leshan.core.request.WriteRequest;
import org.eclipse.leshan.core.response.CreateResponse;
import org.eclipse.leshan.core.response.DiscoverResponse;
import org.eclipse.leshan.core.response.LwM2mResponse;
import org.eclipse.leshan.core.response.ValueResponse;
import org.eclipse.leshan.util.Validate;

public class ObjectResource extends CoapResource implements LinkFormattable, NotifySender {

    private LwM2mObjectEnabler nodeEnabler;

    public ObjectResource(LwM2mObjectEnabler nodeEnabler) {
        super(Integer.toString(nodeEnabler.getId()));
        this.nodeEnabler = nodeEnabler;
        this.nodeEnabler.setNotifySender(this);
        setObservable(true);
    }

    @Override
    public void handleGET(CoapExchange exchange) {
        String URI = "/" + exchange.getRequestOptions().getUriPathString();

        // Manage Discover Request
        if (exchange.getRequestOptions().getAccept() == MediaTypeRegistry.APPLICATION_LINK_FORMAT) {
            DiscoverResponse response = nodeEnabler.discover(new DiscoverRequest(URI));
            exchange.respond(fromLwM2mCode(response.getCode()), LinkFormatUtils.payloadize(response.getObjectLinks()),
                    MediaTypeRegistry.APPLICATION_LINK_FORMAT);
        }
        // Manage Observe Request
        else if (exchange.getRequestOptions().hasObserve()) {
            ValueResponse response = nodeEnabler.observe(new ObserveRequest(URI));
            if (response.getCode() == org.eclipse.leshan.ResponseCode.CONTENT) {
                LwM2mPath path = new LwM2mPath(URI);
                LwM2mNode content = response.getContent();
                ContentFormat contentFormat = getContentFormat(path, content);
                exchange.respond(ResponseCode.CONTENT, LwM2mNodeEncoder.encode(content, contentFormat, path));
                return;
            } else {
                exchange.respond(fromLwM2mCode(response.getCode()));
                return;
            }
        }
        // Manage Read Request
        else {
            ValueResponse response = nodeEnabler.read(new ReadRequest(URI));
            if (response.getCode() == org.eclipse.leshan.ResponseCode.CONTENT) {
                LwM2mPath path = new LwM2mPath(URI);
                LwM2mNode content = response.getContent();
                ContentFormat contentFormat = getContentFormat(path, content);
                exchange.respond(ResponseCode.CONTENT, LwM2mNodeEncoder.encode(content, contentFormat, path));
                return;
            } else {
                exchange.respond(fromLwM2mCode(response.getCode()));
                return;
            }
        }
    }

    @Override
    public void handlePUT(final CoapExchange coapExchange) {
        String URI = "/" + coapExchange.getRequestOptions().getUriPathString();

        // get Observe Spec
        ObserveSpec spec = null;
        if (coapExchange.advanced().getRequest().getOptions().getURIQueryCount() != 0) {
            final List<String> uriQueries = coapExchange.advanced().getRequest().getOptions().getUriQuery();
            spec = ObserveSpecParser.parse(uriQueries);
        }

        // Manage Write Attributes Request
        if (spec != null) {
            LwM2mResponse response = nodeEnabler.writeAttributes(new WriteAttributesRequest(URI, spec));
            coapExchange.respond(fromLwM2mCode(response.getCode()));
            return;
        }
        // Manage Write Request (replace)
        else {
            LwM2mPath path = new LwM2mPath(URI);
            ContentFormat contentFormat = ContentFormat.fromCode(coapExchange.getRequestOptions().getContentFormat());
            LwM2mNode lwM2mNode;
            try {
                lwM2mNode = LwM2mNodeDecoder.decode(coapExchange.getRequestPayload(), contentFormat, path);
                LwM2mResponse response = nodeEnabler.write(new WriteRequest(URI, lwM2mNode, contentFormat, true));
                coapExchange.respond(fromLwM2mCode(response.getCode()));
                return;
            } catch (InvalidValueException e) {
                coapExchange.respond(ResponseCode.INTERNAL_SERVER_ERROR);
                return;
            }

        }
    }

    @Override
    public void handlePOST(final CoapExchange exchange) {
        String URI = "/" + exchange.getRequestOptions().getUriPathString();
        LwM2mPath path = new LwM2mPath(URI);

        // Manage Execute Request
        if (path.isResource()) {
            LwM2mResponse response = nodeEnabler.execute(new ExecuteRequest(URI));
            exchange.respond(fromLwM2mCode(response.getCode()));
            return;
        }

        // Manage Create Request
        try {
            ContentFormat contentFormat = ContentFormat.fromCode(exchange.getRequestOptions().getContentFormat());
            LwM2mNode lwM2mNode = LwM2mNodeDecoder.decode(exchange.getRequestPayload(), contentFormat, path);
            if (!(lwM2mNode instanceof LwM2mObjectInstance)) {
                exchange.respond(ResponseCode.BAD_REQUEST);
                return;
            }
            LwM2mResource[] resources = ((LwM2mObjectInstance) lwM2mNode).getResources().values()
                    .toArray(new LwM2mResource[0]);
            CreateResponse response = nodeEnabler.create(new CreateRequest(URI, resources, contentFormat));
            if (response.getCode() == org.eclipse.leshan.ResponseCode.CREATED) {
                exchange.setLocationPath(response.getLocation());
                exchange.respond(fromLwM2mCode(response.getCode()));
                return;
            } else {
                exchange.respond(fromLwM2mCode(response.getCode()));
                return;
            }
        } catch (InvalidValueException e) {
            exchange.respond(ResponseCode.BAD_REQUEST);
            return;
        }
    }

    @Override
    public void handleDELETE(final CoapExchange coapExchange) {
        // Manage Delete Request
        String URI = "/" + coapExchange.getRequestOptions().getUriPathString();
        LwM2mResponse response = nodeEnabler.delete(new DeleteRequest(URI));
        coapExchange.respond(fromLwM2mCode(response.getCode()));
    }

    @Override
    public void sendNotify(String URI) {
        notifyObserverRelationsForResource(URI);
    }

    /*
     * Override the default behavior so that requests to sub resources (typically /ObjectId/*) are handled by this
     * resource. TODO: not sure this is the best way to do that. (we do the same thing in RegisterResource)
     */
    @Override
    public Resource getChild(String name) {
        return this;
    }

    // TODO this code should be factorize with leshan-server-cf
    public static ResponseCode fromLwM2mCode(final org.eclipse.leshan.ResponseCode code) {
        Validate.notNull(code);

        switch (code) {
        case CREATED:
            return ResponseCode.CREATED;
        case DELETED:
            return ResponseCode.DELETED;
        case CHANGED:
            return ResponseCode.CHANGED;
        case CONTENT:
            return ResponseCode.CONTENT;
        case BAD_REQUEST:
            return ResponseCode.BAD_REQUEST;
        case UNAUTHORIZED:
            return ResponseCode.UNAUTHORIZED;
        case NOT_FOUND:
            return ResponseCode.NOT_FOUND;
        case METHOD_NOT_ALLOWED:
            return ResponseCode.METHOD_NOT_ALLOWED;
        default:
            // TODO how can we manage CONFLICT code ...
            // } else if (code == leshan.ResponseCode.CONFLICT) {
            // //return 137;
            // } else {
            throw new IllegalArgumentException("Invalid CoAP code for LWM2M response: " + code);
        }
    }

    // TODO this code should not be here, this is not its responsibility to do that.
    private ContentFormat getContentFormat(LwM2mPath path, LwM2mNode content) {
        ContentFormat format;
        // TODO this code is duplicate from write request and should be reused.
        // guess content type
        // Manage default format
        // Use text for single resource ...
        if (path.isResource()) {
            // Use resource description to guess
            final ResourceSpec description = Resources.getResourceSpec(path.getObjectId(), path.getResourceId());
            if (description != null) {
                if (description.multiple) {
                    format = ContentFormat.TLV;
                } else {
                    format = description.type == Type.OPAQUE ? ContentFormat.OPAQUE : ContentFormat.TEXT;
                }
            }
            // If no object description available, use 'node' to guess
            else {
                LwM2mResource resourceNode = ((LwM2mResource) content);
                if (resourceNode.isMultiInstances()) {
                    format = ContentFormat.TLV;
                } else {
                    format = resourceNode.getValue().type == DataType.OPAQUE ? ContentFormat.OPAQUE
                            : ContentFormat.TEXT;
                }
            }

        } // ... and TLV for other ones.
        else {
            format = ContentFormat.TLV;
        }
        return format;
    }

    // TODO this code not be here, this is not its responsibility to do that.
    @Override
    public String asLinkFormat() {
        final StringBuilder linkFormat = LinkFormat.serializeResource(this).append(
                LinkFormat.serializeAttributes(getAttributes()));
        linkFormat.deleteCharAt(linkFormat.length() - 1);
        return linkFormat.toString();
    }

    /* TODO: Observe HACK we should see if this could not be integrated in californium */
    private ObserveRelationContainer observeRelations = new ObserveRelationContainer();

    @Override
    public void addObserveRelation(ObserveRelation relation) {
        super.addObserveRelation(relation);
        observeRelations.add(relation);
    }

    @Override
    public void removeObserveRelation(ObserveRelation relation) {
        super.removeObserveRelation(relation);
        observeRelations.remove(relation);
    }

    protected void notifyObserverRelationsForResource(String URI) {
        for (ObserveRelation relation : observeRelations) {
            if (relation.getExchange().getRequest().getOptions().getUriPathString().equals(URI)) {
                relation.notifyObservers();
            }
        }
    }
}
