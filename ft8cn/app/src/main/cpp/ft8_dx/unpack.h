#ifndef UNPACK_DX_H
#define UNPACK_DX_H

#include <stdint.h>
#include "decode.h"  // Включаем decode.h для message_t и hashCode

#ifdef __cplusplus
extern "C" {
#endif

// [REM] УДАЛЁН #define FTX_LDPC_K_BYTES — он уже в constants.h, который включается через decode.h

int unpack_callsign(uint32_t n28, uint8_t ip, uint8_t i3, char *result, hashCode * hash);
int unpack_type1_(const uint8_t *a77, message_t *message);
int unpack_text(const uint8_t *a71, char *text);
int unpack_telemetry(const uint8_t *a71, char *telemetry);
int unpack_nonstandard(const uint8_t *a77, message_t *message);
int unpack77_fields_(const uint8_t *a77, message_t *message);
int unpackToMessage_t(const uint8_t *a77, message_t *message);

// [NEW] Функция для распаковки мульти-сообщений Fox
int unpack_fox_multi(const uint8_t *a91, message_t *message);

#ifdef __cplusplus
}
#endif

#endif // UNPACK_DX_H