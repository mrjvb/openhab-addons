/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
package org.openhab.binding.porscheconnect.internal.handler;

import static org.openhab.binding.porscheconnect.internal.PorscheConnectBindingConstants.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.api.ContentResponse;
import org.openhab.binding.porscheconnect.internal.api.PorscheApi;
import org.openhab.binding.porscheconnect.internal.api.PorscheApiException;
import org.openhab.binding.porscheconnect.internal.api.VehicleData;
import org.openhab.binding.porscheconnect.internal.config.VehicleConfiguration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.OpenClosedType;
import org.openhab.core.library.types.PointType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Handler of one Porsche vehicle. Data is polled by the {@link PorscheAccountHandler} and pushed in via
 * {@link #vehicleUpdated(VehicleData)}; commands are executed through the account's {@link PorscheApi}.
 *
 * @author Marcel van Bergen - Initial contribution
 */

@NonNullByDefault
public class PorscheVehicleHandler extends BaseThingHandler {

    private static final Unit<?> KILOMETRE = MetricPrefix.KILO(SIUnits.METRE);
    private static final Unit<?> KILOWATT = MetricPrefix.KILO(Units.WATT);
    private static final double DEFAULT_CLIMATE_TEMPERATURE = 20.0;

    private static final Set<String> CHANNELS_WITHOUT_BATTERY = Set.of(CH_RANGE_ELECTRIC, CH_SOC, CH_CHARGE_STATUS,
            CH_CHARGE_MODE, CH_CHARGE_RATE, CH_CHARGE_POWER, CH_TARGET_SOC, CH_CHARGE_FINISH, CH_DIRECT_CHARGING);
    private static final Set<String> CHANNELS_WITHOUT_ENGINE = Set.of(CH_FUEL_LEVEL, CH_FUEL_RESERVE,
            CH_SERVICE_OIL_DISTANCE, CH_SERVICE_OIL_TIME, CH_OIL_LEVEL);

    private final Logger logger = LoggerFactory.getLogger(PorscheVehicleHandler.class);

    private VehicleConfiguration config = new VehicleConfiguration();
    private volatile @Nullable VehicleData data;
    private volatile double climateTemperature = DEFAULT_CLIMATE_TEMPERATURE;
    private volatile boolean thingSetUp;

    public PorscheVehicleHandler(Thing thing) {
        super(thing);
    }

    String getVin() {
        return config.vin;
    }

    @Override
    public void initialize() {
        config = getConfigAs(VehicleConfiguration.class);
        if (config.vin.isBlank()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "@text/offline.conf-error.vin");
            return;
        }
        thingSetUp = false;
        PorscheAccountHandler account = getAccountHandler();
        if (account == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED);
            return;
        }
        updateStatus(ThingStatus.UNKNOWN);
        VehicleData cached = account.getVehicleData(config.vin);
        if (cached != null) {
            data = cached;
        }
        account.requestVehicleRefresh(this, false);
    }

    @Override
    public void dispose() {
        data = null;
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (bridgeStatusInfo.getStatus() == ThingStatus.ONLINE) {
            PorscheAccountHandler account = getAccountHandler();
            if (account != null) {
                account.requestVehicleRefresh(this, false);
            }
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
        }
    }

    /** Called by the account handler with fresh data. */
    void vehicleUpdated(VehicleData newData) {
        data = newData;
        if (!thingSetUp) {
            setUpThing(newData);
        }
        updateStatus(ThingStatus.ONLINE);
        updateChannels(newData);
    }

    /** Called by the account handler when this vehicle could not be refreshed. */
    void vehicleError(String message) {
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, message);
    }

    // ---------------------------------------------------------------- commands

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String channel = channelUID.getId();
        if (command instanceof RefreshType) {
            VehicleData current = data;
            if (current != null) {
                updateChannels(current);
            }
            return;
        }
        PorscheAccountHandler account = getAccountHandler();
        PorscheApi api = account != null ? account.getApi() : null;
        VehicleData current = data;
        if (account == null || api == null || current == null || getThing().getStatus() != ThingStatus.ONLINE) {
            logger.debug("Ignoring command {} for {}: vehicle not online", command, channel);
            return;
        }
        String vin = config.vin;

        switch (channel) {
            case CH_CLIMATE_TEMPERATURE -> {
                Double celsius = toCelsius(command);
                if (celsius != null) {
                    climateTemperature = celsius;
                    updateState(CH_CLIMATE_TEMPERATURE, new QuantityType<>(celsius, SIUnits.CELSIUS));
                }
            }
            case CH_CLIMATE_ACTIVE -> {
                if (command instanceof OnOffType onOff) {
                    double temperature = climateTemperature;
                    execute(account, channel, onOff == OnOffType.ON ? () -> api.startClimatisation(vin, temperature)
                            : () -> api.stopClimatisation(vin));
                }
            }
            case CH_DIRECT_CHARGING -> {
                if (command instanceof OnOffType onOff) {
                    execute(account, channel, () -> api.setDirectCharging(vin, onOff == OnOffType.ON));
                }
            }
            case CH_TARGET_SOC -> {
                Integer soc = toPercent(command);
                if (soc != null) {
                    execute(account, channel, () -> api.setTargetSoc(current, soc));
                }
            }
            case CH_LOCK -> {
                if (command == OnOffType.ON) {
                    execute(account, channel, () -> api.lock(vin));
                } else if (command == OnOffType.OFF) {
                    if (config.pin.isBlank()) {
                        logger.warn("Cannot unlock {}: no PIN configured on the vehicle thing", vin);
                        updateChannels(current);
                    } else {
                        String pin = config.pin;
                        execute(account, channel, () -> api.unlock(vin, pin));
                    }
                }
            }
            case CH_FLASH, CH_HONK_FLASH -> {
                if (command == OnOffType.ON) {
                    boolean honk = CH_HONK_FLASH.equals(channel);
                    execute(account, channel, () -> api.flashIndicators(vin, honk));
                }
            }
            case CH_WAKE_UP -> {
                if (command == OnOffType.ON) {
                    account.requestVehicleRefresh(this, true);
                    updateState(CH_WAKE_UP, OnOffType.OFF);
                }
            }
            default -> logger.debug("Channel {} does not accept commands", channel);
        }
    }

    @FunctionalInterface
    private interface RemoteAction {
        void run() throws PorscheApiException, InterruptedException;
    }

    /**
     * Runs a remote command in the background (it can take minutes until the vehicle confirms) and refreshes the
     * vehicle afterwards so the channels show the real state, also when the command failed.
     */
    private void execute(PorscheAccountHandler account, String channel, RemoteAction action) {
        scheduler.execute(() -> {
            try {
                action.run();
                logger.debug("Command on {} for {} performed", channel, config.vin);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (PorscheApiException e) {
                logger.warn("Command on {} for {} failed: {}", channel, config.vin, e.getMessage());
            }
            if (CH_FLASH.equals(channel) || CH_HONK_FLASH.equals(channel)) {
                updateState(channel, OnOffType.OFF);
            }
            account.requestVehicleRefresh(this, false);
        });
    }

    private static @Nullable Double toCelsius(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> celsius = quantity.toUnit(SIUnits.CELSIUS);
            return celsius != null ? celsius.doubleValue() : null;
        }
        if (command instanceof DecimalType decimal) {
            return decimal.doubleValue();
        }
        return null;
    }

    private static @Nullable Integer toPercent(Command command) {
        if (command instanceof QuantityType<?> quantity) {
            QuantityType<?> percent = quantity.toUnit(Units.PERCENT);
            return percent != null ? percent.intValue() : null;
        }
        if (command instanceof DecimalType decimal) {
            return decimal.intValue();
        }
        return null;
    }

    // ---------------------------------------------------------------- thing setup

    /** Properties, channels not applicable to the drivetrain, vehicle picture. Done once per initialization. */
    private void setUpThing(VehicleData vehicle) {
        thingSetUp = true;

        Map<String, String> properties = new HashMap<>(editProperties());
        putIfNotNull(properties, PROPERTY_MODEL, vehicle.getModelName());
        putIfNotNull(properties, PROPERTY_MODEL_YEAR, vehicle.getModelYear());
        putIfNotNull(properties, PROPERTY_ENGINE, vehicle.getEngine());
        updateProperties(properties);

        String engine = vehicle.getEngine();
        if (engine != null) {
            Set<String> unsupported = switch (engine) {
                case VehicleData.ENGINE_COMBUSTION -> CHANNELS_WITHOUT_BATTERY;
                case VehicleData.ENGINE_BEV -> CHANNELS_WITHOUT_ENGINE;
                default -> Set.of();
            };
            List<Channel> toRemove = getThing().getChannels().stream()
                    .filter(c -> unsupported.contains(c.getUID().getId())).toList();
            if (!toRemove.isEmpty()) {
                updateThing(editThing().withoutChannels(toRemove).build());
            }
        }

        if (isLinked(CH_IMAGE)) {
            loadPicture();
        }
    }

    @Override
    public void channelLinked(ChannelUID channelUID) {
        if (CH_IMAGE.equals(channelUID.getId())) {
            loadPicture();
        } else {
            super.channelLinked(channelUID);
        }
    }

    private void loadPicture() {
        PorscheAccountHandler account = getAccountHandler();
        PorscheApi api = account != null ? account.getApi() : null;
        if (api == null) {
            return;
        }
        String vin = config.vin;
        String view = config.imageView;
        scheduler.execute(() -> {
            try {
                String url = null;
                for (JsonElement element : api.getPictures(vin)) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject picture = element.getAsJsonObject();
                    JsonElement viewName = picture.get("view");
                    JsonElement pictureUrl = picture.get("url");
                    if (pictureUrl == null || !pictureUrl.isJsonPrimitive()) {
                        continue;
                    }
                    if (url == null || (viewName != null && view.equalsIgnoreCase(viewName.getAsString()))) {
                        url = pictureUrl.getAsString();
                    }
                }
                if (url == null) {
                    return;
                }
                ContentResponse response = api.downloadPicture(url);
                if (response != null) {
                    String mime = response.getMediaType();
                    updateState(CH_IMAGE,
                            new RawType(response.getContent(), mime != null ? mime : RawType.DEFAULT_MIME_TYPE));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (PorscheApiException e) {
                logger.debug("Could not load picture of {}: {}", vin, e.getMessage());
            }
        });
    }

    // ---------------------------------------------------------------- channel updates

    private void updateChannels(VehicleData d) {
        // status
        update(CH_MILEAGE, quantity(d.number("MILEAGE", "kilometers"), KILOMETRE));
        Boolean closed = d.isClosed();
        update(CH_CLOSED, closed == null ? UnDefType.UNDEF : closed ? OpenClosedType.CLOSED : OpenClosedType.OPEN);
        update(CH_PRIVACY_MODE, onOff(d.bool("GLOBAL_PRIVACY_MODE", "isEnabled")));
        update(CH_REMOTE_ACCESS, onOff(d.bool("REMOTE_ACCESS_AUTHORIZATION", "isEnabled")));
        update(CH_PARKING_BRAKE, onOff(d.bool("PARKING_BRAKE", "isOn")));
        update(CH_PARKING_LIGHT, onOff(d.bool("PARKING_LIGHT", "isOn")));
        update(CH_LAST_UPDATE, dateTime(d.timestamp("GLOBAL_TIMESTAMP", "timestamp")));

        // range
        update(CH_RANGE_TOTAL, quantity(d.number("RANGE", "kilometers"), KILOMETRE));
        update(CH_RANGE_ELECTRIC, quantity(d.number("E_RANGE", "kilometers"), KILOMETRE));
        update(CH_FUEL_LEVEL, quantity(d.number("FUEL_LEVEL", "percent"), Units.PERCENT));
        update(CH_FUEL_RESERVE, quantity(d.number("FUEL_RESERVE", "percent"), Units.PERCENT));

        // charge
        update(CH_SOC, quantity(d.number("BATTERY_LEVEL", "percent"), Units.PERCENT));
        update(CH_CHARGE_STATUS, string(d.string("CHARGING_SUMMARY", "status")));
        update(CH_CHARGE_MODE, string(d.string("CHARGING_SUMMARY", "mode")));
        update(CH_CHARGE_RATE, quantity(d.getChargingRateKmh(), SIUnits.KILOMETRE_PER_HOUR));
        update(CH_CHARGE_POWER, quantity(d.getChargingPowerKw(), KILOWATT));
        update(CH_TARGET_SOC, quantity(d.getChargingTarget(), Units.PERCENT));
        update(CH_CHARGE_FINISH, dateTime(d.timestamp("CHARGING_SUMMARY", "targetDateTimeWithOffset")));
        if (d.has("CHARGING_SUMMARY")) {
            update(CH_DIRECT_CHARGING, OnOffType.from(d.isDirectChargeOn()));
        } else {
            update(CH_DIRECT_CHARGING, UnDefType.UNDEF);
        }

        // climate
        update(CH_CLIMATE_ACTIVE, onOff(d.isClimatisationOn()));
        Double target = d.getClimatisationTargetCelsius();
        if (target != null) {
            climateTemperature = target;
        }
        update(CH_CLIMATE_TEMPERATURE, new QuantityType<>(climateTemperature, SIUnits.CELSIUS));
        update(CH_CLIMATE_REMAINING, quantity(d.number("HVAC_SUMMARY", "remainingTime"), Units.MINUTE));

        // doors, lids, windows
        Map<String, Boolean> openStates = d.openStates();
        for (Entry<String, String> entry : OPEN_STATE_CHANNELS.entrySet()) {
            Boolean open = openStates.get(entry.getKey());
            update(entry.getValue(),
                    open == null ? UnDefType.UNDEF : open ? OpenClosedType.OPEN : OpenClosedType.CLOSED);
        }

        // tires
        for (Entry<String, String> entry : TIRE_CHANNELS.entrySet()) {
            update(entry.getValue(),
                    quantity(d.number("TIRE_PRESSURE_" + entry.getKey(), "actualPressureBar"), Units.BAR));
        }
        update(CH_TIRE_WARNING, onOff(d.isTirePressureWarning()));

        // service
        update(CH_SERVICE_MAIN_DISTANCE, quantity(d.number("MAIN_SERVICE_RANGE", "kilometers"), KILOMETRE));
        update(CH_SERVICE_MAIN_TIME, quantity(d.number("MAIN_SERVICE_TIME", "days"), Units.DAY));
        update(CH_SERVICE_OIL_DISTANCE, quantity(d.number("OIL_SERVICE_RANGE", "kilometers"), KILOMETRE));
        update(CH_SERVICE_OIL_TIME, quantity(d.number("OIL_SERVICE_TIME", "days"), Units.DAY));
        update(CH_SERVICE_INTERMEDIATE_DISTANCE,
                quantity(d.number("INTERMEDIATE_SERVICE_RANGE", "kilometers"), KILOMETRE));
        update(CH_SERVICE_INTERMEDIATE_TIME, quantity(d.number("INTERMEDIATE_SERVICE_TIME", "days"), Units.DAY));
        update(CH_OIL_LEVEL, quantity(d.number("OIL_LEVEL_CURRENT", "percent"), Units.PERCENT));

        // location
        double[] location = d.getLocation();
        update(CH_POSITION, location == null ? UnDefType.UNDEF
                : new PointType(new DecimalType(location[0]), new DecimalType(location[1])));
        update(CH_HEADING, quantity(d.number("GPS_LOCATION", "direction"), Units.DEGREE_ANGLE));
        update(CH_LOCATION_UPDATE, dateTime(d.timestamp("GPS_LOCATION", "lastModified")));

        // control
        update(CH_LOCK, onOff(d.bool("LOCK_STATE_VEHICLE", "isLocked")));
    }

    /** Updates a channel only if the thing still has it (some are removed depending on the drivetrain). */
    private void update(String channelId, State state) {
        if (getThing().getChannel(channelId) != null) {
            updateState(channelId, state);
        }
    }

    private static State quantity(@Nullable Double value, Unit<?> unit) {
        return value == null ? UnDefType.UNDEF : new QuantityType<>(value, unit);
    }

    private static State onOff(@Nullable Boolean value) {
        return value == null ? UnDefType.UNDEF : OnOffType.from(value);
    }

    private static State string(@Nullable String value) {
        return value == null ? UnDefType.UNDEF : new StringType(value);
    }

    private static State dateTime(@Nullable Instant value) {
        return value == null ? UnDefType.UNDEF : new DateTimeType(value);
    }

    private static void putIfNotNull(Map<String, String> map, String key, @Nullable String value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private @Nullable PorscheAccountHandler getAccountHandler() {
        Bridge bridge = getBridge();
        return bridge != null && bridge.getHandler() instanceof PorscheAccountHandler handler ? handler : null;
    }
}
