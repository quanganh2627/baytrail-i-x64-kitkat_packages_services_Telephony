package com.android.phone;

interface ISimWidgetService {
    boolean setPrimarySim(int simId);
    int getPrimarySim();
    void enableSim(int simId, boolean enable);
    boolean isSimOn(int simId);
}
