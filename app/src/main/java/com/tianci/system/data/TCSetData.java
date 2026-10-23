package com.tianci.system.data;

import java.io.Serializable;

/**
 * Wire-compatible mirror of Skyworth/Coocaa's {@code com.tianci.system.data.TCSetData}.
 *
 * <p>The TV's {@code content://com.tianci.provider} takes a {@link Serializable} in a Bundle
 * ({@code METHOD_SYSTEM_CMD_GET_CONFIG} / {@code METHOD_SYSTEM_CMD_SET_CONFIG}). The class is
 * resolved by name and serialVersionUID in the provider's process, so this copy must keep the
 * vendor's UID. The value was computed from the class as shipped in SkySystemService on a
 * Skyworth 8H52_G3 (Android 5.1.1); the vendor class declares no UID of its own.
 *
 * <p>{@code pluginValue} is intentionally omitted: it is null in the configs Aerial writes, and an
 * absent field deserializes to null on the other side.
 */
public class TCSetData implements Serializable {
    private static final long serialVersionUID = 2885028736638460682L;

    protected String type;
    protected String name;
    protected String value;
    protected boolean enable = true;
    protected long startProcessTimestamp;
    protected long endProcessTimestamp;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public boolean isEnable() {
        return enable;
    }

    public void setEnable(boolean enable) {
        this.enable = enable;
    }

    public long getStartProcessTimestamp() {
        return startProcessTimestamp;
    }

    public void setStartProcessTimestamp(long startProcessTimestamp) {
        this.startProcessTimestamp = startProcessTimestamp;
    }

    public long getEndProcessTimestamp() {
        return endProcessTimestamp;
    }

    public void setEndProcessTimestamp(long endProcessTimestamp) {
        this.endProcessTimestamp = endProcessTimestamp;
    }
}
