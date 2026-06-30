# AudioMoth Android Config Editor Implementation Plan

## Goal
Build a reliable Android AudioMoth configuration editor that:
- Detects and connects to AudioMoth over USB HID/vendor-specific interrupt interfaces
- Reads current device config first
- Lets the user edit config in-app
- Writes updated config back to device and verifies the write
- Handles disconnect/reconnect safely without stale UI state

## Milestones

### Milestone 1: Protocol and Transport Discovery
Status: Completed

Tasks:
- Confirm AudioMoth packet protocol from upstream references
- Probe both IF0/IF1 (HID/vendor-specific) interrupt transport paths
- Handle zero-only first responses and retry reads
- Add diagnostics for VID/PID, endpoints, and packet exchange

Deliverables:
- Stable device polling/packet exchange path
- Diagnostic logs suitable for field debugging

### Milestone 2: Core Config Editor Read/Write Flow
Status: Completed

Tasks:
- Remove obsolete chime export path
- Implement read-current-config via GET_APP_PACKET
- Decode packet into app config model
- Apply edited config via SET_APP_PACKET
- Verify write by checking response/echo
- Improve UI usability (home screen scroll, diagnostics usability)

Deliverables:
- Working read-edit-apply loop on real device
- Build marker and copyable debug log for easier support

### Milestone 3: Recovery and Negative-Path Hardening
Status: In Progress (compilation issues resolved; runtime validation pending)

Tasks:
- Register USB attach/detach lifecycle receiver
- Reset to safe UI state on disconnect (avoid stale edit state)
- Ensure reconnect path can reinitialize cleanly
- Validate behavior around permission revocation and missing device
- Keep diagnostics clear when device is removed/reinserted

Deliverables:
- Predictable recovery UX during unplug/replug
- No stale device/config state after detach

## Completed Items Summary
- HID polling and interface probing stabilized
- Zero-frame handling implemented
- Chime export path removed
- Config read/write packet flow implemented
- Home screen/navigation usability improved
- Kotlin scope/bracing errors fixed
- Full local validation passed (`assembleDebug`, `testDebugUnitTest`)

## Remaining Validation
- Device-level manual test pass for Milestone 3:
  - Start on Home, connect device, open Edit
  - Unplug device while in Edit
  - Confirm UI returns to Home and stale state clears
  - Reconnect and confirm read/apply works again

## Out of Scope (for now)
- Non-USB config export methods
- Feature expansion beyond current AudioMoth app packet coverage

## Notes
This plan reflects the current implementation direction agreed during the USB debugging and config-editor transition work.