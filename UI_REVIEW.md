# Timekeeper 0.7.1: timer UI review

Reviewed 29 October 2026. Scope: the supplied Digital Wellbeing Set timer screenshot, the current Compose implementation, and official Samsung/Android guidance. This review led to the changes in 0.7.1; device validation is still pending.

## Assessment

The 0.7.0 interface followed some One UI principles, particularly bottom-aligned choices and restrained colors. Its picker contents were inconsistent: wrapping three-letter day chips, oversized independent preset actions, scrolling confirmation buttons, prominent per-column selection boxes, and a cooldown hours wheel ending at 168. The result did not capture the orderly grouping and clear selected values in the supplied screenshot.

The screenshot is an observed reference, not a public component specification. Its useful features are circular weekday selectors, a strong central timer value with subdued neighbouring values, clear grouping, and consistent bottom actions. Its exact spacing, Samsung-internal widgets and implementation are not available from the screenshot.

Samsung's current public developer portal documents general One UI behavior. The materials reviewed do not establish a separate, complete phone-component specification for One UI 9. A One UI 9 Watch design story is a wearable reference and was not used as a phone-dialog specification. These changes remain a custom Compose implementation; they are not Samsung Health widgets or a claim of pixel-identical system rendering.

## Findings and resulting changes

| Area | Evidence or design reasoning | Change in 0.7.1 |
| --- | --- | --- |
| Modal placement | Samsung puts choice/confirmation dialogs near the bottom; informational progress dialogs can be centered [1]. The existing placement was appropriate. | Retain bottom-aligned choice dialogs, safe system insets and outside/Back dismissal. A drag handle is not added to a non-draggable choice dialog. |
| Dialog hierarchy | Consistent controls and clear routes back are Samsung principles [2]. Independent body scrolling is our implementation decision to keep confirmation reachable. | Fixed title and Cancel/Done row for timer pickers; the body alone scrolls. Shared dividers, typography and margins. |
| Button emphasis | Samsung recommends flat actions in dialogs to avoid unnecessary visual layers [3]. | Matching flat Cancel/Done actions, separated by a subtle divider. No extra full-width action for switching picker mode. |
| Repeat presets | Android documents segmented controls for side-by-side single selection [4]. These three presets are mutually exclusive shortcuts. | One joined Every day / Weekdays / Weekends row, with one selected preset when its mask matches. A custom day combination can match none. |
| Weekday selection | Seven independent selections are different from three exclusive presets. The circular visual comes from the supplied screenshot; checkbox semantics and non-overlapping targets are accessibility decisions [5]. | Circular short weekday labels with full weekday names exposed to accessibility. Selected days have an accent outline and light tint. Locale controls display order; stored day bits are unchanged. |
| Narrow windows / text scale | Samsung calls for text scaling to 200% without losing functionality [6]. Android recommends targets of at least 48 by 48 dp [5]. Seven 48 dp targets require 336 dp. | Keep a seven-day row where it fits. Use deliberately balanced rows on narrow or enlarged layouts. Preserve single-line preset labels with horizontal overflow instead of squeezing type or randomly wrapping controls. |
| Timer values | The supplied screenshot gives the selected number stronger emphasis than adjacent values. Exact sizes here are our design judgment. | Selected values use 36 sp medium-weight text; neighbours are smaller and subdued. Remove individual blue selection rectangles. A two-column picker includes a colon. |
| Duration versus clock time | A cooldown is an elapsed quantity; a reset time is a time of day. The previous maximum, 168 hours, equals seven days. No Samsung rule requires that maximum. | Cooldown uses Days / Hours / Minutes. Retain the seven-day product limit and all existing data. Hours are a remainder below 24, with dynamic constraints at the maximum. Reset remains a clock-time picker; daily allowances retain their Full day option. |
| Alternative input | Android's picker examples support switching input modes within a dialog [7]. Samsung also asks for alternatives to complex gestures and clear validation [2]. | Compact Keypad/Wheels switch in the header. Label every input, support Next/Done IME actions, keep errors visible, and retain accessible wheel adjustment semantics. |
| Cancel behavior | A dialog with an explicit Cancel action should not silently accept its tentative selection. This is an interaction decision consistent with the documented confirm/dismiss pattern [7]. | Repeat days are staged until Done. Cancel, outside tap and Back retain the previous editor selection. The editor's Save still persists the whole timer. |
| Summary language | Show a duration in units a user can readily understand; an implementation maximum should not look like a clock hour. | Show 7 days rather than 168 hr in summaries, and Off for a zero cooldown in the editor. |
| Dashboard copy | The user asked to remove the screen-time and chart footer; these explanations were not needed for the dashboard's main task. | Remove both paragraphs. Usage accounting and chart periods are unchanged. |

