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

import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_CHARGING;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_LAST_CHARGE_STATE_UPDATE;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_ODOMETER;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_PLUGGED_IN;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_RANGE_ELECTRIC;
import static org.openhab.binding.saicismart.internal.SAICiSMARTBindingConstants.CHANNEL_SOC;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.thing.ThingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.heberling.ismart.java.rest.SaicApiClient;
import net.heberling.ismart.java.rest.api.v1.VechicleChargingMgmtData;
import net.heberling.ismart.java.rest.api.v1.VechicleChargingMgmtData.ChrgMgmtData;
import net.heberling.ismart.java.rest.api.v1.VechicleChargingMgmtData.RvsChargeStatus;
import net.heberling.ismart.java.rest.api.v1.VehicleStatus;
import net.heberling.ismart.java.rest.exceptions.VehicleStatusAPIException;

/**
 * @author Markus Heberling - Initial contribution
 * @author Doug Culnane - SAIC REST API
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

                Integer gunState = rvsChargeStatus.getChargingGunState();
                boolean pluggedIn = gunState != null && gunState == 1;
                saiCiSMARTHandler.updateState(CHANNEL_PLUGGED_IN, OnOffType.from(pluggedIn));
            }

            saiCiSMARTHandler.notifyCarActivity(Instant.now(), true);
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
}
