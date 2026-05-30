his repository is a fork of the original [N0BOY/FT8CN](https://github.com/N0BOY/FT8CN/) with the following improvements:

1. The project now includes the original C++ source files, and you can choose whether to use the original `.so` libraries or build from source.
2. The app now supports exporting and importing QSO logs directly from the application.
3. The web server port can now be changed from the default 7050 to any other port in the range 1024 to 65535. 
4. Time synchronization with a second NTP server, ntp2.vniiftri.ru, has been added. Automatic synchronization on startup has also been added, followed by synchronization every 5 minutes. Timing update is synchronized with the end of the transmission phase.
5. Transmission is now blocked when the phone battery level drops below 1% to prevent the transceiver from hanging.
6. In the Calling window, display of the bearing to the transmitting station has been added. The calculation is based on the receiver and transmitter QTH values. The bearing is measured clockwise from 0° to 360°.
7. Added logging to the hrdlog.net server using ADIF format. API URL, API Key, Username, Password, and callsign settings have also been added, theoretically allowing logging to any server.
8. Displaying UTC.Delay offset of virtual time relative to precise UTC.
9. Auto TUNE on frequency change for ICOM transceivers is added
10. The Connect button and rig status have been added to the Settings window.
11. CQ button behavior repaired (If no messages type 2 to 6 then CQ is generated)
12. Log export and import is added
13. Passwords are now stored in secured repository
14. Busy windows are added in spectrum view
15. Azimuth bearing to station is added
16. SCAN mode is added but not working yet
17. Some error fixing and perfomance improvements


FT8CN — an app that runs FT8 natively on Android
Check https://github.com/R3BCK/FT8CN/blob/release/ft8cn/app/build/outputs/apk/release/app-release.apk to download the latest APK file.





**Thanks to:**  
Steve Franke (K9AN), Bill Somerville (G4WJS), and Joe Taylor (K1JT) proposed the FT8 and FT4 protocols. 
The “FT” stands for Franke and Taylor. Their paper, *The FT4 and FT8 Communication Protocols*, explains the design goals of FT4 and FT8 and the implementation details in WSJT-X, which served as the fundamental guide for this app.  
Karlis Goba (YL3JG) provided references for the implementation details in code.

Developed by BG7YOZ and hosted by N0BOY  
BG7YOY, for guidance on basic radio theory during the development of FT8CN, and for designing the app icon.  
BG4IGX, for practical guidance when I first started in amateur radio. You can find many of his teaching videos on Douyin.  
BD7MXN, for testing some radio connection controls and suggesting improvements.  
BH2RSJ, for helping establish an FT8CN test group and providing many valuable suggestions for testing and future improvements.  
BH7ACO, for helping solve the driver and related configuration parameters for a certain radio model.  
BG7IKK, for helping test radios that only support PTT transmission via RTS control.  
BI1NIZ, for helping register an account used for issue collection and FAQ features.  
BD3OOX and the Shijiazhuang Amateur Radio Club, for extracting callsign region mapping data for FT8CN from the JTDX Shijiazhuang edition, enabling callsign location to be mapped down to the provincial level in China.  
VR2UPU (BD7MJO), for guidance on FT8 development and usage experience, and for help with multilingual support.  
BA2BI, for help and guidance on amateur radio fundamentals and log handling for contacts.  
BI3QXJ, for professional guidance on the command set of a certain radio series.  
BG6TQD, for help testing the command set of a certain radio model.  
BG5CSS, for providing a radio model for testing.  
BG7YXN, for providing a radio model for testing.  
BG7YRB, for help with callsign rule calculations.  
BG8KAH, for providing equipment for testing.  
BA7LVG and JE6WUD, for completing the Japanese translation proofreading.  
BG6RI, for helping solve signal report issues in the log.  
SV1EEX, for completing the Greek and Spanish UI translations.  
VR2VRC, for helping correct historical callsign lookup rules.  
BA7NQ, for providing equipment for testing.  
BD7MYM, for guidance in testing a certain radio model.  
NØBOY, for helping provide the GitHub source and translation work.  
BG5JNT, for helping fix recognition of non-standard callsigns.  
BH3NEK, for assisting with testing a certain radio model.  
BG2ALB, for assisting with testing a certain radio model.  
BG6DRU, for assisting with testing a certain radio model.  
BG7NQF, for providing hidden commands for a certain radio model and performing compatibility tests on some devices.  
BH2VSQ, for assisting with testing a certain radio model.  
BG7YBW, for assisting with testing some features.  
BH1RNN, for assisting with testing some features.  
BG7BSM, for helping debug some bugs.  
BH4FTI, for finding and helping debug some bugs.  
BG8BXM (M Ge), for promoting the use of FT8CN; many of his tutorial videos are available on Douyin and Bilibili.  
BA7MFQ, for promoting the use of FT8CN and helping with testing.  
BG2EFX, for providing large-volume logs for testing.  
DS1UFX, for contributing the `(tr)uSDX audio over CAT` code.  
BG8HT, for providing a radio model for testing.  
UB6LUM, for helping solve operating mode settings for a certain radio model.  
BG5VLI, for contributing code to automatically upload logs to Cloudlog and QRZ.

**Disclaimer:**  
FT8CN is intended for research and learning purposes, including decoding and transmitting FT8 signals. The authors are not responsible for any consequences resulting from the user's use of this app.  
Please comply with local laws and regulations when using FT8CN.  
Considering the performance and battery limitations of mobile phones, signal processing uses lightweight computation rather than deep decoding and similar processing.  
If you have suggestions or questions, please submit them in “FAQ / Feedback”.
BG7YOZ  
2022-07-01


[Физический уровень] → [Протокол CI-V] → [Логика FT8CN] → [UI/ViewModel]
1. Пользователь нажимает "Connect" в UI
2. ConfigFragment → mainViewModel.toggleRigConnection()
3. MainViewModel.connectCableRig(context, port)
   ├─ Создаёт CableConnector
   ├─ Вызывает cableSerialPort.connect()
   │  ├─ Открывает /dev/ttyUSBx (или CH340)
   │  ├─ Настраивает: baudRate (19200/38400/115200), 8N1
   │  └─ Запускает SerialInputOutputManager в фоне
   └─ После успешного open() → onConnected() коллбэк
4. onConnected() → updateRigStatus() → UI: "Connected: USB Cable"
5. После onConnected() MainViewModel ждёт ~1 сек (Handler.postDelayed)
6. Вызывается setOperationBand():
   ├─ baseRig.setUsbModeToRig() → отправляет кадр режима (06 01 для USB)
   │  FE FE 94 E0 06 01 01 FD
   │  Ответ: FE FE E0 94 FB FD (OK)
   └─ baseRig.setFreq(GeneralVariables.band) → отправляет частоту
   Ответ: FE FE E0 94 FB FD

┌─ Приём (декодирование) ──────────────────────────────┐
│ 1. Каждый 15-сек цикл:                                │
│    ├─ Условие: UtcTimer.tick() % 15 == 0             │
│    ├─ hamRecorder.startRecord() → 14.77 сек аудио    │
│    ├─ FT8SignalListener.decode() → список сообщений  │
│    └─ Если есть сообщения → mutableFt8MessageList    │
│                                                      │
│ 2. Во время приёма (14.77 сек):                      │
│    ├─ Никаких команд на радио НЕ отправляется        │
│    ├─ Только чтение аудио из порта (если trUSDX)     │
│    └─ CI-V шина "молчит"                             │
└──────────────────────────────────────────────────────┘

┌─ Передача (12.64 сек) ───────────────────────────────┐
│ 1. За 100–200 мс до начала:                          │
│    ├─ baseRig.setPTT(true)                          │
│    │  FE FE 94 E0 1C 00 01 FD  (PTT ON)            │
│    │  Ответ: ~50–100 мс: FE FE E0 94 FB FD         │
│    └─ Запускается воспроизведение аудио              │
│                                                      │
│ 2. Во время передачи (12.64 сек):                    │
│    ├─ Аудио идёт в звуковую карту ИЛИ через CAT     │
│    ├─ CI-V шина:                                     │
│    │  • По умолчанию — молчит (безопасный режим)    │
│    │  • Если включен мониторинг — можно слать:     │
│    │    - 15 12 (запрос КСВ) → ответ: 12 <byte> FD │
│    │    - 15 13 (запрос ALC) → ответ: 13 <byte> FD │
│    │  • Частота/режим — НЕ менять!                 │
│    └─ Любая команда >1 раза в 500 мс = риск помех  │
│                                                      │
│ 3. После передачи:                                  │
│    ├─ baseRig.setPTT(false)                         │
│    │  FE FE 94 E0 1C 00 00 FD  (PTT OFF)          │
│    ├─ Если включён NTP-sync → UtcTimer.syncTime()  │
│    └─ Если включён TUNE on Freq Change →           │
│       baseRig.setTune(TUNER_START)                 │
│          FE FE 94 E0 1C 01 02 FD                  │
│          Ответ: ~100–300 мс: FE FE E0 94 FB FD    │
└──────────────────────────────────────────────────────┘

1. Нет ответа > 500 мс после отправки команды
2. SerialInputOutputManager ловит IOException / EIO
3. Вызывается onRunError("read failed: EIO")
4. BaseRigConnector.onRunError() → onRigStateChanged.onDisconnected()
5. MainViewModel: updateRigStatus() → UI: "Disconnected"

сбой
Причина,Механизм,Как проявляется
ВЧ-наводка на USB,Излучение антенны → наводка на экран кабеля → битые байты в UART,IOException: read failed: EIO в Logcat
Ground loop,Разность потенциалов земли радио и планшета → ток в экране → искажение логики,Периодические FA ответы или полная потеря связи
Перегрузка 5V линии,Радио потребляет >500 мА при TX → Android отключает порт по защите,USB device disconnected в системном логе

------------------------------------------------------------------------------------------------

[FT8CN           ] ←→ [Wi-Fi роутер] ←→ [ICOM-радио с включённым "CI-V over LAN"]
│                        │                      │
│                        │                      │
TcpClient (Socket)      TCP порт 50001        Встроенный CI-V сервер
(IComWifiConnector)     (настраивается        (меню радио:
в радио)              CI-V → IP Address/Port)

Отличие: В TCP нет стартовых/стоповых битов, нет контроля чётности. Только поток байтов. Поэтому важно:
Не терять границы кадров (ищем 0xFE 0xFE как начало, 0xFD как конец)
Обрабатывать фрагментацию (один read() может вернуть пол-кадра)

1. В настройках:
    - Connect Mode = NETWORK
    - Control Mode = CAT
    - CIV Address = 0x__
2. Пользователь нажимает "Connect" в диалоге (LoginIcomRadioDialog)
3. Передаётся: IP (например, 192.168.1.100), порт (50001 по умолчанию)
4. MainViewModel.connectWifiRig(wifiRig)
      ├─ Создаёт IComWifiConnector(controlMode, wifiRig)
      │  ├─ Вызывает super(controlMode, wifiRig) → WifiConnector
      │  ├─ Создаёт Socket: new Socket(ip, port)  // 50001
      │  ├─ Настраивает:
      │  │  • setSoTimeout(5000)  // таймаут чтения 5 сек
      │  │  • setTcpNoDelay(true) // отключаем Nagle для низкой задержки
      │  │  • Буферы: 8192 байт (по умолчанию)
      │  └─ Запускает фоновый поток чтения:
      │     while (connected) {
      │         int len = inputStream.read(buffer);
      │         if (len > 0) onReceivedCivData(buffer, len);
      │     }
      │
      ├─ После socket.connect() → onConnected() коллбэк
      └─ MainViewModel: updateRigStatus() → UI: "Connected: Network"
5. Задержка ~1 сек (Handler.postDelayed)
6. setOperationBand():
   ├─ setUsbModeToRig() → отправляет кадр режима
   │  Отправка: [FE FE 94 E0 06 01 01 FD]
   │  Ожидание ответа: до 200 мс
   │  Ответ: [FE FE E0 94 FB FD]
   │
   └─ setFreq(band) → отправляет частоту
   Отправка: [FE FE 94 E0 05 <5 байт> FD]
   Ответ: [FE FE E0 94 FB FD]
   ┌─ Приём (декодирование) ──────────────────────────────┐
   │ 1. Каждый 15-сек цикл:                                │
   │    ├─ hamRecorder.startRecord() → 14.77 сек аудио    │
   │    │  • В сетевом режиме: аудио НЕ записывается с    │
   │    │    микрофона, а принимается из сокета (если     │
   │    │    радио поддерживает audio over CI-V)         │
   │    │  • Для стандартного режима: аудио с микрофона  │
   │    │    планшета, радио только принимает команды    │
   │    │                                                 │
   │    ├─ Декодирование → список сообщений              │
   │    └─ Обновление UI                                  │
   │                                                      │
   │ 2. Во время приёма:                                  │
   │    ├─ Можно отправлять команды (частота, режим)     │
   │    ├─ Но не рекомендуется: радио может быть занято  │
   │    │  обработкой приёма                              │
   │    └─ Задержка ответа: 20–100 мс (сеть) + обработка │
   └──────────────────────────────────────────────────────┘

┌─ Передача (12.64 сек) ───────────────────────────────┐
│ 1. За 100–200 мс до начала:                          │
│    ├─ setPTT(true)                                  │
│    │  Отправка: [FE FE 94 E0 1C 00 01 FD]          │
│    │  Ожидание: 50–150 мс                           │
│    │  Ответ: [FE FE E0 94 FB FD]                    │
│    └─ Запуск воспроизведения аудио                  │
│       • Если audio over CAT: отправка аудио-пакетов │
│         в тот же сокет (или отдельный порт 50002)  │
│       • Если локальное аудио: звук в динамик,      │
│         радио принимает по эфиру                    │
│                                                      │
│ 2. Во время передачи:                                │
│    ├─ Аудио-поток: непрерывная отправка PCM-данных  │
│    │  • Формат: 16-bit little-endian, 48 kHz (обычно)│
│    │  • Размер пакета: 256–1024 байт (баланс задержки)│
│    │  • Интервал: каждые 5–20 мс                    │
│    │                                                 │
│    ├─ Управляющие команды:                           │
│    │  • Можно: 15 12 (КСВ), 15 13 (ALC) — редко     │
│    │  • Нельзя: менять частоту/режим (сорвёт цикл) │
│    │                                                 │
│    └─ Риск: если сеть "лагает", аудио-буфер         │
│       переполняется → радио обрезает передачу      │
│                                                      │
│ 3. После передачи:                                  │
│    ├─ setPTT(false)                                 │
│    │  [FE FE 94 E0 1C 00 00 FD]                   │
│    ├─ Если включён TUNE on Freq Change:            │
│    │  setTune(TUNER_START)                         │
│    │  [FE FE 94 E0 1C 01 02 FD]                   │
│    │  Ответ: ~100–300 мс                           │
│    └─ Если включён NTP-sync → UtcTimer.syncTime()  │
└──────────────────────────────────────────────────────┘

