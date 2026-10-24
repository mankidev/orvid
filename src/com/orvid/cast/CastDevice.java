package com.orvid.cast;

public final class CastDevice {

    public final String id;
    public final String name;
    public final String model;
    public final String host;
    public final int port;

    public CastDevice(String id, String name, String model, String host, int port) {
        this.id = id;
        this.name = name;
        this.model = model;
        this.host = host;
        this.port = port;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CastDevice && ((CastDevice) o).id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
