/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.services.telephony.common;

/**
 * Container class for video modes.
 */
public class VideoMode {

    public final static int NONE = 0;
    public final static int RECEIVE_ONLY = 1;
    public final static int SEND_ONLY = 2;
    public final static int DUPLEX = 3;

    public static String toString(int mode) {
        switch (mode) {
            case NONE:
                return "NONE";
            case RECEIVE_ONLY:
                return "RECEIVE_ONLY";
            case SEND_ONLY:
                return "SEND_ONLY";
            case DUPLEX:
                return "DUPLEX";
        }
        return "UNDEFINED";
    }
}

