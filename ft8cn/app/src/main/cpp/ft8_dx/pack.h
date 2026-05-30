#ifndef PACK_DX_H
#define PACK_DX_H

#include <stdint.h>
#include <stdbool.h>
#include "constants.h"  // Все константы уже здесь

#ifdef __cplusplus
extern "C" {
#endif

// [REM] УДАЛЁН #define FTX_LDPC_K_BYTES — он уже в constants.h

int32_t pack28(const char* callsign);
bool chkcall(const char* call, char* bc);
uint16_t packgrid(const char* grid4);
int pack77_1(const char* msg, uint8_t* b77);
void packtext77(const char* text, uint8_t* b77);
int pack77(const char* msg, uint8_t* c77);

// [NEW] Функция для упаковки мульти-сообщений Fox
int pack_fox_multi(const char calls_list[][14], int count, uint8_t *a91);

#ifdef __cplusplus
}
#endif

#endif // PACK_DX_H