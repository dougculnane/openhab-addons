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

import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.binding.saicismart.internal.exceptions.VehicleStatusAPIException;
import org.openhab.binding.saicismart.internal.rest.SaicApiClient;
import org.openhab.binding.saicismart.internal.rest.v1.VechicleChargingMgmtData;
import org.openhab.binding.saicismart.internal.rest.v1.VechicleChargingMgmtData.ChrgMgmtData;
import org.openhab.binding.saicismart.internal.rest.v1.VechicleChargingMgmtData.RvsChargeStatus;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleCcInfo;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleLocation;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleStatisticsBasicInfo;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleStatus;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ThingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Markus Heberling - Initial contribution
 * @author Doug Culnane - Rest API
 */
@NonNullByDefault
class VehicleStateUpdater implements Callable<VehicleStatus> {
    private final Logger logger = LoggerFactory.getLogger(VehicleStateUpdater.class);

    private final SAICiSMARTHandler saiCiSMARTHandler;
    private SaicApiClient saicApiClient;

    public VehicleStateUpdater(SAICiSMARTHandler saiCiSMARTHandler) {
        this.saiCiSMARTHandler = saiCiSMARTHandler;
        this.saicApiClient = saiCiSMARTHandler.getBridgeHandler().getSaicApiClient();
    }

    @Override
    public VehicleStatus call() throws URISyntaxException, ExecutionException, InterruptedException, TimeoutException,
            VehicleStatusAPIException, IOException {

        // get status of vehicle
        VehicleStatus statusResponse = saicApiClient.getVehicleStatus(saiCiSMARTHandler.getBridgeHandler().getToken(),
                saiCiSMARTHandler.config.vin);

        if (!statusResponse.isSuccess()) {
            final Integer resultCode = statusResponse.getCode();
            if (resultCode == 2 || resultCode == 3) {
                saiCiSMARTHandler.getBridgeHandler().relogin();
            }
            logger.warn("Vehicle status update error: {}", statusResponse);
            // throw new VehicleStatusAPIException(statusResponse);
        }

        // we get an eventId back..... use that to request the data again, until we have it
        VechicleChargingMgmtData mgmtDataResonse = saicApiClient.getVehicleChargingMgmtData(
                saiCiSMARTHandler.getBridgeHandler().getToken(), saiCiSMARTHandler.config.vin);
        if (!mgmtDataResonse.isSuccess()) {
            throw new VehicleStatusAPIException(mgmtDataResonse);
        }

        Integer chrgingState = null;

        if (mgmtDataResonse != null && mgmtDataResonse.getData() != null) {

            ChrgMgmtData chrgMgmtDat = mgmtDataResonse.getData().getChrgMgmtData();
            if (chrgMgmtDat != null) {

                chrgingState = chrgMgmtDat.getBmsChrgSts();

                // saiCiSMARTHandler.updateState(CHANNEL_ENGINE, OnOffType.from(engineRunning));

                // TODO not working.
                // saiCiSMARTHandler.updateState(CHANNEL_AUXILIARY_BATTERY_VOLTAGE, new QuantityType<>(
                // mgmtDataResonse.getData().getRvsChargeStatus().getWorkingVoltage() / 100.d, Units.VOLT));


                // Sometimes we get a value of 1023 which we should ignore.
                Integer socDsp = chrgMgmtDat.getBmsPackSOCDsp();
                if (socDsp != null && socDsp != 1023) {
                    saiCiSMARTHandler.updateState(CHANNEL_SOC, new DecimalType(socDsp / 10.d));
                    saiCiSMARTHandler.updateState(CHANNEL_LAST_CHARGE_STATE_UPDATE, new DateTimeType(Instant.now()));
                }
            }


            RvsChargeStatus rvsChargeStatus = mgmtDataResonse.getData().getRvsChargeStatus();
            if (rvsChargeStatus != null) {

                // ?? boolean engineRunning = mgmtDataResonse.getData().getRvsChargeStatus().getEngineStatus() == 1;

                Integer mileage = rvsChargeStatus.getMileage();
                if (mileage != null && mileage > 0) {
                    saiCiSMARTHandler.updateState(CHANNEL_ODOMETER,
                            new QuantityType<>(mileage / 10.d, MetricPrefix.KILO(SIUnits.METRE)));
                }

                Integer fuelRangeElec = rvsChargeStatus.getFuelRangeElec();
                if (fuelRangeElec != null && fuelRangeElec > 0) {
                    saiCiSMARTHandler.updateState(CHANNEL_RANGE_ELECTRIC,
                            new QuantityType<>(fuelRangeElec / 10.d, MetricPrefix.KILO(SIUnits.METRE)));
                }

                // TODO new plugged in state or charging state..?
                Integer gunState = rvsChargeStatus.getChargingGunState();
            }



        if (chrgingState == 1) { // TODO || acActive || engineRunning) {
            // update activity date
            saiCiSMARTHandler.notifyCarActivity(Instant.now(), true);
            // Double power = (chargingStatusResponseMessage.getApplicationData().getBmsPackCrnt() * 0.05d - 1000.0d)
            // * ((double) chargingStatusResponseMessage.getApplicationData().getBmsPackVol() * 0.25d);
            // saiCiSMARTHandler.updateState(CHANNEL_POWER, new QuantityType<>(power.intValue(), Units.WATT));

            // VehicleLocation vehicleLocationResponse = saiCiSMARTHandler.getBridgeHandler().sendRequest(
            // new VehicleLocation(),
            // URI.create("https://gateway-mg-eu.soimt.com/api.app/v1/vehicle/location?vin="
            // + HashUtils.sha256(saiCiSMARTHandler.config.vin)),
            // HttpMethod.GET, "", "application/json", saiCiSMARTHandler.getBridgeHandler().getToken(), "");

            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_TYRE_PRESSURE_FRONT_LEFT, new
            // QuantityType<>(
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getFrontLeftTyrePressure()
            // * 4 / 100.d,
            // Units.BAR));
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_TYRE_PRESSURE_FRONT_RIGHT, new
            // QuantityType<>(
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getFrontRrightTyrePressure()
            // * 4 / 100.d,
            // Units.BAR));
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_TYRE_PRESSURE_REAR_LEFT, new
            // QuantityType<>(
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRearLeftTyrePressure() * 4
            // / 100.d,
            // Units.BAR));
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_TYRE_PRESSURE_REAR_RIGHT, new
            // QuantityType<>(
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRearRightTyrePressure()
            // * 4 / 100.d,
            // Units.BAR));
            //
            // Integer interiorTemperature = chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus()
            // .getInteriorTemperature();
            // if (interiorTemperature > -128) {
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_INTERIOR_TEMPERATURE,
            // new QuantityType<>(interiorTemperature, SIUnits.CELSIUS));
            // }
            // Integer exteriorTemperature = chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus()
            // .getExteriorTemperature();
            // if (exteriorTemperature > -128) {
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_EXTERIOR_TEMPERATURE,
            // new QuantityType<>(exteriorTemperature, SIUnits.CELSIUS));
            // }
            //
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_DOOR_DRIVER,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getDriverDoor()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_DOOR_PASSENGER,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getPassengerDoor()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_DOOR_REAR_LEFT,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRearLeftDoor()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_DOOR_REAR_RIGHT,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRearRightDoor()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            //
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_WINDOW_DRIVER,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getDriverWindow()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_WINDOW_PASSENGER,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getPassengerWindow()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_WINDOW_REAR_LEFT,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRearLeftWindow()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_WINDOW_REAR_RIGHT,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRearRightWindow()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_WINDOW_SUN_ROOF,
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getSunroofStatus()
            // ? OpenClosedType.OPEN
            // : OpenClosedType.CLOSED);
            //
            // boolean acActive = chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus()
            // .getRemoteClimateStatus() > 0;
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_SWITCH_AC, OnOffType.from(acActive));
            // saiCiSMARTHandler.updateState(SAICiSMARTBindingConstants.CHANNEL_REMOTE_AC_STATUS, new DecimalType(
            // chargingStatusResponseMessage.getApplicationData().getBasicVehicleStatus().getRemoteClimateStatus()));

            boolean isCharging = chrgingState != null && chrgingState == 1;
            saiCiSMARTHandler.updateState(CHANNEL_CHARGING, OnOffType.from(isCharging));

            if (isCharging) { // TODO || acActive || engineRunning) {
                // update activity date
                saiCiSMARTHandler.notifyCarActivity(Instant.now(), true);
            }
        }

        saiCiSMARTHandler.updateStatus(ThingStatus.ONLINE);

        return statusResponse;
    }

