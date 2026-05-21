/*
 * Java-systemd implementation
 * Copyright (c) 2016 Markus Enax
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of either the GNU Lesser General Public License Version 2 or the
 * Academic Free Licence Version 3.0.
 *
 * Full licence texts are included in the COPYING file with this program.
 */

package de.thjom.java.systemd;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnection.DBusBusType;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.exceptions.NotConnected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Systemd {

    public enum InstanceType {

        SYSTEM(DBusConnection.DBusBusType.SYSTEM),
        USER(DBusConnection.DBusBusType.SESSION);

        private final DBusConnection.DBusBusType index;

        InstanceType(DBusConnection.DBusBusType index) {
            this.index = index;
        }

        public final DBusConnection.DBusBusType getIndex() {
            return index;
        }

        @Override
        public String toString() {
            return super.toString().toLowerCase();
        }

    }

    public static final String SERVICE_NAME = "org.freedesktop.systemd1";
    public static final String OBJECT_PATH = "/org/freedesktop/systemd1";

    public static final Pattern PATH_ESCAPE_PATTERN = Pattern.compile("([\\W_])");

    public static final byte DEFAULT_THREAD_POOL_SIZE = 1;

    private static final Logger LOG = LoggerFactory.getLogger(Systemd.class);

    private static final Systemd[] INSTANCES = new Systemd[InstanceType.values().length];

    private final InstanceType instanceType;
    private final boolean ownsConnection;

    private DBusConnection dbus;
    private Manager manager;

    private Systemd(final InstanceType instanceType) {
        this(instanceType, null, true);
    }

    private Systemd(final InstanceType instanceType, final DBusConnection dbus, final boolean ownsConnection) {
        this.instanceType = instanceType;
        this.dbus = dbus;
        this.ownsConnection = ownsConnection;
    }

    public static String escapePath(final CharSequence path) {
        if (path != null) {
            StringBuilder escaped = new StringBuilder(path.length());
            Matcher matcher = PATH_ESCAPE_PATTERN.matcher(path);

            while (matcher.find()) {
                String replacement = '_' + Integer.toHexString(matcher.group().charAt(0));
                matcher.appendReplacement(escaped, replacement);
            }

            matcher.appendTail(escaped);

            return escaped.toString();
        }

        return "";
    }

    public static Instant timestampToInstant(final long timestamp) {
        return Instant.EPOCH.plus(timestamp, ChronoUnit.MICROS);
    }

    public static Duration usecsToDuration(final long usecs) {
        return Duration.of(usecs, ChronoUnit.MICROS);
    }

    public static String id128ToString(final byte[] id128) {
        return HexFormat.of().formatHex(id128);
    }

    public static Systemd get() throws DBusException {
        return get(InstanceType.SYSTEM);
    }

    public static Systemd get(final InstanceType instanceType) throws DBusException {
        final DBusBusType index = instanceType.getIndex();

        Systemd instance;

        synchronized (INSTANCES) {
            if (INSTANCES[index.ordinal()] == null) {
                instance = new Systemd(instanceType);
                instance.open();

                INSTANCES[index.ordinal()] = instance;
            }
            else {
                instance = INSTANCES[index.ordinal()];
            }
        }

        return instance;
    }

    public static Systemd fromConnection(final DBusConnection dbus) {
        return fromConnection(InstanceType.SYSTEM, dbus);
    }

    public static Systemd fromConnection(final InstanceType instanceType, final DBusConnection dbus) {
        if (instanceType == null) {
            throw new IllegalArgumentException("instanceType must not be null");
        }
        if (dbus == null) {
            throw new IllegalArgumentException("dbus must not be null");
        }

        return new Systemd(instanceType, dbus, false);
    }

    public static void disconnect() {
        disconnect(InstanceType.SYSTEM);
    }

    public static void disconnect(final InstanceType instanceType) {
        final DBusBusType index = instanceType.getIndex();

        synchronized (INSTANCES) {
            Systemd instance = INSTANCES[index.ordinal()];

            if (instance != null) {
                instance.close();
            }

            INSTANCES[index.ordinal()] = null;
        }
    }

    public static void disconnectAll() {
        synchronized (INSTANCES) {
            for (Systemd instance : INSTANCES) {
                if (instance != null) {
                    instance.close();
                }
            }

            Arrays.fill(INSTANCES, null);
        }
    }

    private void open() throws DBusException {
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Connecting to %s bus", instanceType));
        }

        try {
            dbus = DBusConnectionBuilder.forType(instanceType.getIndex())
                    .receivingThreadConfig()
                        .withSignalThreadCount(DEFAULT_THREAD_POOL_SIZE)
                    .connectionConfig()
                    .build();
        }
        catch (final DBusException e) {
            LOG.error(String.format("Unable to connect to %s bus", instanceType), e);

            throw e;
        }
    }

    private void close() {
        if (ownsConnection && isConnected()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Disconnecting from %s bus", instanceType));
            }

            dbus.disconnect();
        }

        dbus = null;
        manager = null;
    }

    public boolean isConnected() {
        return !(dbus == null || dbus.getError() instanceof NotConnected);
    }

    Optional<DBusConnection> getConnection() {
        return Optional.ofNullable(dbus);
    }

    public Manager getManager() throws DBusException {
        if (manager == null) {
            if (!isConnected()) {
                throw new DBusException("Unable to create manager without bus (please connect first)");
            }

            if (LOG.isDebugEnabled()) {
                LOG.debug(String.format("Creating new manager instance on %s bus", instanceType));
            }

            manager = Manager.create(dbus);
        }

        return manager;
    }

}
