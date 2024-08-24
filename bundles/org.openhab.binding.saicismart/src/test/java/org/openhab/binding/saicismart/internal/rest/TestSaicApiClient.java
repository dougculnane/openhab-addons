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
package org.openhab.binding.saicismart.internal.rest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.Test;
import org.openhab.binding.saicismart.internal.rest.v1.OauthToken;
import org.openhab.binding.saicismart.internal.rest.v1.VehicleList;

/**
 * @author Doug Culnane
 */
public class TestSaicApiClient {

    @Test
    public void testClient() throws Exception {

        String username = System.getenv("SAIC_USERNAME");
        String password = System.getenv("SAIC_PASSWORD");
        assertNotNull(username, "Set the environment variable: SAIC_USERNAME");
        assertNotNull(password, "Set the environment variable: SAIC_PASSWORD");

        HttpClient httpClient = new HttpClient(new HttpClientTransportOverHTTP(), new SslContextFactory(true));
        httpClient.start();

        SaicApiClient client = new SaicApiClient(httpClient);
        OauthToken oauthToken = client.getOauthToken(username, password);

        String token = oauthToken.getData().getAccess_token();
        VehicleList vehicleList = client.getVehicleList(token);
        String vin = vehicleList.getData().getVinList()[0].getVin();

        assertTrue(client.getVehicleStatus(token, vin).isSuccess());
        assertTrue(client.getVehicleChargingMgmtData(token, vin).isSuccess());
        assertTrue(client.getVehicleStatisticsBasicInfo(token, vin).isSuccess());
        assertTrue(client.getVehicleChargingMgmtData(token, vin).isSuccess());

        // Failing
        // assertTrue(client.getVehicleCcInfo(token, vin).isSuccess());
        // assertTrue(client.getVehicleLocation(token, vin).isSuccess());
    }
}
