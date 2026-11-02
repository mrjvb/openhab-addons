# OnlyCat Binding

The OnlyCat binding connects OnlyCat smart cat flaps through the OnlyCat cloud using a developer API key.
It is based on the API client and device model used by the [OnlyCat Home Assistant integration](https://github.com/OnlyCatAI/onlycat-home-assistant).

## Supported Things

- `account`: Authenticated connection to the OnlyCat cloud.
- `device`: A smart cat flap discovered through the account bridge.

## Setup

In the OnlyCat app, enable Developer Mode under Account and create an API key for openHAB.
Add an `onlycat:account` bridge with that key, then scan the inbox for flaps.

The account connection uses Socket.IO over WebSocket and stays online to receive device updates.

## Channels

| Channel | Type | Description |
|---------|------|-------------|
| `connected` | Switch | Whether the flap is connected to the OnlyCat cloud |
| `description` | String | Device name from the OnlyCat app |
| `active-policy` | Number | Active transit policy identifier |

## Example

```java
Bridge onlycat:account:home "OnlyCat" [ apiKey="your-api-key" ]
```