## Interaction and layout decisions

At a 411 dp window width, dialog margins and body padding leave about 355 dp, enough for seven 48 dp weekday targets. At smaller widths the layout intentionally reflows into balanced groups rather than reducing targets. Circles grow when text grows. This is a constraint check, not a claim that a real Galaxy screen was rendered or inspected.

The preset strip measures its labels at the current font scale. It expands horizontally when the three labels cannot fit. Duration columns similarly retain room for their labels and numbers at larger sizes. The fixed dialog actions remain available while content scrolls or the keyboard reduces available height.

The three presets write the same day masks as manual selection. At least one day remains required. A cooldown of 7 days permits 00 hours and 00 minutes only. All 10,081 supported whole-minute cooldown values convert to the new fields and back without loss. No cooldown is shortened, and no existing backup format changes in this release.

Keeping repeat days in a separate editor preserves the current settings flow. Combining the entire timer into one Set timer page like the reference is a possible later product change; this patch does not claim to reproduce that complete screen.

## Validation and limits

All current production sources compile. All 86 JVM tests pass: 68 core and 18 app host tests. New tests cover round-trip preservation of all supported cooldowns, the exact seven-day boundary, 00–23 hour remainders, empty/out-of-range input, and partially bounded last days. Android lint reports zero errors and 16 existing warnings. APK version/code, original signing certificate, resource IDs, alignment, ZIP integrity and exclusion of test classes were checked.

No booted emulator or attached phone was available for this release's UI check; the previous isolated emulator attempt exited before Android boot. Compilation and lint do not validate visual polish, gesture feel, keyboard placement, or screen-reader traversal. The following still need a phone check:

- Default and 200% font size, increased display size, portrait, landscape and split-screen.
- Seven-day layout at the actual Galaxy settings; deliberate reflow on a narrow window; one-line preset labels.
- TalkBack: full day names, selected states, heading announcement, wheel adjustment, focus order and input errors.
- Cancel/Done, outside tap and Back; rotation with tentative changes; editor Save after Done.
- Hours/minutes wrapping, keypad Next/Done, keyboard visibility, Full day, zero cooldown, and 6 days 23 hours 59 minutes → 7 days.
- Both light and dark appearance, including larger system fonts.

## Official sources

1. [Samsung One UI: Dialog](https://developer.samsung.com/one-ui/comp/dialog.html)
2. [Samsung One UI: Interaction and control](https://developer.samsung.com/one-ui/accessibility/interaction-and-control.html)
3. [Samsung One UI: Buttons](https://developer.samsung.com/one-ui/comp/button.html)
4. [Android Developers: Segmented button](https://developer.android.com/develop/ui/compose/components/segmented-button)
5. [Android Developers: Accessibility API defaults and touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)
6. [Samsung One UI: Layout and typography](https://developer.samsung.com/one-ui/accessibility/layout-and-typo.html)
7. [Android Developers: Dialogs for time pickers](https://developer.android.com/develop/ui/compose/components/time-pickers-dialogs)
8. [Samsung One UI overview](https://developer.samsung.com/one-ui/index.html)
9. [Android Developers: Time pickers](https://developer.android.com/develop/ui/compose/components/time-pickers)
10. [Android Developers: Bottom sheets](https://developer.android.com/develop/ui/compose/components/bottom-sheets)

The sources support the interaction principles above. The exact circle treatment, spacing, fixed action layout, seven-day product maximum and numerical typography are implementation/design judgments for Timekeeper.
