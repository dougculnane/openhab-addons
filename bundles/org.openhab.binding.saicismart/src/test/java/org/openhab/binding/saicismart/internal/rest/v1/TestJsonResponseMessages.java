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
package org.openhab.binding.saicismart.internal.rest.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

/**
 * @author Doug Culnane
 */
public class TestJsonResponseMessages {

    @Test
    void testOauthToken() throws JsonSyntaxException, IOException {

        Gson gson = new Gson();
        OauthToken oathToken = gson.fromJson(getResourceFileAsString("rest_v1/oauth_token_0.json"), OauthToken.class);

        assertTrue(oathToken.isSuccess());
        assertEquals("[0] success", oathToken.toString());
        assertEquals("ABC123", oathToken.getData().getAccess_token());
    }

    @Test
    void testVehicleList() throws JsonSyntaxException, IOException {

        Gson gson = new Gson();
        VehicleList vinList = gson.fromJson(getResourceFileAsString("rest_v1/vehicle_list_0.json"), VehicleList.class);

        assertTrue(vinList.isSuccess());
        assertEquals("[0] success", vinList.toString());
        assertEquals(1, vinList.getData().getVinList().length);
        assertEquals("ABCD1234567890123", vinList.getData().getVinList()[0].getVin());
        assertEquals("MG", vinList.getData().getVinList()[0].getBrandName());
        assertEquals("MG5 Electric", vinList.getData().getVinList()[0].getModelName());
    }

    static String getResourceFileAsString(String fileName) throws IOException {
        ClassLoader classLoader = ClassLoader.getSystemClassLoader();
        try (InputStream is = classLoader.getResourceAsStream(fileName)) {
            if (is == null)
                return null;
            try (InputStreamReader isr = new InputStreamReader(is); BufferedReader reader = new BufferedReader(isr)) {
                return reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }
        }
    }
}
