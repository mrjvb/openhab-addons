# Dyson Binding

This binding integrates Dyson Wi-Fi connected devices: air purifiers, fans, Hot+Cool heaters, humidifiers and robot vacuums.
It is modelled after the [hass-dyson](https://github.com/cmgrayb/hass-dyson) Home Assistant integration.

Devices are discovered through a MyDyson cloud account.
They are controlled over MQTT, either directly on the local network or through the Dyson cloud (AWS IoT), with optional fallback between the two.

## Supported Things

| Thing type | Description                                                                                                 |
|------------|-------------------------------------------------------------------------------------------------------------|
| `account`  | Bridge for a MyDyson account. Used for discovery, credentials, cloud connections and firmware information.  |
| `fan`      | Dyson environment cleaners: Pure Cool / Purifier, Hot+Cool, Humidify+Cool, Big+Quiet, Link models, etc.     |
| `robot`    | Dyson robot vacuums: 360 Eye, 360 Heurist, 360 Vis Nav and similar.                                         |

Bluetooth-only devices (lamps, cordless vacuums) are not supported.

## Discovery

After the `account` bridge is online, all Wi-Fi devices of the account appear in the inbox.

## Thing Configuration

### `account` Bridge

| Name              | Type    | Description                                                                                   | Default | Required |
|-------------------|---------|-----------------------------------------------------------------------------------------------|---------|----------|
| `email`           | text    | E-mail address of the MyDyson account (a mobile number with country code in the China region) | N/A     | yes      |
| `password`        | text    | Password of the MyDyson account                                                               | N/A     | yes      |
| `country`         | text    | Two letter country code, e.g. `US`, `GB`, `DE`, `CN`                                          | US      | yes      |
| `culture`         | text    | Language tag, e.g. `en-US`                                                                    | en-US   | no       |
| `otp`             | text    | One-time verification code sent by Dyson during login                                         | N/A     | no       |
| `token`           | text    | Authentication token, filled in automatically                                                 | N/A     | no       |
| `refreshInterval` | integer | Minutes between refreshes of the device list and firmware information                        | 60      | no       |

#### Login

Dyson uses two-factor authentication:

1. Create the bridge with e-mail, password and country.
2. The bridge goes OFFLINE with the message that a verification code was sent. Dyson e-mails you a code.
3. Enter the code in the `otp` parameter and save. The binding completes the login, stores the token and clears the code.

The token is reused after restarts. If Dyson ever rejects it, a new code is requested automatically.

### `fan` and `robot` Things

| Name                        | Type    | Description                                                                                         | Default              | Required |
|-----------------------------|---------|-----------------------------------------------------------------------------------------------------|----------------------|----------|
| `serialNumber`              | text    | Serial number of the device                                                                         | N/A                  | yes      |
| `connectionType`            | text    | `LOCAL`, `LOCAL_CLOUD_FALLBACK`, `CLOUD_LOCAL_FALLBACK` or `CLOUD`                                   | LOCAL_CLOUD_FALLBACK | no       |
| `hostname`                  | text    | Host name or IP address for local connections. Defaults to `<serialNumber>.local` (mDNS)            | N/A                  | no       |
| `refreshInterval`           | integer | Seconds between state requests                                                                      | 30                   | no       |
| `removeUnsupportedChannels` | boolean | Remove channels of features the device does not report                                              | true                 | no       |
| `localPassword`             | text    | Local MQTT password (manual setup only)                                                             | N/A                  | no       |
| `mqttPrefix`                | text    | MQTT root topic / product type, e.g. `438M` (manual setup only)                                     | N/A                  | no       |
| `capabilities`              | text    | Comma separated capabilities, e.g. `AdvanceOscillationDay1,Scheduling,ExtendedAQ` (manual setup only) | N/A                | no       |

With an account bridge, `localPassword`, `mqttPrefix` and `capabilities` are retrieved from the cloud.
Without a bridge (e.g. on an isolated network), configure them manually and use `connectionType` `LOCAL`.

Connection types:

- `LOCAL`: only the MQTT broker on the device (port 1883). Maximum privacy, works without internet.
- `LOCAL_CLOUD_FALLBACK`: local, falls back to the Dyson cloud when the device cannot be reached locally.
- `CLOUD_LOCAL_FALLBACK`: Dyson cloud, falls back to local when the cloud cannot be reached.
- `CLOUD`: only the Dyson cloud. Useful when mDNS or the local network does not work.

On a fallback connection the binding retries the preferred connection every 5 minutes.
A fixed IP address (DHCP reservation) in `hostname` is recommended for local connections.

Shortly after the first connection, channels for features that the device does not report are removed (see `removeUnsupportedChannels`).
Delete and re-add the thing to restore them.

## Channels

### Common Channels (`fan` and `robot`)

| Channel                     | Type         | Read/Write | Description                                         |
|-----------------------------|--------------|------------|-----------------------------------------------------|
| `wifi-signal`               | Number:Power | R          | Wi-Fi signal strength (dBm)                         |
| `connection-status`         | String       | R          | `LOCAL`, `CLOUD` or `DISCONNECTED`                  |
| `host`                      | String       | R          | Host used for the current connection                |
| `reconnect`                 | Switch       | W          | ON reconnects using the preferred connection        |
| `firmware-version`          | String       | R          | Installed firmware version                          |
| `firmware-latest-version`   | String       | R          | Latest available firmware version                   |
| `firmware-update-available` | Switch       | R          | ON when a firmware update is available              |
| `firmware-auto-update`      | Switch       | R          | Automatic firmware updates enabled in the app       |
| `firmware-update`           | Switch       | W          | ON asks the Dyson cloud to install the update       |

### `fan` Channels

| Channel                    | Type                 | Read/Write | Description                                                                 |
|----------------------------|----------------------|------------|-----------------------------------------------------------------------------|
| `power`                    | Switch               | RW         | Fan on/off                                                                  |
| `fan-speed`                | Number               | RW         | Speed 1-10 (0 = off). Shows the actual speed in auto mode                   |
| `auto-mode`                | Switch               | RW         | Automatic speed based on air quality                                        |
| `night-mode`               | Switch               | RW         | Quiet operation, dimmed display                                             |
| `oscillation`              | Switch               | RW         | Oscillation on/off                                                          |
| `oscillation-mode`         | String               | RW         | Preset: `45`, `90`, `180`, `350`, `BREEZE`, `CUSTOM` (Day0 models: `OFF`, `15`, `40`, `70`) |
| `oscillation-lower-angle`  | Number:Angle         | RW         | Lower oscillation angle                                                     |
| `oscillation-upper-angle`  | Number:Angle         | RW         | Upper oscillation angle                                                     |
| `oscillation-center-angle` | Number:Angle         | RW         | Moves the oscillation range                                                 |
| `oscillation-span`         | Number:Angle         | RW         | Width of the oscillation range                                              |
| `tilt-oscillation`         | String               | RW         | Vertical oscillation: `0`, `25`, `50`, `BREEZE`                             |
| `sleep-timer`              | Number:Time          | RW         | Sleep timer, 0 or 15-540 minutes                                            |
| `continuous-monitoring`    | Switch               | RW         | Keep sensors active while the fan is off                                    |
| `front-airflow`            | Switch               | RW         | Front (ON) or back (OFF) airflow                                            |
| `focus-mode`               | Switch               | RW         | Focused or diffused airflow (older heaters)                                 |
| `find-follow`              | Switch               | RW         | Find+Follow on/off                                                          |
| `find-follow-status`       | String               | R          | Find+Follow status                                                          |
| `find-follow-scan`         | Switch               | W          | ON starts a Find+Follow scan                                                |
| `heating-mode`             | String               | RW         | `OFF` or `HEAT`                                                             |
| `target-temperature`       | Number:Temperature   | RW         | Heating target, 1-37 °C (switches heating on)                               |
| `heating-active`           | Switch               | R          | Heating element is heating                                                  |
| `humidifier`               | Switch               | RW         | Humidifier on/off                                                           |
| `humidifier-auto`          | Switch               | RW         | Automatic humidity target                                                   |
| `target-humidity`          | Number:Dimensionless | RW         | Humidity target, 30-70 %                                                    |
| `water-hardness`           | String               | RW         | `SOFT`, `MEDIUM`, `HARD`                                                    |
| `next-deep-clean`          | Number:Time          | R          | Time until the next humidifier deep clean                                   |
| `deep-clean-remaining`     | Number:Time          | R          | Remaining time of a running deep clean                                      |
| `temperature`              | Number:Temperature   | R          | Room temperature                                                            |
| `humidity`                 | Number:Dimensionless | R          | Relative humidity                                                           |
| `pm25`                     | Number:Density       | R          | PM2.5                                                                       |
| `pm10`                     | Number:Density       | R          | PM10                                                                        |
| `particulates`             | Number:Density       | R          | Particulate level (Link models)                                             |
| `voc`                      | Number:Density       | R          | Volatile organic compounds                                                  |
| `no2`                      | Number:Density       | R          | Nitrogen dioxide                                                            |
| `co2`                      | Number:Dimensionless | R          | Carbon dioxide (ppm)                                                        |
| `formaldehyde`             | Number:Density       | R          | Formaldehyde                                                                |
| `aqi`                      | Number               | R          | Overall air quality index (0-500)                                           |
| `aqi-category`             | String               | R          | `GOOD`, `FAIR`, `POOR`, `VERY_POOR`, `EXTREMELY_POOR`, `SEVERE`             |
| `dominant-pollutant`       | String               | R          | Pollutant(s) determining the index                                          |
| `hepa-filter-life`         | Number:Dimensionless | R          | Remaining HEPA filter life                                                  |
| `hepa-filter-type`         | String               | R          | Installed HEPA filter type                                                  |
| `carbon-filter-life`       | Number:Dimensionless | R          | Remaining carbon filter life                                                |
| `carbon-filter-type`       | String               | R          | Installed carbon filter type                                                |
| `filter-replacement`       | Switch               | R          | ON when the filter life is 10 % or less                                     |
| `reset-hepa-filter`        | Switch               | W          | ON resets the HEPA filter life                                              |
| `reset-carbon-filter`      | Switch               | W          | ON resets the carbon filter life                                            |
| `fault`                    | Switch               | R          | ON while any fault is reported                                              |
| `active-faults`            | String               | R          | Description of all active faults                                            |
| `fault-<code>`             | Switch               | R          | Individual faults: `aqs`, `fltr`, `hflr`, `cflr`, `mflr`, `temp`, `humi`, `tnke` (tank empty), `tnkp` (tank missing), `cldu`, `etwd`, `pwr`, `wifi`, `sys` |

### `robot` Channels

| Channel                    | Type                 | Read/Write | Description                                                          |
|----------------------------|----------------------|------------|----------------------------------------------------------------------|
| `activity`                 | String               | R          | `CLEANING`, `PAUSED`, `RETURNING`, `DOCKED`, `IDLE`, `ERROR`         |
| `state`                    | String               | R          | Raw robot state, e.g. `FULL_CLEAN_RUNNING`                           |
| `command`                  | String               | W          | `START` (resumes when paused), `PAUSE`, `RESUME`, `ABORT`, `DOCK`    |
| `battery-level`            | Number:Dimensionless | R          | Battery level                                                        |
| `charging`                 | Switch               | R          | Robot is charging                                                    |
| `power-mode`               | String               | RW         | Suction power (options depend on the model)                          |
| `clean-type`               | String               | R          | How the clean was started                                            |
| `clean-id`                 | String               | R          | Cleaning session id                                                  |
| `clean-duration`           | Number:Time          | R          | Duration of the current or last clean                                |
| `position`                 | String               | R          | Position on the map (`x,y`)                                          |
| `dock-state`               | String               | R          | Dock activity (e.g. mop drying)                                      |
| `active-fault`             | String               | R          | Fault requiring attention, or `none`                                 |
| `action-required`          | Switch               | R          | ON when the robot needs user action                                  |
| `<consumable>-life`        | Number:Dimensionless | R          | `brush-bar`, `mop-roller`, `side-brushes`, `robot-filter`, `dock-filter`, `ioniser-cartridge` |
| `cleaning-solution-refill` | Switch               | R          | Cleaning solution needs a refill                                     |
| `robot-fault-<subsystem>`  | Switch               | R          | `airways`, `battery`, `brush-bar-and-traction`, `charge-station`, `lift`, `lost`, `optics` |

## Full Example

### Thing Configuration

```java
Bridge dyson:account:home "Dyson Account" [ email="me@example.com", password="secret", country="GB" ] {
    Thing fan livingroom "Living Room Purifier" [ serialNumber="VS6-EU-HJA1234A", hostname="192.168.1.50" ]
    Thing robot vacuum "Robot Vacuum" [ serialNumber="NN2-EU-KAA1234A", connectionType="CLOUD" ]
}

// Without cloud account (sticker / manual setup)
Thing dyson:fan:bedroom "Bedroom Fan" [ serialNumber="AB1-EU-CDE1234F", connectionType="LOCAL", hostname="192.168.1.51", localPassword="xxxxxxxx", mqttPrefix="438M", capabilities="AdvanceOscillationDay1,Scheduling,ExtendedAQ" ]
```

### Item Configuration

```java
Switch               Purifier_Power      "Power"            { channel="dyson:fan:home:livingroom:power" }
Number               Purifier_Speed      "Fan speed [%d]"   { channel="dyson:fan:home:livingroom:fan-speed" }
Switch               Purifier_Auto       "Auto mode"        { channel="dyson:fan:home:livingroom:auto-mode" }
String               Purifier_Osc        "Oscillation"      { channel="dyson:fan:home:livingroom:oscillation-mode" }
Number:Temperature   Purifier_Temp       "Temperature"      { channel="dyson:fan:home:livingroom:temperature" }
Number:Density       Purifier_PM25       "PM2.5"            { channel="dyson:fan:home:livingroom:pm25" }
Number:Dimensionless Purifier_Filter     "HEPA filter"      { channel="dyson:fan:home:livingroom:hepa-filter-life" }

String               Vacuum_Activity     "Activity"         { channel="dyson:robot:home:vacuum:activity" }
String               Vacuum_Command      "Command"          { channel="dyson:robot:home:vacuum:command" }
Number:Dimensionless Vacuum_Battery      "Battery"          { channel="dyson:robot:home:vacuum:battery-level" }
```

## Limitations

- Bluetooth devices (Lightcycle lamps, BLE floor cleaners) are not supported.
- Vis Nav zone/room cleaning, maps, schedules and cleaning history are not supported; robots clean the whole home.
- The firmware auto-update setting is read-only (the Dyson API for changing it is undocumented).
