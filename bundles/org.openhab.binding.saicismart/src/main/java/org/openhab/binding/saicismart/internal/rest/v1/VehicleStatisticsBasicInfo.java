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

/**
 * @author Doug Culnane
 */
public class VehicleStatisticsBasicInfo extends JsonResponseMessage {

    VehicleStatisticsBasicInfoData data;

    public VehicleStatisticsBasicInfo() {
    }

    public VehicleStatisticsBasicInfoData getData() {
        return data;
    }

    public void setData(VehicleStatisticsBasicInfoData data) {
        this.data = data;
    }

    public class VehicleStatisticsBasicInfoData {

        String vin;
        Long initialDate;

        public VehicleStatisticsBasicInfoData() {
        }

        public String getVin() {
            return vin;
        }

        public void setVin(String vin) {
            this.vin = vin;
        }

        public Long getInitialDate() {
            return initialDate;
        }

        public void setInitialDate(Long initialDate) {
            this.initialDate = initialDate;
        }
    }
}
