---
name: ancs-reference
description: Verified facts from Apple's Apple Notification Center Service (ANCS) specification, plus the list of details still unverified. Use before writing or changing any ANCS client code (GATT discovery, Notification Source / Data Source parsing, Control Point commands, notification actions).
---

# ANCS facts — verified against Apple's spec

Sources (re-check before relying on anything not listed here):
- https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Introduction/Introduction.html
- https://developer.apple.com/library/archive/documentation/CoreBluetooth/Reference/AppleNotificationCenterServiceSpecification/Specification/Specification.html

## Roles
- iOS device = Notification Provider (NP); accessory (our watch) = Notification Consumer (NC).
- The spec implies the accessory is the GATT client ("An accessory acting as a GATT client is free to access and use other services provided by the iOS device while using the ANCS").

## UUIDs
| Item | UUID | Properties |
|---|---|---|
| ANCS service (primary) | `7905F431-B5CE-4E99-A40F-4B1E122D00D0` | |
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | notifiable |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | writeable with response |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | notifiable |

## Encoding and access
- All numeric values are little endian; all strings are UTF-8.
- "All these characteristics require authorization for access."

## Control Point commands
- `0` Get Notification Attributes (`CommandIDGetNotificationAttributes`)
- `1` Get App Attributes (`CommandIDGetAppAttributes`); the AppIdentifier must be NUL-terminated
- `2` Perform Notification Action (`CommandIDPerformNotificationAction`): byte 1 CommandID, bytes 2-5 NotificationUID (32-bit), byte 6 ActionID

## Control Point error codes
`0xA0` unknown command, `0xA1` invalid command, `0xA2` invalid parameter, `0xA3` action failed.

## Enumerations (Appendix) — verified
- EventID: Added 0, Modified 1, Removed 2.
- EventFlags (bitmask): Silent 1<<0, Important 1<<1, PreExisting 1<<2, PositiveAction 1<<3, NegativeAction 1<<4.
- CategoryID: Other 0, IncomingCall 1, MissedCall 2, Voicemail 3, Social 4, Schedule 5, Email 6, News 7, HealthAndFitness 8, BusinessAndFinance 9, Location 10, Entertainment 11.
- NotificationAttributeID: AppIdentifier 0, Title 1*, Subtitle 2*, Message 3*, MessageSize 4, Date 5, PositiveActionLabel 6, NegativeActionLabel 7 (* = followed by a 2-byte max length in the request).
- ActionID: Positive 0, Negative 1. AppAttributeID: DisplayName 0.

## Protocol behavior — verified from the spec text
- Get Notification Attributes response: CommandID(0), NotificationUID(4), then (AttributeID(1), Length(2, 16-bit), Data) tuples in the order requested; empty/missing attribute = length 0; data is not NUL terminated.
- Responses larger than the negotiated MTU are split into fragments; the client splices them; complete when every requested tuple has arrived.
- Perform Notification Action produces no Data Source data, success or failure. Errors surface as ATT error codes on the Control Point write.
- Action availability is signalled by the PositiveAction / NegativeAction event flags.
- "GATT notifications may be delivered as soon as the NC subscribes to the Notification Source" — be ready first (so subscribe Data Source first).
- A session starts when the NC subscribes to Notification Source and ends on unsubscribe/disconnect; UIDs are valid only within a session. On a new session the NP "does its best" to report existing notifications (PreExisting flag).
- The call "actions" only tell the iPhone to answer/decline; the spec has no audio, so call audio stays on the iPhone.

## Still NOT verified
- Notification Source packet byte sizes (the spec shows them only in a figure; the code assumes 8 bytes = 1+1+1+1+4 and logs any other length).
- Endianness of the 16-bit attribute length (the spec says numerics are little endian unless stated otherwise; unit tests assume that).
- Who advertises / initiates the BLE connection (spec silent), bonding/encryption details, and what the iPhone user must enable ("Share System Notifications" in iPhone Bluetooth settings is only a hint from forum text).
- Everything about the Apple Media Service (AMS) — Phase 2 needs its own verification pass.
