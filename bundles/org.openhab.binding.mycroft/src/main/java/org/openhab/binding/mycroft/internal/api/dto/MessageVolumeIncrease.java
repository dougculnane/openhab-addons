/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
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
package org.openhab.binding.mycroft.internal.api.dto;

import org.openhab.binding.mycroft.internal.api.MessageType;

import com.google.gson.annotations.SerializedName;

/**
 * This message asks Mycroft to increase the volume by 10%
 *
 * @author Gwendal Roulleau - Initial contribution
 */
public class MessageVolumeIncrease extends BaseMessage {

    public Data data = new Data();

    public MessageVolumeIncrease() {
        this.type = MessageType.mycroft_volume_increase;
    }

    public static class Data {
        @SerializedName("play_sound")
        public Boolean playSound = true;
    }
}