    private void updateVehicleCcInfo() throws IOException, InterruptedException, TimeoutException, ExecutionException {
        VehicleCcInfo response = saicApiClient.getVehicleCcInfo(saiCiSMARTHandler.getBridgeHandler().getToken(),
                saiCiSMARTHandler.config.vin);
        if (!response.isSuccess()) {
            logger.warn("Vehicle CC info update error: {}", response);
            return;
        }
    }

    private void updateVehicleStatisticsBasicInfo()
            throws IOException, InterruptedException, TimeoutException, ExecutionException {
        VehicleStatisticsBasicInfo response = saicApiClient.getVehicleStatisticsBasicInfo(
                saiCiSMARTHandler.getBridgeHandler().getToken(), saiCiSMARTHandler.config.vin);
        if (!response.isSuccess()) {
            logger.warn("Vehicle statistics info update error: {}", response);
            return;
        }
    }

    private void updateVehicleLocation()
            throws IOException, InterruptedException, TimeoutException, ExecutionException {

        VehicleLocation response = saicApiClient.getVehicleLocation(saiCiSMARTHandler.getBridgeHandler().getToken(),
                saiCiSMARTHandler.config.vin);
        if (!response.isSuccess()) {
            logger.warn("Vehicle location update error: {}", response);
            return;
        }

        // saiCiSMARTHandler.updateState(CHANNEL_SPEED, new QuantityType<>(
        // chargingStatusResponseMessage.getApplicationData().getGpsPosition().getWayPoint().getSpeed() / 10.d,
        // SIUnits.KILOMETRE_PER_HOUR));
        // saiCiSMARTHandler.updateState(CHANNEL_HEADING,
        // new QuantityType<>(
        // chargingStatusResponseMessage.getApplicationData().getGpsPosition().getWayPoint().getHeading(),
        // Units.DEGREE_ANGLE));
        // saiCiSMARTHandler.updateState(CHANNEL_LOCATION,
        // new PointType(
        // new DecimalType(chargingStatusResponseMessage.getApplicationData().getGpsPosition()
        // .getWayPoint().getPosition().getLatitude() / 1000000d),
        // new DecimalType(chargingStatusResponseMessage.getApplicationData().getGpsPosition()
        // .getWayPoint().getPosition().getLongitude() / 1000000d),
        // new DecimalType(chargingStatusResponseMessage.getApplicationData().getGpsPosition()
        // .getWayPoint().getPosition().getAltitude())));

        // saiCiSMARTHandler
        // .updateState(SAICiSMARTBindingConstants.CHANNEL_LAST_POSITION_UPDATE,
        // new DateTimeType(ZonedDateTime.ofInstant(
        // Instant.ofEpochSecond(chargingStatusResponseMessage.getApplicationData()
        // .getGpsPosition().getTimestamp4Short().getSeconds()),
        // saiCiSMARTHandler.getTimeZone())));
    }
}
