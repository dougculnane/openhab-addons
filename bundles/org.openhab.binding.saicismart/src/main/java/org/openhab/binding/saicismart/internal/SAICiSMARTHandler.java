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

import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.API_ENDPOINT_V21;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_FORCE_REFRESH;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_LAST_ACTIVITY;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_SWITCH_AC;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.saicismart.internal.rest.v1.MessageNotificationList;
import org.openhab.binding.saicismart.internal.rest.v1.MessageNotificationList.Notification;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.GsonBuilder;

import net.heberling.ismart.asn1.v1_1.entity.Message;
import net.heberling.ismart.asn1.v2_1.MessageCoder;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVCReq;
import net.heberling.ismart.asn1.v2_1.entity.OTA_RVCStatus25857;
import net.heberling.ismart.asn1.v2_1.entity.RvcReqParam;

/**
 * The {@link SAICiSMARTHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Markus Heberling - Initial contribution
 */
@NonNullByDefault
public class SAICiSMARTHandler extends BaseThingHandler {

    private final Logger logger = LoggerFactory.getLogger(SAICiSMARTHandler.class);

    @Nullable
    SAICiSMARTVehicleConfiguration config;
    private @Nullable Future<?> pollingJob;
    private Instant lastAlarmMessage;
    private Instant lastCarActivity;

    @Nullable
    private Instant lastCarStart;

    /**
     * If the binding is initialized, treat the car as active (lastCarActivity = now) to get some first data.
     * 
     * @param httpClientFactory
     * @param thing
     */
    public SAICiSMARTHandler(Thing thing) {
        super(thing);
        lastAlarmMessage = Instant.now();
        lastCarActivity = Instant.now();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (channelUID.getId().equals(SAICiSMARTBindingConstants.CHANNEL_FORCE_REFRESH) && command == OnOffType.ON) {
            // reset channel to off
            updateState(CHANNEL_FORCE_REFRESH, OnOffType.from(false));
            // update internal activity date, to query the car for about a minute
            notifyCarActivity(
                    Instant.now().minus(SAICiSMARTBindingConstants.POLLING_ACTIVE_MINS - 1, ChronoUnit.MINUTES), true);
        } else if (channelUID.getId().equals(CHANNEL_SWITCH_AC) && command == OnOffType.ON) {
            // reset channel to off
            updateState(CHANNEL_SWITCH_AC, OnOffType.ON);
            // enable air conditioning
            try {
                sendACCommand((byte) 5, (byte) 8);
            } catch (URISyntaxException | ExecutionException | TimeoutException | InterruptedException e) {
                logger.warn("A/C On Command failed", e);
            }
        } else if (channelUID.getId().equals(CHANNEL_SWITCH_AC) && command == OnOffType.OFF) {
            // reset channel to off
            updateState(CHANNEL_SWITCH_AC, OnOffType.OFF);
            // disable air conditioning
            try {
                sendACCommand((byte) 0, (byte) 0);
            } catch (URISyntaxException | ExecutionException | TimeoutException | InterruptedException e) {
                logger.warn("A/C Off Command failed", e);
            }
        } else if (channelUID.getId().equals(CHANNEL_LAST_ACTIVITY)
                && command instanceof DateTimeType commnadAsDateTimeType) {
            // update internal activity date from external date
            notifyCarActivity(commnadAsDateTimeType.getInstant(), true);
        }
    }

    protected @Nullable SAICiSMARTBridgeHandler getBridgeHandler() {
        return (SAICiSMARTBridgeHandler) super.getBridge().getHandler();
    }

    @Override
    public void initialize() {
        config = getConfigAs(SAICiSMARTVehicleConfiguration.class);

        ThingBuilder thingBuilder = editThing();
        updateThing(thingBuilder.build());

        updateStatus(ThingStatus.UNKNOWN);

        // Validate configuration
        if (this.config.vin.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                    "@text/thing-type.config.saicismart.vehicle.vin.required");
            return;
        }

