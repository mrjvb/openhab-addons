# OnlyCat Binding

This binding integrates [OnlyCat](https://www.onlycat.com/) smart cat flaps.
It tells you which cat is at home, reports what happens at the flap, including prey detection, and lets you switch door policies and unlock the flap remotely.

The binding uses the OnlyCat cloud gateway, so it needs an internet connection.
Updates are pushed by the gateway as they happen.

## Supported Things

| Thing     | Type   | Description                                              |
|-----------|--------|----------------------------------------------------------|
| `account` | Bridge | The connection to an OnlyCat account                     |
| `flap`    | Thing  | An OnlyCat flap                                          |
| `cat`     | Thing  | A cat, known to all flaps of the account by its RFID code |

## Discovery

After you add an `account`, its flaps and the cats they have seen appear in the Inbox.
Cats that are hidden in the OnlyCat app are not offered.

## Thing Configuration

### `account` Bridge Configuration

| Name              | Type    | Description                                                                     | Default | Required | Advanced |
|-------------------|---------|---------------------------------------------------------------------------------|---------|----------|----------|
| `apiKey`          | text    | The API key                                                                     | N/A     | yes      | no       |
| `refreshInterval` | integer | Minutes between refreshes of door policies, cat names and reboot logs           | 10      | no       | yes      |
| `rebootLogHours`  | integer | Hours of reboot history to report                                               | 24      | no       | yes      |
| `detailedMetrics` | boolean | Also fetch telemetry metrics                                                    | false   | no       | yes      |

To create an API key, enable **Developer Mode** in the OnlyCat app under **Account**, then open **API Keys**.
The access of an API key cannot be restricted: it can do everything you can do in the app, so create a separate key for openHAB.

### `flap` Thing Configuration

| Name                      | Type    | Description                                                          | Default | Required | Advanced |
|---------------------------|---------|----------------------------------------------------------------------|---------|----------|----------|
| `deviceId`                | text    | The ID of the flap, such as `OC-8C1F64481234`                        | N/A     | yes      | no       |
| `ignoreFlapStateRules`    | boolean | Leave door policy rules on the flap state out of the `locked` channel | false   | no       | yes      |
| `ignoreMotionSensorRules` | boolean | Leave door policy rules on the motion sensors out of the `locked` channel | false   | no       | yes      |

### `cat` Thing Configuration

| Name       | Type | Description                        | Default | Required | Advanced |
|------------|------|------------------------------------|---------|----------|----------|
| `rfidCode` | text | The RFID code of the cat's microchip | N/A     | yes      | no       |

## Channels

### `flap` Channels

| Channel                  | Type     | Read/Write | Description                                                                            |
|--------------------------|----------|------------|----------------------------------------------------------------------------------------|
| `connected`              | Switch   | R          | Whether the flap is connected to the OnlyCat cloud                                     |
| `disconnect-reason`      | String   | R          | Why the flap lost its connection                                                       |
| `connectivity-changed`   | DateTime | R          | When the flap last connected or disconnected                                           |
| `locked`                 | Switch   | R          | Estimated lock state, see below                                                        |
| `door-policy`            | String   | RW         | The active door policy; send a policy name to activate it                              |
| `door-policy-definition` | String   | R          | The active door policy as JSON                                                         |
| `unlock`                 | Switch   | W          | Send `ON` to unlock the flap for a single passage                                      |
| `reboot`                 | Switch   | W          | Send `ON` to reboot the flap                                                           |
| `event-active`           | Switch   | R          | Whether an event is in progress                                                        |
| `event-id`               | Number   | R          | The ID of the latest event                                                             |
| `event-time`             | DateTime | R          | When the latest event started                                                          |
| `event-trigger`          | String   | R          | `MANUAL`, `REMOTE`, `INDOOR_MOTION` or `OUTDOOR_MOTION`                                |
| `event-classification`   | String   | R          | `UNKNOWN`, `CLEAR`, `SUSPICIOUS`, `CONTRABAND`, `HUMAN_ACTIVITY` or `REMOTE_UNLOCK`    |
| `event-rfid`             | String   | R          | Comma-separated RFID codes seen in the latest event                                    |
| `event-cat`              | String   | R          | Names of the cats seen in the latest event                                             |
| `event-direction`        | String   | R          | `INWARD` or `OUTWARD`                                                                  |
| `event-action`           | String   | R          | `TRANSIT`, `PEEK`, `DENY` or `BREACH`                                                  |
| `contraband`             | Switch   | R          | Whether the latest event was classified as contraband; stays `ON` until the next event |
| `human`                  | Switch   | R          | Whether the latest event was classified as human activity; stays `ON` until the next event |
| `image`                  | Image    | R          | A still frame of the latest event                                                      |
| `video-url`              | String   | R          | HLS playlist of the latest event; contains an access token                             |
| `error`                  | Switch   | R          | Whether the flap logged an error reboot in the configured period                       |
| `reboot-log`             | String   | R          | The reboots in the configured period as JSON                                           |
| `telemetry`              | String   | R          | The latest telemetry metrics as JSON, if `detailedMetrics` is enabled                  |

The gateway does not report the lock state, so the `locked` channel is estimated from the active door policy.
Outside of an event it follows the `idleLock` setting of the policy, so it does not reflect time-based rules or the `idleLockBattery` setting.
During an event, the first matching rule decides.
The channel is `UNDEF` if the deciding rule depends on something that is not known, such as an RFID code that has not been read yet.
The flap and motion sensor states are never reported, so a rule on those also gives `UNDEF`, unless you exclude these rules with `ignoreFlapStateRules` or `ignoreMotionSensorRules`.

A summary can change while OnlyCat analyses an event, for example when a passage turns out to be a peek.
The channels `event-direction` and `event-action`, and the `presence` channel of the cat, follow these corrections.

### `cat` Channels

| Channel          | Type     | Read/Write | Description                                                              |
|------------------|----------|------------|--------------------------------------------------------------------------|
| `presence`       | Switch   | RW         | `ON` if the cat is inside; send `ON` or `OFF` to correct it              |
| `last-seen`      | DateTime | R          | When the cat was last seen at a flap                                     |
| `last-flap`      | String   | R          | The ID of the flap that last saw the cat                                 |
| `last-direction` | String   | R          | The direction of its last movement at a flap                             |
| `last-action`    | String   | R          | What it did: `TRANSIT`, `PEEK`, `DENY` or `BREACH`                       |

A cat that leaves through an open window leaves the `presence` channel wrong.
A command to the channel corrects it in openHAB only, until the cat is next seen at a flap.
The correction survives a restart of openHAB.

## Actions

The `flap` Thing provides these actions in the scope `onlycat`:

| Action                           | Returns | Description                                                       |
|----------------------------------|---------|-------------------------------------------------------------------|
| `unlock()`                       | -       | Unlocks the flap for a single passage                             |
| `reboot()`                       | -       | Reboots the flap                                                  |
| `activateDoorPolicy(policy)`     | Boolean | Activates a door policy by name or ID                             |
| `getDoorPolicies()`              | String  | Returns all door policies of the flap as a JSON array             |
| `updateDoorPolicy(policyJson)`   | Boolean | Changes an existing door policy, given as an element of that array |

A door policy that is changed with `updateDoorPolicy` takes effect on the flap, so check it carefully: a mistake can lock your cat out.
The format of a policy is described in the [door policy schema](https://www.onlycat.com/door-policy-schema/).

## Full Example

### `onlycat.things` Example

```java
Bridge onlycat:account:home "OnlyCat" [ apiKey="oc_live_..." ] {
    Thing flap backdoor "Back Door Flap" [ deviceId="OC-8C1F64481234" ]
    Thing cat zorro "Zorro" [ rfidCode="900123456789012" ]
}
```

### `onlycat.items` Example

```java
Switch   Zorro_Home        "Zorro at home"  <presence> { channel="onlycat:cat:home:zorro:presence" }
DateTime Zorro_Last_Seen   "Last seen"      <time>     { channel="onlycat:cat:home:zorro:last-seen" }
String   Flap_Policy       "Door policy"               { channel="onlycat:flap:home:backdoor:door-policy" }
Switch   Flap_Unlock       "Unlock"                    { channel="onlycat:flap:home:backdoor:unlock", autoupdate="false" }
Switch   Flap_Contraband   "Prey detected"             { channel="onlycat:flap:home:backdoor:contraband" }
Image    Flap_Image        "Last event"                { channel="onlycat:flap:home:backdoor:image" }
```

### `onlycat.rules` Example

```java
rule "Keep the cats in after prey was detected"
when
    Item Flap_Contraband changed to ON
then
    Flap_Policy.sendCommand("Locked")
end
```
