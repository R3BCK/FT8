#ifndef ENCODE_DX_H
#define ENCODE_DX_H

#include <stdint.h>
#include <stdbool.h>
#include "constants.h"

#ifdef __cplusplus
extern "C" {
#endif

// [CHANGED] Сигнатура с параметром is_fox_multi
void ft8_encode(const uint8_t* payload, uint8_t* tones, bool is_fox_multi);
void ft4_encode(const uint8_t* payload, uint8_t* tones);

#ifdef __cplusplus
}
#endif

#endif // ENCODE_DX_H