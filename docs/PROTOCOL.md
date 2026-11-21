# PhoneControl wire protocol (v1)

- Transport: WebSocket, plain `ws://<agent-lan-ip>:8765/`
- Text frames: JSON control messages (UTF-8)
- Binary frames: video/config packets
- Byte order for binary headers: big endian

The controller is the WebSocket client, the agent is the server.

## 1. Handshake

Agent sends immediately after the WebSocket upgrade:

```json
{
  "type": "hello",
  "role": "agent",
  "protocol": 1,
  "name": "Google Pixel 8",
  "width": 1080,
  "height": 2400,
  "port": 8765
}
```

`width` / `height` are the **real display** dimensions used for touch mapping, not the scaled video resolution. The controller uses them for aspect ratio and displays them in the title bar.

The agent then sends the latest codec-config packet (if available) and requests a sync frame from the encoder. If the controller is not ready to decode yet, it can send `requestSync` after its SurfaceView is created.

## 2. Control messages

### 2.1 Touch

```json
{"type":"touch","phase":"down","id":0,"x":0.4321,"y":0.6180}
{"type":"touch","phase":"move","id":0,"x":0.4402,"y":0.6201}
{"type":"touch","phase":"up","id":0,"x":0.4410,"y":0.6210}
{"type":"touch","phase":"cancel","id":0,"x":0.4410,"y":0.6210}
```

- `phase`: `down`, `move`, `up`, `cancel`
- `x`, `y`: normalized to `[0.0, 1.0]` relative to the visible video area
- `id`: reserved for future multi-touch; currently always `0`
- The agent validates finite values and range 0..1.
- The agent buffers the whole gesture and injects it on `up`. Long press is inferred when the elapsed time is at least 550 ms and movement stays within a small slop.

### 2.2 Global actions

```json
{"type":"action","action":"back"}
{"type":"action","action":"home"}
{"type":"action","action":"recents"}
```

Implemented with `AccessibilityService.performGlobalAction`.

### 2.3 Sync / key frame

```json
{"type":"requestSync"}
{"type":"requestKeyframe"}
```

`requestSync` also re-sends the cached SPS/PPS config to the requesting client. Both ask the encoder for an IDR frame via `MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`.

### 2.4 Latency ping

```json
{"type":"ping","t":123456789}
{"type":"pong","t":123456789}
```

`t` is the controller's `SystemClock.elapsedRealtime()` at send time. The agent echoes the same value. The controller computes `now - t`, so the two devices do not need synchronized clocks.

### 2.5 Status and errors

```json
{"type":"status","sharing":true,"clients":1}
{"type":"error","message":"invalid touch"}
```

## 3. Binary packet

```text
offset  size  field
0       4     magic = 0x50435631 ("PCV1")
4       1     type: 1 = codec config, 2 = video access unit
5       1     flags: bit0 = key frame
6       2     width  (uint16, big endian)
8       2     height (uint16, big endian)
10      8     presentationTimeUs (int64, big endian)
18      ...   payload
```

### 3.1 Codec config (type 1)

Payload:

```text
uint8 nalCount
repeat nalCount:
  int32 nalLength
  byte[nalLength] raw NAL unit (without 00 00 01 / 00 00 00 01 start code)
```

The agent extracts SPS (NAL type 7) and PPS (NAL type 8) from MediaCodec's `csd-0` / `csd-1` or from codec-config output buffers. The controller builds:

```text
csd-0 = 00 00 00 01 + SPS
csd-1 = 00 00 00 01 + PPS
```

and configures `MediaCodec` decoder with the packet's width/height.

A config packet supersedes any queued frames on the controller. The controller clears its decode queue when it arrives.

### 3.2 Video frame (type 2)

Payload is one H.264 access unit as produced by the encoder, normally Annex-B with start codes. The controller prefixes `00 00 00 01` if the first NAL has no start code.

Key-frame packets also set `flags` bit 0, which the controller maps to `MediaCodec.BUFFER_FLAG_KEY_FRAME`.

The controller queue is bounded (90 items). When it overflows, it drops a frame and asks for `requestSync`, which re-sends config and requests an IDR. This bounds latency on slow devices.

## 4. Sequence

```text
Agent                                 Controller
  |  hello                              |
  |  codec-config                       |
  |  video key frame                    |
  |------------------------------------>|
  |                                     | SurfaceView created
  |<------------------------------------| requestSync
  |  codec-config                       |
  |  video key frame                    |
  |  video ...                          |
  |<------------------------------------| ping
  |------------------------------------>| pong
  |<------------------------------------| touch down/move/up
  |<------------------------------------| action back/home
```

## 5. Service discovery

- DNS-SD/mDNS service type: `_phonecontrol._tcp.`
- Instance name: `PhoneControl-<model>` (sanitized)
- Port: the WebSocket port (default 8765)
- Advertised by `NsdAdvertiser`, resolved by `NsdDiscovery`
- If discovery fails, the UI always allows manual `IP:port` entry.

## 6. Security notes

The protocol is intentionally simple and unauthenticated. It must only be used on a trusted LAN. Do not expose port 8765 to the internet. A production version should add a pairing secret, message authentication, and TLS (`wss://`), and reject unknown clients before sending video.
