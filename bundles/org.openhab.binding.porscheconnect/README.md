# Porsche Connect Binding

This binding connects openHAB to Porsche vehicles with _Porsche Connect_ — the service behind the My Porsche app.
It connects to the Porsche cloud, so it works wherever the car has mobile reception.

It is based on the Python library [pyporscheconnectapi](https://github.com/CJNE/pyporscheconnectapi) that is also used for the HA integration [ha-porscheconnect](https://github.com/CJNE/ha-porscheconnect).
The Porsche API is not public and can therefore change without notice.

Porsche Connect is available for most models from 2022 (that have PCM 6.0 or newer) such as:

- Porsche 911 (992.1 facelift and 992.2, from model year 2022)
- Porsche Taycan (J1 phase 1 and phase 2 J1 II from model year 2022)
- Porsche Cayenne (E3 / E3 II from model year 2022)
- Porsche Panamera (late generation 2 of G2 and all G3 from model year 2022)
- Porsche Macan Electric (and later generation ICE models from model year 2024)

Note that The older models such as the Porsche 718 (Boxster/Cayman), older iterations of the Macan (2017–2023) running PCM 5.0 or older, and legacy 911s (like the 991 generation) do not feature PCM 6.0 natively and therefor do not support _Porsche Connect_. This binding does not support the legacy _Porsche Car Connect_ service.

## Supported Things

| Thing type | Description                                                                |
|------------|----------------------------------------------------------------------------|
| `account`  | Bridge for a Porsche ID (e-mail address and password used in My Porsche)   |
| `vehicle`  | Vehicle belonging to the Porsche ID bridge                                 |

## Discovery

Once the `account` bridge is online, all vehicles belonging to the Porsche ID appear in the inbox.

## Thing Configuration

### `account` Bridge

| Name              | Type    | Description                                                                | Default | Required |
|-------------------|---------|----------------------------------------------------------------------------|---------|----------|
| `email`           | text    | E-mail address of the Porsche ID                                           | -       | yes      |
| `password`        | text    | Password of the Porsche ID                                                 | -       | yes      |
| `captcha`         | text    | Answer to a captcha, only when the bridge status asks for one (see below)  | -       | no       |
| `refreshInterval` | integer | Minutes between updates (minimum 5)                                        | 30      | no       |

The regular update reads the values the car last sent to the Porsche cloud; it does not wake the car.
Polling more often than every 30 minutes is possible but please note that Porsche rate-limits the API (HTTP 429).

#### Captcha

Porsche's identity server sometimes asks for a captcha, typically on the first login from a new system.
The bridge then goes OFFLINE with status detail `CONFIGURATION_PENDING`, and the captcha is shown:

- as file `http://<openhab-host>:8080/static/porscheconnect-captcha-<bridge-id>.svg` (written to `$OPENHAB_CONF/html/`), and
- on the bridge's `captcha` Image channel.

Enter the characters in the bridge's `captcha` parameter and save, the bridge resumes the login, and the parameter is cleared again. If the captcha has expired, a new one is shown.

After a successful login the binding keeps the OAuth tokens (stored in openHAB's JSON database, not in the thing configuration) and renews them with the refresh token, so the password and captcha are rarely needed again.

### `vehicle` Thing

| Name        | Type | Description                                                         | Default     | Required |
|-------------|------|---------------------------------------------------------------------|-------------|----------|
| `vin`       | text | Vehicle identification number                                       | -           | yes      |
| `pin`       | text | 4 digit Porsche ID PIN, only needed for unlocking                   | -           | no       |
| `imageView` | text | Picture for the `status#image` channel: `frontView`, `sideView`, `rearView`, `rearTopView`, `topView` | `frontView` | no |

The PIN is never sent to Porsche; the binding sends a SHA-512 hash of PIN and a one-time server challenge, just like the app.

## Channels

Channels that do not apply to the drivetrain are removed: charging channels for combustion cars, fuel and oil channels for battery electric cars.
Other channels show `UNDEF` when the car does not report the value.

### Account

| Channel   | Type  | Description                                  |
|-----------|-------|----------------------------------------------|
| `captcha` | Image | Captcha to solve when the login asks for one |

### Vehicle

| Channel                          | Type                 | Read/Write | Description                                                                 |
|----------------------------------|----------------------|------------|-----------------------------------------------------------------------------|
| `status#mileage`                 | Number:Length        | R          | Odometer                                                                    |
| `status#closed`                  | Contact              | R          | CLOSED when all doors, lids and windows are closed                          |
| `status#privacy-mode`            | Switch               | R          | Privacy mode enabled                                                        |
| `status#remote-access`           | Switch               | R          | Remote access authorized (required for commands)                            |
| `status#parking-brake`           | Switch               | R          | Parking brake engaged                                                       |
| `status#parking-light`           | Switch               | R          | Parking light on                                                            |
| `status#last-update`             | DateTime             | R          | Time the car last reported data                                             |
| `status#image`                   | Image                | R          | Vehicle picture as selected under the `imageView` Thing setting             |
| `range#total`                    | Number:Length        | R          | Remaining range                                                             |
| `range#electric`                 | Number:Length        | R          | Electric range (BEV/PHEV)                                                   |
| `range#fuel-level`               | Number:Dimensionless | R          | Fuel level (combustion/PHEV)                                                |
| `range#fuel-reserve`             | Number:Dimensionless | R          | Fuel reserve threshold                                                      |
| `charge#soc`                     | Number:Dimensionless | R          | State of charge                                                             |
| `charge#status`                  | String               | R          | Charging status, e.g. `CHARGING`, `CHARGING_COMPLETED`, `NOT_PLUGGED`       |
| `charge#mode`                    | String               | R          | Charging mode, e.g. `DIRECT`, `PROFILE`                                     |
| `charge#rate`                    | Number:Speed         | R          | Range gained per hour                                                       |
| `charge#power`                   | Number:Power         | R          | Charging power                                                              |
| `charge#target-soc`              | Number:Dimensionless | R/W        | Charge limit, 25–100 %                                                      |
| `charge#finish-time`             | DateTime             | R          | Expected end of charging                                                    |
| `charge#direct-charging`         | Switch               | R/W        | Charge now, ignoring timers                                                 |
| `climate#active`                 | Switch               | R/W        | Start/stop remote climatisation                                             |
| `climate#target-temperature`     | Number:Temperature   | R/W        | Temperature used the next time climatisation is started                     |
| `climate#remaining-time`         | Number:Time          | R          | Remaining climatisation time                                                |
| `doors#front-left` … `doors#rear-right` | Contact       | R          | Doors                                                                       |
| `doors#hood`, `doors#trunk`      | Contact              | R          | Front and rear lid                                                          |
| `doors#service-flap`, `doors#sunroof`, `doors#sunroof-rear`, `doors#top`, `doors#spoiler` | Contact | R | Further openings, when the car has them |
| `windows#front-left` … `windows#rear-right` | Contact   | R          | Windows                                                                     |
| `tires#front-left` … `tires#rear-right` | Number:Pressure | R        | Tire pressures                                                              |
| `tires#warning`                  | Switch               | R          | ON if a tire deviates more than 0.3 bar from its target                     |
| `service#main-distance`, `service#main-time` | Number:Length, Number:Time | R | Main service due in                                             |
| `service#oil-distance`, `service#oil-time`   | Number:Length, Number:Time | R | Oil service due in                                              |
| `service#intermediate-distance`, `service#intermediate-time` | Number:Length, Number:Time | R | Intermediate service due in                     |
| `service#oil-level`              | Number:Dimensionless | R          | Engine oil level                                                            |
| `location#position`              | Location             | R          | GPS position                                                                |
| `location#heading`               | Number:Angle         | R          | Heading                                                                     |
| `location#last-update`           | DateTime             | R          | Time of the position                                                        |
| `control#lock`                   | Switch               | R/W        | ON locks, OFF unlocks (needs `pin`)                                         |
| `control#flash`                  | Switch               | W          | Flash the indicators                                                        |
| `control#honk-flash`             | Switch               | W          | Honk and flash                                                              |
| `control#wake-up`                | Switch               | W          | Ask the car for live data (uses the 12 V battery — use sparingly)           |

Remote commands are confirmed by the car, which can take up to a few minutes; the channel then reflects the real state.
A failed command is logged as a warning and the channel falls back to the reported state.

## Full Example

### `porscheconnect.things`

```java
Bridge porscheconnect:account:myporsche "Porsche ID" [ email="me@example.com", password="secret", refreshInterval=30 ] {
    Thing vehicle taycan "Taycan" [ vin="WP0ZZZY0123456789", pin="1234" ]
}
```

### `porscheconnect.items`

```java
Group                gTaycan          "Taycan"                       ["Car"]
Number:Dimensionless Taycan_SoC       "Battery [%d %%]"              (gTaycan) ["Measurement", "StateOfCharge"] { channel="porscheconnect:vehicle:myporsche:taycan:charge#soc" }
Number:Length        Taycan_Range     "Range [%.0f km]"              (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:range#electric" }
String               Taycan_Charging  "Charging [%s]"                (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:charge#status" }
Number:Dimensionless Taycan_Target    "Charge limit [%d %%]"         (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:charge#target-soc" }
Switch               Taycan_Climate   "Climatisation"                (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:climate#active" }
Number:Temperature   Taycan_ClimTemp  "Cabin temperature [%.1f °C]"  (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:climate#target-temperature" }
Switch               Taycan_Locked    "Locked"                       (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:control#lock" }
Location             Taycan_Position  "Position"                     (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:location#position" }
Image                Taycan_Picture   "Picture"                      (gTaycan) { channel="porscheconnect:vehicle:myporsche:taycan:status#image" }
Image                Porsche_Captcha  "Porsche captcha"              { channel="porscheconnect:account:myporsche:captcha" }
```

### `porscheconnect.sitemap`

```perl
sitemap porsche label="Porsche" {
    Frame label="Taycan" {
        Image     item=Taycan_Picture
        Text      item=Taycan_SoC
        Text      item=Taycan_Range
        Text      item=Taycan_Charging
        Setpoint  item=Taycan_Target minValue=25 maxValue=100 step=5
        Switch    item=Taycan_Climate
        Setpoint  item=Taycan_ClimTemp minValue=15 maxValue=30 step=0.5
        Switch    item=Taycan_Locked
        Mapview   item=Taycan_Position height=8
    }
}
```
