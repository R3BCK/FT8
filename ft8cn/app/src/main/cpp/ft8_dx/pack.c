#include "pack.h"
#include "text.h"
#include <stdbool.h>
#include <stdint.h>
#include <string.h>
#include <stdio.h>
#include "../common/debug.h"

#define NTOKENS  ((uint32_t)2063592L)
#define MAX22    ((uint32_t)4194304L)
#define MAXGRID4 ((uint16_t)32400)
#define FOX_MAX_CALLS 5

const char A0[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?";
const char A1[] = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const char A2[] = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
const char A3[] = "0123456789";
const char A4[] = " ABCDEFGHIJKLMNOPQRSTUVWXYZ";

int32_t pack28(const char* callsign) {
    if (starts_with(callsign, "DE ")) return 0;
    if (starts_with(callsign, "QRZ ")) return 1;
    if (starts_with(callsign, "CQ ")) return 2;

    if (starts_with(callsign, "CQ_")) {
        int nnum = 0, nlet = 0;
        // TODO: handle CQ_nnn
    }

    char c6[6] = { ' ', ' ', ' ', ' ', ' ', ' ' };
    int length = 0;
    while (callsign[length] != ' ' && callsign[length] != 0) length++;

    if (starts_with(callsign, "3DA0") && length <= 7) {
        memcpy(c6, "3D0", 3);
        memcpy(c6 + 3, callsign + 4, length - 4);
    } else if (starts_with(callsign, "3X") && is_letter(callsign[2]) && length <= 7) {
        memcpy(c6, "Q", 1);
        memcpy(c6 + 1, callsign + 2, length - 2);
    } else {
        if (is_digit(callsign[2]) && length <= 6) {
            memcpy(c6, callsign, length);
        } else if (is_digit(callsign[1]) && length <= 5) {
            memcpy(c6 + 1, callsign, length);
        }
    }

    int i0, i1, i2, i3, i4, i5;
    if ((i0 = char_index(A1, c6[0])) >= 0 && (i1 = char_index(A2, c6[1])) >= 0 &&
        (i2 = char_index(A3, c6[2])) >= 0 && (i3 = char_index(A4, c6[3])) >= 0 &&
        (i4 = char_index(A4, c6[4])) >= 0 && (i5 = char_index(A4, c6[5])) >= 0) {
        int32_t n28 = i0;
        n28 = n28 * 36 + i1;
        n28 = n28 * 10 + i2;
        n28 = n28 * 27 + i3;
        n28 = n28 * 27 + i4;
        n28 = n28 * 27 + i5;
        return NTOKENS + MAX22 + n28;
    }
    return -1;
}

bool chkcall(const char* call, char* bc) {
    int length = strlen(call);
    if (length > 11) return false;
    if (0 != strchr(call, '.')) return false;
    if (0 != strchr(call, '+')) return false;
    if (0 != strchr(call, '-')) return false;
    if (0 != strchr(call, '?')) return false;
    if (length > 6 && 0 != strchr(call, '/')) return false;
    return true;
}

uint16_t packgrid(const char* grid4) {
    if (grid4 == 0) return MAXGRID4 + 1;
    if (equals(grid4, "RRR")) return MAXGRID4 + 2;
    if (equals(grid4, "RR73")) return MAXGRID4 + 3;
    if (equals(grid4, "73")) return MAXGRID4 + 4;

    if (in_range(grid4[0], 'A', 'R') && in_range(grid4[1], 'A', 'R') &&
        is_digit(grid4[2]) && is_digit(grid4[3])) {
        uint16_t igrid4 = (grid4[0] - 'A');
        igrid4 = igrid4 * 18 + (grid4[1] - 'A');
        igrid4 = igrid4 * 10 + (grid4[2] - '0');
        igrid4 = igrid4 * 10 + (grid4[3] - '0');
        return igrid4;
    }

    if (grid4[0] == 'R') {
        int dd = dd_to_int(grid4 + 1, 3);
        uint16_t irpt = 35 + dd;
        return (MAXGRID4 + irpt) | 0x8000;
    } else {
        int dd = dd_to_int(grid4, 3);
        uint16_t irpt = 35 + dd;
        return (MAXGRID4 + irpt);
    }
    return MAXGRID4 + 1;
}

int pack77_1(const char* msg, uint8_t* b77) {
    const char* s1 = strchr(msg, ' ');
    if (s1 == 0) return -1;
    const char* call1 = msg;
    const char* call2 = s1 + 1;

    int32_t n28a = pack28(call1);
    int32_t n28b = pack28(call2);
    if (n28a < 0 || n28b < 0) return -1;

    uint16_t igrid4;
    const char* s2 = strchr(s1 + 1, ' ');
    if (s2 != 0) {
        igrid4 = packgrid(s2 + 1);
    } else {
        igrid4 = packgrid(0);
    }

    uint8_t i3 = 1;
    n28a <<= 1;
    n28b <<= 1;

    b77[0] = (n28a >> 21);
    b77[1] = (n28a >> 13);
    b77[2] = (n28a >> 5);
    b77[3] = (uint8_t)(n28a << 3) | (uint8_t)(n28b >> 26);
    b77[4] = (n28b >> 18);
    b77[5] = (n28b >> 10);
    b77[6] = (n28b >> 2);
    b77[7] = (uint8_t)(n28b << 6) | (uint8_t)(igrid4 >> 10);
    b77[8] = (igrid4 >> 2);
    b77[9] = (uint8_t)(igrid4 << 6) | (uint8_t)(i3 << 3);
    return 0;
}

void packtext77(const char* text, uint8_t* b77) {
    int length = strlen(text);
    while (*text == ' ' && *text != 0) { ++text; --length; }
    while (length > 0 && text[length - 1] == ' ') { --length; }

    for (int i = 0; i < 9; ++i) b77[i] = 0;

    for (int j = 0; j < 13; ++j) {
        uint16_t x = 0;
        for (int i = 8; i >= 0; --i) {
            x += b77[i] * (uint16_t)42;
            b77[i] = (x & 0xFF);
            x >>= 8;
        }
        if (j < length) {
            int q = char_index(A0, text[j]);
            x = (q > 0) ? q : 0;
        } else {
            x = 0;
        }
        x <<= 1;
        for (int i = 8; i >= 0; --i) {
            if (x == 0) break;
            x += b77[i];
            b77[i] = (x & 0xFF);
            x >>= 8;
        }
    }
    b77[8] &= 0xFE;
    b77[9] &= 0x00;
}

// [NEW] Упаковка мульти-сообщения Fox (полная версия)
int pack_fox_multi(const char calls_list[][14], int count, uint8_t *a91) {
    if (count <= 0 || count > FOX_MAX_CALLS) return -1;

    memset(a91, 0, FTX_LDPC_K_BYTES);

    // Байт 0: количество позывных (биты 0-2) + флаг (бит 3 = 1 для Fox)
    a91[0] = (count & 0x07) | 0x08;  // Установить бит 3 = 1 (флаг Fox)

    // Байты 1-70: список позывных (по 14 байт на каждый)
    for (int i = 0; i < count; i++) {
        int offset = 1 + i * 14;
        strncpy((char*)(a91 + offset), calls_list[i], 13);
        a91[offset + 13] = '\0';
    }

    // Байты 71-72: хэш ведущей (заглушка)
    a91[71] = 0x00;
    a91[72] = 0x00;

    // Установить 76-й бит (индекс 75) в 1
    a91[9] |= 0x10;  // Бит 3 в байте 9

    return 0;
}

int pack77(const char* msg, uint8_t* c77) {
    if (0 == pack77_1(msg, c77)) return 0;
    packtext77(msg, c77);
    return 0;
}