        // just started, make sure we start querying
        notifyCarActivity(Instant.now(), true);
        pollingJob = scheduler.scheduleWithFixedDelay(this::updateStatus, 2,
                SAICiSMARTBindingConstants.REFRESH_INTERVAL, TimeUnit.SECONDS);
    }
    SimpleDateFormat messageTImesampFormmater = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    private void updateStatus() {
        try {
            MessageNotificationList messageList = getBridgeHandler().getMessages();
            if (messageList != null && messageList.getData().getNotifications() != null) {
                boolean setLastMessage = false;
                boolean setLastStart = false;
                for (Notification notification : messageList.getData().getNotifications()) {
                    if (notification.getVin() != null && notification.getVin().equals(config.vin)) {
                        if (!setLastMessage) {

                            Date messageTime = messageTImesampFormmater.parse(notification.getMessageTime());
                            updateState(SAICiSMARTBindingConstants.CHANNEL_ALARM_MESSAGE_DATE,
                                    new DateTimeType(messageTime.toInstant()));
                            updateState(SAICiSMARTBindingConstants.CHANNEL_ALARM_MESSAGE_CONTENT,
                                    new StringType(notification.getContent()));
                            setLastMessage = true;
                        }
                        if (!setLastStart && notification.getMessageType().equals("323")
                                && notification.getVin().equals(config.vin)) {
                          
                            Instant mesageDateTime = messageTImesampFormmater.parse(notification.getMessageTime()).toInstant();
                            
                            // If the start is new force now to be the activity time..
                            if (lastCarStart != null && lastCarStart.isBefore(mesageDateTime)) {
                                notifyCarActivity(
                                        Instant.now().minus(
                                                SAICiSMARTBindingConstants.POLLING_ACTIVE_MINS - 1, ChronoUnit.MINUTES),
                                        true);
                                lastCarStart = mesageDateTime;
                            } else {
                                notifyCarActivity(mesageDateTime, false);
                            }
                            setLastStart = true;
                        }
                    }
                    if (setLastMessage && setLastStart) {
                        break;
                    }
                }
            }

            if (lastCarActivity.isAfter(
                    Instant.now().minus(SAICiSMARTBindingConstants.POLLING_ACTIVE_MINS, ChronoUnit.MINUTES))) {
            	if (this.getBridgeHandler().getUid() != null && this.getBridgeHandler().getToken() != null) {
                    try {
                        new VehicleStateUpdater(this).call();

                        if (config.abrpUserToken != null && config.abrpUserToken.length() > 0) {
                            // String execute = ABRP.updateAbrp(ABRP_API_KEY, config.abrpUserToken,
                            // otaRvmVehicleStatusResp25857, otaChrgMangDataResp);

                            // logger.debug("ABRP: {}", execute);
                        }
                    } catch (Exception e) {
                        logger.warn("Could not refresh car data. {}", e.getMessage());
                        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                                "@text/addon.saicismart.error.refresh.car.data");
                    }
                }
            } else {
            	updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.DISABLED);
            }
        } catch (Exception e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "@text/addon.saicismart.error.refresh.car.data");
            logger.error("Update Status Error: {}", e.getMessage(), e);
        }
    }

    private void sendACCommand(byte command, byte temperature)
            throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException {
        MessageCoder<OTA_RVCReq> otaRvcReqMessageCoder = new MessageCoder<>(OTA_RVCReq.class);

        // we send a command end expect the car to wake up
        notifyCarActivity(Instant.now(), false);

        OTA_RVCReq req = new OTA_RVCReq();
        req.setRvcReqType(new byte[] { 6 });
        List<RvcReqParam> params = new ArrayList<>();
        req.setRvcParams(params);
        RvcReqParam param = new RvcReqParam();
        param.setParamId(19);
        param.setParamValue(new byte[] { command });
        params.add(param);
        param = new RvcReqParam();
        param.setParamId(20);
        param.setParamValue(new byte[] { temperature });
        params.add(param);
        param = new RvcReqParam();
        param.setParamId(255);
        param.setParamValue(new byte[] { 0 });
        params.add(param);

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVCReq> enableACRequest = otaRvcReqMessageCoder.initializeMessage(
                getBridgeHandler().getUid(), getBridgeHandler().getToken(), config.vin, "510", 25857, 1, req);

        String enableACRequestMessage = otaRvcReqMessageCoder.encodeRequest(enableACRequest);

        String enableACResponseMessage = getBridgeHandler().sendRequest(enableACRequestMessage, API_ENDPOINT_V21);

        net.heberling.ismart.asn1.v2_1.Message<OTA_RVCStatus25857> enableACResponse = new net.heberling.ismart.asn1.v2_1.MessageCoder<>(
                OTA_RVCStatus25857.class).decodeResponse(enableACResponseMessage);

        // ... use that to request the data again, until we have it
        while (enableACResponse.getApplicationData() == null) {
            if (enableACResponse.getBody().isErrorMessagePresent()) {
                if (enableACResponse.getBody().getResult() == 2) {
                    getBridgeHandler().relogin();
                }
                throw new TimeoutException(new String(enableACResponse.getBody().getErrorMessage()));
            }

            if (enableACResponse.getBody().getResult() == 0) {
                // we get an eventId back...
                enableACRequest.getBody().setEventID(enableACResponse.getBody().getEventID());
            } else {
                // try a fresh eventId
                enableACRequest.getBody().setEventID(0);
            }

            enableACRequestMessage = otaRvcReqMessageCoder.encodeRequest(enableACRequest);

            enableACResponseMessage = getBridgeHandler().sendRequest(enableACRequestMessage, API_ENDPOINT_V21);

            enableACResponse = new net.heberling.ismart.asn1.v2_1.MessageCoder<>(OTA_RVCStatus25857.class)
                    .decodeResponse(enableACResponseMessage);
        }

        logger.trace("Got A/C message: {}", new GsonBuilder().setPrettyPrinting().create().toJson(enableACResponse));
    }


    public void notifyCarActivity(Instant timeStamp, boolean force) {
        // if the car activity changed, notify the channel
        if (force || lastCarActivity.isBefore(timeStamp)) {
            lastCarActivity = timeStamp;
            updateState(CHANNEL_LAST_ACTIVITY, new DateTimeType(lastCarActivity));
        }
    }

    @Override
    public void dispose() {
        Future<?> job = pollingJob;
        if (job != null) {
            job.cancel(true);
            pollingJob = null;
        }
    }

    @Override
    public void updateState(String channelID, State state) {
        super.updateState(channelID, state);
    }

    @Override
    public void updateStatus(ThingStatus status) {
        super.updateStatus(status);
    }

    public void handleMessage(Message message) {
        Instant time = Instant.ofEpochSecond(message.getMessageTime().getSeconds());

        if (time.isAfter(lastAlarmMessage)) {
            lastAlarmMessage = time;
            updateState(SAICiSMARTBindingConstants.CHANNEL_ALARM_MESSAGE_CONTENT,
                    new StringType(new String(message.getContent(), StandardCharsets.UTF_8)));
            updateState(SAICiSMARTBindingConstants.CHANNEL_ALARM_MESSAGE_DATE, new DateTimeType(time));
        }

        notifyCarActivity(time, false);
    }
}
