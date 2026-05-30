#include "encode.h"
#include "constants.h"
#include "crc.h"
#include <stdio.h>
#include "../common/debug.h"

static uint8_t parity8(uint8_t x) {
    x ^= x >> 4;
    x ^= x >> 2;
    x ^= x >> 1;
    return x % 2;
}

static void encode174(const uint8_t* message, uint8_t* codeword) {
    for (int j = 0; j < FTX_LDPC_N_BYTES; ++j) {
        codeword[j] = (j < FTX_LDPC_K_BYTES) ? message[j] : 0;
    }

    uint8_t col_mask = (0x80u >> (FTX_LDPC_K % 8u));
    uint8_t col_idx = FTX_LDPC_K_BYTES - 1;

    for (int i = 0; i < FTX_LDPC_M; ++i) {
        uint8_t nsum = 0;
        for (int j = 0; j < FTX_LDPC_K_BYTES; ++j) {
            uint8_t bits = message[j] & kFTXLDPCGenerator[i][j];
            nsum ^= parity8(bits);
        }
        if (nsum % 2) {
            codeword[col_idx] |= col_mask;
        }
        col_mask >>= 1;
        if (col_mask == 0) {
            col_mask = 0x80u;
            ++col_idx;
        }
    }
}

// [NEW] Добавлен параметр is_fox_multi
void ft8_encode(const uint8_t* payload, uint8_t* tones, bool is_fox_multi) {
    uint8_t a91[FTX_LDPC_K_BYTES];
    ftx_add_crc(payload, a91);
    uint8_t codeword[FTX_LDPC_N_BYTES];
    encode174(a91, codeword);

    // [NEW] Установка 76-го бита (индекс 75) для режима Fox multi-call
    if (is_fox_multi) {
        // Бит 75 в массиве 174 бит = байт 9, бит 3 (нумерация с 0, слева направо)
        codeword[9] |= 0x10;  // Установить бит 3 в байте 9
    }
    // [END NEW]

    uint8_t mask = 0x80u;
    int i_byte = 0;
    for (int i_tone = 0; i_tone < FT8_NN; ++i_tone) {
        if ((i_tone >= 0) && (i_tone < 7)) {
            tones[i_tone] = kFT8CostasPattern[i_tone];
        } else if ((i_tone >= 36) && (i_tone < 43)) {
            tones[i_tone] = kFT8CostasPattern[i_tone - 36];
        } else if ((i_tone >= 72) && (i_tone < 79)) {
            tones[i_tone] = kFT8CostasPattern[i_tone - 72];
        } else {
            uint8_t bits3 = 0;
            if (codeword[i_byte] & mask) bits3 |= 4;
            if (0 == (mask >>= 1)) { mask = 0x80u; i_byte++; }
            if (codeword[i_byte] & mask) bits3 |= 2;
            if (0 == (mask >>= 1)) { mask = 0x80u; i_byte++; }
            if (codeword[i_byte] & mask) bits3 |= 1;
            if (0 == (mask >>= 1)) { mask = 0x80u; i_byte++; }
            tones[i_tone] = kFT8GrayMap[bits3];
        }
    }
}

void ft4_encode(const uint8_t* payload, uint8_t* tones) {
    uint8_t a91[FTX_LDPC_K_BYTES];
    uint8_t payload_xor[10];

    for (int i = 0; i < 10; ++i) {
        payload_xor[i] = payload[i] ^ kFT4XORSequence[i];
    }
    ftx_add_crc(payload_xor, a91);

    uint8_t codeword[FTX_LDPC_N_BYTES];
    encode174(a91, codeword);

    uint8_t mask = 0x80u;
    int i_byte = 0;
    for (int i_tone = 0; i_tone < FT4_NN; ++i_tone) {
        if ((i_tone == 0) || (i_tone == 104)) {
            tones[i_tone] = 0;
        } else if ((i_tone >= 1) && (i_tone < 5)) {
            tones[i_tone] = kFT4CostasPattern[0][i_tone - 1];
        } else if ((i_tone >= 34) && (i_tone < 38)) {
            tones[i_tone] = kFT4CostasPattern[1][i_tone - 34];
        } else if ((i_tone >= 67) && (i_tone < 71)) {
            tones[i_tone] = kFT4CostasPattern[2][i_tone - 67];
        } else if ((i_tone >= 100) && (i_tone < 104)) {
            tones[i_tone] = kFT4CostasPattern[3][i_tone - 100];
        } else {
            uint8_t bits2 = 0;
            if (codeword[i_byte] & mask) bits2 |= 2;
            if (0 == (mask >>= 1)) { mask = 0x80u; i_byte++; }
            if (codeword[i_byte] & mask) bits2 |= 1;
            if (0 == (mask >>= 1)) { mask = 0x80u; i_byte++; }
            tones[i_tone] = kFT4GrayMap[bits2];
        }
    }
}