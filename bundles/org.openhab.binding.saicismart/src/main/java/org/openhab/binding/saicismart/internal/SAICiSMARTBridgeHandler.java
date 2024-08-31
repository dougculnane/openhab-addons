/**
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.saicismart.internal;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.xml.bind.DatatypeConverter;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.openhab.binding.saicismart.internal.rest.SaicApiClient;
import org.openhab.binding.saicismart.internal.rest.v1.MessageNotificationList;
import org.openhab.binding.saicismart.internal.rest.v1.OauthToken;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleList;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleList.VinListItem;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SAICiSMARTBridgeHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Markus Heberling - Initial contribution
 */
// @NonNullByDefault
public class SAICiSMARTBridgeHandler extends BaseBridgeHandler {

    private final Logger logger = LoggerFactory.getLogger(SAICiSMARTBridgeHandler.class);

    private @Nullable SAICiSMARTBridgeConfiguration config;

    private @Nullable String token;
    private @Nullable String uid;
    private @Nullable MessageNotificationList messageList;

    private @Nullable VinListItem[] vinList;
    private HttpClient httpClient;
    private SaicApiClient saicApiClient;

    private @Nullable Future<?> pollingJob;

    public SAICiSMARTBridgeHandler(Bridge bridge, HttpClient httpClient) {
        super(bridge);
        this.httpClient = httpClient;
        this.saicApiClient = new SaicApiClient(httpClient);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        // no commands available
    }

    @Override
    public void initialize() {
        config = getConfigAs(SAICiSMARTBridgeConfiguration.class);
        updateStatus(ThingStatus.UNKNOWN);

        // Validate configuration
        if (this.config.username.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/thing-type.config.saicismart.bridge.username.required");
            return;
        }
        if (this.config.password.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/thing-type.config.saicismart.bridge.password.required");
            return;
        }
        if (this.config.username.length() > 50) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/thing-type.config.saicismart.bridge.username.toolong");
            return;
        }
        pollingJob = scheduler.scheduleWithFixedDelay(this::updateStatus, 0,
                SAICiSMARTBindingConstants.REFRESH_INTERVAL, TimeUnit.MINUTES);
    }

    private void updateStatus() {
        if (uid == null || token == null) {
            login();
        } else {
            updateMessages();
        }
    }

    private void login() {

        try {

            // login
            OauthToken loginRespounse = saicApiClient.getOauthToken(config.username, config.password);
            if (loginRespounse.isSuccess()) {
                this.uid = loginRespounse.getData().getUser_id();
                this.token = loginRespounse.getData().getAccess_token();
                // TODO check expire time and refresh before.
            }

            // get vehicles
            VehicleList vehiclesResponse = saicApiClient.getVehicleList(token);
            if (vehiclesResponse.isSuccess()) {
                this.vinList = vehiclesResponse.getData().getVinList();
            }

            updateStatus(ThingStatus.ONLINE);
            updateMessages();

        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    private void updateMessages() {
        try {
            this.messageList = saicApiClient.getMessageNotificationList(getToken());
            updateStatus(ThingStatus.ONLINE);
        } catch (TimeoutException | InterruptedException | ExecutionException | IOException e) {
            logger.warn("Update messages error: {}", e.getMessage());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    public String hashMD5(String password) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(password.getBytes());
        byte[] digest = md.digest();
        return DatatypeConverter.printHexBinary(digest).toUpperCase();
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return Collections.singleton(VehicleDiscovery.class);
    }

    @Nullable
    public String getUid() {
        return uid;
    }

    @Nullable
    public String getToken() {
        return token;
    }

    public VinListItem[] getVinList() {
        VinListItem[] vinList = this.vinList;
        return vinList != null ? vinList : new VinListItem[0];
    }

    public String sendRequest(String request, String endpoint)
            throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException {
        return httpClient.POST(new URI(endpoint)).content(new StringContentProvider(request), "text/html").send()
                .getContentAsString();
    }

    public void relogin() {
        uid = null;
        token = null;
    }

    @Override
    public void dispose() {
        Future<?> job = pollingJob;
        if (job != null) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    public SaicApiClient getSaicApiClient() {
        return saicApiClient;
    }

    public MessageNotificationList getMessages() {
        return messageList;
    }
}
