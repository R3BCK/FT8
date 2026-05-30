Disclaimer
FT8CN is designed for research purposes to learn how to decode and transmit FT8 signals. The developer is not responsible for any consequences resulting from the user's operation of this application.
When using FT8CN within the People's Republic of China, please comply with relevant regulations such as the "Regulations on Radio Management of the People's Republic of China".
Considering the performance and battery life limitations of mobile devices, signal processing uses lightweight operations without deep decoding or other intensive processing.
If you have good suggestions or questions, please submit them via "FAQ / Report Issues".
BG7YOZ
2022-07-01
Changelog
2025-01-04 (0.93)
Fixed calculation error in transmit supervision timer.
Fixed issue where first log entry was missing when downloading log data.
Added log data sharing feature.
Added automatic log upload to QRZ and CloudLog (code contributed by SydneyOwl).
Added SWR and ALC alarm switches.
2024-01-22 (0.92)
Added QSO confirmation indicator for messages in waterfall display.
Added support for new radio models.
Added serial port parameter settings.
Added support for all FT8 message types in decoded messages.
Fixed issue where serial port error messages were displayed only in Chinese.
Improved SWL QSO logging.
Improved QSO handling for messages containing callsigns with /P or /R suffixes.
2023-09-14 (0.91 patch 1)
Fixed USB-DATA mode selection error for Yaesu FT-891/991.
2023-09-11 (0.91)
Fixed issue where RR73 was incorrectly sent as 73 when transmitting non-standard messages (i3=4).
Fixed issue where messages decoded in previous cycle could not be replied to in time, and 73 could be missed in multi-decode mode.
Fixed issue where sender callsign might be incorrect when generating messages with both parties using compound callsigns.
Optimized automatic program logic.
Added (tr)uSDX Audio over CAT feature (code contributed by DS1UFX).
Added WiFi mode support for XieGu (CoGu) X6100 (firmware version 1.1.7; transmit audio not yet resolved).
Added support for Kenwood TS-570D.
Added USB-DATA mode for Yaesu FT-891/991.
Added one-click QSO log query for callsigns in message list.
Added simplified display mode for message list.
2023-08-13 (0.90)
Added web interface interactive mode for log import.
Fixed map crash issue when log data volume is too large.
Optimized database structure to improve log import and update speed (backup logs before updating this version to prevent data loss).
Fixed some spelling errors.
Added support for UA3REO Wolf SDR radio.
Added support for GUOHE (Guohe) PMR-171 radio.
2023-07-08 (0.89)
Added multi-decode feature; in multi-decode mode, increases decode depth to attempt decoding overlapping signals.
Fixed issue where worked zones were not updated promptly after importing ADI files.
Fixed issue where transmit audio could become distorted on ICOM radios in network mode.
Fixed issue where RR73 could freeze in certain situations.
Improved decode stability.
Fixed issue where comment field in decoded messages sometimes displayed incorrectly.
Modified export log prompts.
2023-05-02 (0.88 Patch 2)
Added audio output settings (bit depth, sample rate).
Added conditional query and export for logs.
Modified log query to display results in descending time order.
Fixed issue with duplicate SWL QSO records.
Optimized background UI for major browsers.
Patch 2:
Fixed crash issue when locating entries in "QSO Log".
2023-03-24 (0.87)
Added map location display feature for queried QSO log results.
Added FlexRadio instrument display and parameter settings (transmit not currently supported).
Added automatic time synchronization feature (server: Microsoft NTP).
Added SWL mode with save and export functions for decoded messages and QSOs (SWL QSO recognition criteria: must have reports from both parties, plus closing 73, RR73, or RRR).
Enhanced background data query functions.
Fixed distance calculation error in "Callsign-Grid Mapping Table" background query.
Adjusted radio model options for future new firmware of XieGu G90S.
Fixed UI lag issue when many decoded messages are present.
Optimized log query performance.
2023-02-06 (0.86)
Improved robustness of log import with feedback for malformed log entries.
Fixed occasional crash issue caused by array index out of bounds during SNR calculation.
Fixed inaccurate count calculation after log import.
2023-01-28 (0.85)
Added excluded callsign prefix feature (excluded prefixes have highest priority, filtering out unwanted calls in automatic mode).
Added sunrise/sunset gray line to GridTracker map.
Added function to clear followed callsign list.
Added function to clear cached QSO messages.
Added call modifier function. Examples: CQ POTA xxxxxx xxxx, or CQ DX xxxxxx xxxx. Modifier range: 000-999, A-Z, AA-ZZ, AAA-ZZZ, AAAA-ZZZZ.
2023-01-08 (0.84)
Optimized map color scheme.
Fixed crash issue caused by thread synchronization problems in prompt message operations.
Fixed log import failure caused by non-standard field descriptions in some logs.
Optimized callsign hash table processing.
2023-01-07 (0.83)
Added free text transmit function.
Fixed crash issue caused by Execute-only memory violation error.
Fixed settings error for certain radio models.
Modified log import/export operations; added confirmation field to exported logs, with automatic confirmation item update on import.
Fixed multiple memory leak points.
Fixed inaccurate grid information for counterpart in some QSO logs.
Fixed issue where screen would turn off when staying on Grid Tracker interface for extended periods.
2022-12-31 (0.8.1)
Goodbye, 2022! May tomorrow be better!
Important Notes:
This version has updated database structure. Please export and backup logs before upgrading; downgrade to older versions will not be possible after upgrade.
This version begins supporting ICOM network control function. Recommended: connect radio WiFi to phone AP (preferred), or phone to radio AP.
Not recommended to use router for connection; if router performance is insufficient, transmit audio packet loss may occur!
Added network (WiFi) support for ICOM series radios.
Added SWR and ALC value over-limit warning for ICOM series radios.
Added SWR and ALC value over-limit warning for Kenwood TS series radios.
Added SWR and ALC value over-limit warning for YAESU series radios.
Added SWR and ALC value over-limit warning for Elecraft series radios.
Added automatic switching of connector Data mode for some ICOM radios under different connection methods.
Added signal strength adjustment function for ALC tuning.
Added support for calling 3-character callsigns.
Added support for additional radio models.
Added entry point to get latest version: https://github.com/N0BOY/FT8CN/releases
Added callsign-grid mapping table (database upgrade).
Added map visualization feature (similar to GridTracker).
Added ability to call from within the map.
Fixed crash issue on some devices caused by memory jitter during audio data processing.
Fixed inaccurate QTH information for some messages.
Fixed issue where zone icons incorrectly displayed on transmit entries in certain situations.
Fixed crash issue when transmitting messages containing 3-character callsigns.
Optimized spectrum display; fixed abnormal text display on low-resolution screens.
Updated coordinates for some regions.
Optimized message list processing strategy to reduce memory jitter.
Fixed issue where prompt message did not update after switching target callsign upon reaching no-reply threshold.
Fixed issue where geographic location could not be resolved for some non-standard callsigns.
Fixed abnormal crash issue caused by memory access mechanisms in high-version Android and ARM64.
2022-11-08 (0.79)
Changed XieGu X6100 operation mode to U-DIG mode.
Changed audio data format from 16-bit integer to 32-bit float mode.
Fixed memory leak issue in FFT process.
Added network connection mode support for Flex-6000 series (receive only, transmit not currently supported).
Added prevention of screen lock/sleep.
Limited historical message count (temporarily set to within 3000 entries).
Added full-screen mode.
Added quick frequency switching.
Fixed crash issue on some radios (ICOM, XieGu) caused by poor data transmission quality.
Fixed recognition error for non-standard callsigns shorter than 6 characters.
Known Issues:
Flex radio connection only works within same network segment; direct IP input connection method not yet added.
2022-11-18 (0.79 Patch 4)
Fixed issue where decode button was unresponsive on some devices.
Added direct IP input connection method for Flex radios, resolving cross-network segment connection issues.
2022-10-06 (0.78)
Continued optimization of automatic program logic; fixed issue where target was not focused when auto-call was enabled.
Added confirmation dialog for log deletion actions.
2022-10-01 (0.77)
Fixed case-sensitivity issue in band statistics.
Callsigns previously worked but not on current band are now displayed in blue font.
Added new radio models.
2022-09-24 (0.76)
Adjusted historical QSO callsign rules to distinguish by band (wavelength).
Fixed error where transmit supervision counter would auto-decrement.
Continued fixing inaccurate signal report issue in logs.
Continued optimizing automatic program strategy.
2022-09-17 (0.75)
Continued fixing signal report issues in QSO logs (reversed reports, inaccurate values).
Added Bluetooth connection permission request for Android 12.
Enabled delayed command transmission for certain radio models with slow USB command response.
Changed YAESU FT450D operation mode to USER-U mode.
Continued optimizing automatic program; adjusted operational mechanism, moved automatic log recording earlier.
Automatically closes PTT when exiting application if in transmit state.
Fixed duplicate message issue for hashed callsigns caused by oversampling.
Added Japanese, Greek, and Spanish UI support.
Fixed error where followed messages on different frequencies would trigger auto-call.
2022-09-09 (0.74)
Added English version help documentation.
Callsign query results now displayed in descending time order.
Changed ICOM radio operation mode to USB-D mode.
Added QRZ query function for callsigns.
Fixed imprecise signal report values in logs.
2022-09-03 (0.73)
Fixed issue with inaccurate start times for some logs.
Optimized annotation for previously worked zones.
Based on message history, added distance annotation for messages without grid reports.
2022-08-28 (0.72)
Fixed issue in automatic program where app would call itself.
Differentiated successfully worked callsigns by QSO frequency.
Enriched content of background "Track Operation Information".
Re-added callsign query list in QSO log and adjusted displayed content.
Fixed crash issue caused by array index overflow.
Reduced permission requests; removed storage permission, retained microphone and location permissions (can be denied).
Fixed crash issue caused by missing microphone permission.
2022-08-27 (0.71)
Optimized PTT-on duration during transmit cycle to ensure complete receive message cycle.
Fixed Bluetooth transmit/receive audio adaptation for Q900, enabling true Bluetooth control and audio capability.
Beautified zone annotation in messages.
Added support for new radio models.
Fixed issue where message list would not auto-scroll up after new messages arrived.
2022-08-22 (0.7)
Added DXCC zone statistics.
Added ITU zone statistics.
Added CQ zone statistics.
Added distance statistics per band.
Added annotation for callsigns in unworked DXCC, ITU, CQ zones.
Fixed inaccurate calculation for callsigns with 1-letter prefix and 2-digit suffix.
2022-08-13 (0.63)
Fixed recognition criteria for non-standard callsigns; resolved calculation errors for some non-standard callsigns.
Continued layout optimizations (especially landscape mode).
Added traditional Chinese location information.
2022-08-11 (0.62)
Changed FT-817/818 series operation mode from USB to DIGI mode.
Added echo of transmitted messages to call bar.
Fixed crash issue on some devices when manually interrupting transmit.
Fixed crash issue when my callsign was empty during transmit.
Fixed control issue for certain radio models.
Added English language pack.
Optimized layout.
2022-08-06 (0.6)
Refactored radio-related low-level architecture to support multiple radio models.
Completed command sets for Guohe, YAESU, and KENWOOD partial models.
Completed control function via Bluetooth serial port (SPP mode).
Implemented Bluetooth audio capture.
Modified rules to prevent calling oneself.
Added support for non-standard and compound callsigns.
Added feature to submit transmit message to call list if no audio is captured during transmit.
2022-07-17 (0.51)
With help from BA2BI, fixed incorrect band wavelength issue.
Fixed duplicate content in carrier band list on settings page.
Fixed DTR transmit issue.
Added saving of radio frequency value after frequency change; if QSO successful, use radio frequency as reference.
Added protection for WSPR-2 frequencies; transmit is disabled when radio frequency is within WSPR-2 range.
Fixed issue where counterpart callsign lacked grid information in v0.5 logs.
Fixed issue where auto-followed CQ targets were not auto-called in v0.5.
Fixed issue where followed callsigns could not be deleted in background.
Added progress bars for transmit and receive.
Added synchronization for log import/export with automatic LoTW confirmation.
Added manual confirmation.
Added radio PTT response delay setting.
Added quick call feature via left-swipe in message list (effective within first 2.5 seconds of cycle).
Added "Today's Log" option to log export.
Fixed issue where callsigns with slashes could not be deleted.
Added simple filter function to QSO log query.
2022-07-10 (0.5)
This is a major update. Improved automatic program, added log query and export functions. At this point, the app basically completes capabilities for QSO operation.
Additional changes:
Fixed text overlap issue in waterfall display.
Added radio support and baud rates.
Fixed crash on startup when location permission was not granted.
Added DTR support.
Fixed various minor bugs discovered during testing.
Added supervision for automatic transmit.
Added auto-follow CQ switch.
Added auto-call followed callsigns switch.
Added annotation for messages with excessive time offset.
Known Issues:
If counterpart calls me starting from the second message, the saved log lacks their grid information, though grid info exists in message context.
If auto-follow CQ messages is enabled with auto-reply to followed callsigns, CQ messages are not replied to.
The above issues will be resolved in the next version.
2022-07-02 (0.44)
Added entry point for issue collection and feedback.
Fixed crash bug on settings page.
Added x5105 to device list.
2022-07-01 (0.43)
With help from BG7IKK, fixed RTS-controlled PTT issue for some radios.
BI1NIZ registered an account for project issue collection, feedback, and FAQ.
Added red marker for transmit frequency on spectrum scale.
2022-06-30 (0.42)
BH7ACO helped resolve driver for XieGu X6100. (Unresolved issue: X6100 sometimes disconnects unexpectedly; workaround: set 1-second delay for SSB mode commands; solution not ideal).
2022-06-29 (0.41)
Confirmed successful control testing for IC-705, IC-7100, IC-7300.
BH2RSJ helped establish an app testing group; members are providing usage feedback and modification suggestions.
Modified startup method to ensure configuration parameters are read in on time.
Fixed error where changing radio frequency would incorrectly change filter to FIL2.
2022-06-27
Added radio CAT control function; currently supports partial ICOM series radios. Testing successful only on IC-705; other ICOM models not available for testing, unclear if serial drivers are recognized.
Compiled list of ICOM radios supporting CI-V command control, with default addresses for each model.
2022-06-20
Added help function.
Added marking function for waterfall display.
Added adaptations for dark mode on Android 10 and above.
Updated app icon (designed by BG7YOY).
Acknowledgments
With Respect:
Steve Franke (K9AN), Bill Somerville (G4WJS), Joe Taylor (K1JT): Proposed the FT8 and FT4 protocols (FT from Franke and Taylor initials), and detailed the design rationale and WSJT-X implementation details of FT4 and FT8 in the paper "The FT4 and FT8 Communication Protocols", which became the fundamental guide for completing this app.
Karlis Goba (YL3JG): Provided reference for specific code implementation.
Special Thanks:
BG7YOY: Provided guidance on fundamental radio theory during FT8CN development, and designed the FT8CN icon.
BG4IGX: Provided practical guidance when I first entered amateur radio. Many of his tutorial videos can be found on Douyin.
BD7MXN: Helped test connection control for some radios and provided improvement suggestions.
BH2RSJ: Helped establish an FT8CN testing group, providing many valuable opinions for testing and subsequent improvements.
BH7ACO: Helped resolve driver and related configuration parameters for a certain radio.
BG7IKK: Helped resolve testing for radios supporting only RTS-controlled PTT transmit.
BI1NIZ: Helped register account for collecting issue feedback and FAQ functionality.
BD3OOX and Shijiazhuang Amateur Radio Club: FT8CN callsign region attribution data extracted from JTDX Shijiazhuang edition, enabling callsign location precision to Chinese provincial level.
VR2UPU (BD7MJO): Provided guidance on FT8 development and usage experience, and assisted with multi-language support.
BA2BI: Provided help and guidance on amateur radio fundamentals and QSO log processing.
BI3QXJ: Provided professional guidance on command sets for a certain brand series of radios.
BG6TQD: Assisted with command set testing for a certain radio model.
BG5CSS: Provided a certain radio model for testing.
BG7YXN: Provided a certain radio model for testing.
BG7YRB: Assisted with callsign rule calculations.
BG8KAH: Provided equipment for testing.
BA7LVG: Completed Japanese translation proofreading.
JE6WUD: Completed Japanese translation proofreading.
BG6RI: Helped resolve signal report issues in logs.
SV1EEX: Completed Greek and Spanish UI translation work.
VR2VRC: Helped correct historical callsign reading rules.
BA7NQ: Provided equipment for testing.
BD7MYM: Provided guidance for testing a certain radio model.
N0BOY: Helped provide Github repository and translation work.
BG5JNT: Helped fix non-standard callsign recognition issues.
BH3NEK: Assisted with testing for a certain radio model.
BG2ALB: Assisted with testing for a certain radio model.
BG6DRU: Assisted with testing for a certain radio model.
BG7NQF: Provided hidden commands for a certain radio model for compatibility testing.
BH2VSQ: Assisted with testing for a certain radio model.
BG7YBW: Assisted with testing for some features.
BH1RNN: Assisted with testing for some features.
BG7BSM: Assisted with debugging some bugs.
BH4FTI: Discovered and assisted with debugging some bugs.
BG8BXM (Brother M): Promoted FT8CN usage; many tutorial videos on Douyin and Bilibili.
BG7MFQ: Promoted FT8CN usage and assisted with testing.
BG2EFX: Provided large-volume logs for testing.
DS1UFX: Contributed (tr)uSDX audio over CAT code.
BG8HT: Provided a certain radio model for testing.
UB6LUM: Helped resolve operation mode settings for a certain radio model.
SydneyOwl: Provided code for uploading logs to QRZ and CloudLog.