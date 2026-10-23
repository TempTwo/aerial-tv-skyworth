package com.tianci.system.data;

/**
 * Wire-compatible mirror of Skyworth/Coocaa's {@code com.tianci.system.data.TCRangeSetData}.
 *
 * <p>Picture settings such as the panel backlight are ranges: the TV returns min/max/current and
 * applies a write of {@code current}. The serialVersionUID is the vendor class's computed value
 * (see {@link TCSetData}); don't change the fields without recomputing it.
 */
public class TCRangeSetData extends TCSetData {
    private static final long serialVersionUID = 5953384215072876485L;

    private int current;
    private int max;
    private int min;

    public TCRangeSetData() {
        setType("SKY_CONFIG_RANGE");
    }

    public int getCurrent() {
        return current;
    }

    public void setCurrent(int current) {
        this.current = current;
    }

    public int getMax() {
        return max;
    }

    public void setMax(int max) {
        this.max = max;
    }

    public int getMin() {
        return min;
    }

    public void setMin(int min) {
        this.min = min;
    }
}
