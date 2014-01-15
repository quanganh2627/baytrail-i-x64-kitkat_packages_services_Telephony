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

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Container class for video modes.
 */
public class VideoMode implements Parcelable {

    public final static int NONE = 0;
    public final static int RECEIVE_ONLY = 1;
    public final static int SEND_ONLY = 2;
    public final static int DUPLEX = 3;

    public int value = NONE;

    public VideoMode(Parcel p) {
        value = p.readInt();
    }

    public VideoMode(int value) {
        this.value = value;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(value);
    }

    /**
     * Creates Call objects for Parcelable implementation.
     */
    public static final Creator<VideoMode> CREATOR = new Creator<VideoMode>() {

        @Override
        public VideoMode createFromParcel(Parcel in) {
            return new VideoMode(in);
        }

        @Override
        public VideoMode[] newArray(int size) {
            return new VideoMode[size];
        }
    };

    @Override
    public String toString() {
        return Integer.toString(value);
    }
}
