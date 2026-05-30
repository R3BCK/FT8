#ifdef __linux__
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#endif

#include "unpack.h"
#include "text.h"
#include <string.h>
#include "hash22.h"

#define MAX22    ((uint32_t)4194304L)
#define NTOKENS  ((uint32_t)2063592L)
#define MAXGRID4 ((uint16_t)32400L)
#define FOX_MAX_CALLS 5

// n28 is a 28-bit integer, e.g. n28a or n28b, containing all the
// call sign bits from a packed message.
int unpack_callsign(uint32_t n28, uint8_t ip, uint8_t i3, char *result, hashCode * hash) {
    hash->hash10 = 0;
    hash->hash12 = 0;
    hash->hash22 = 0;

    if (n28 < NTOKENS) {
        if (n28 <= 2) {
            if (n28 == 0) strcpy(result, "DE");
            if (n28 == 1) strcpy(result, "QRZ");
            if (n28 == 2) strcpy(result, "CQ");
            return 0;
        }
        if (n28 <= 1002) {
            strcpy(result, "CQ ");
            int_to_dd(result + 3, n28 - 3, 3, false);
            return 0;
        }
        if (n28 <= 532443L) {
            uint32_t n = n28 - 1003;
            char aaaa[5];
            aaaa[4] = '\0';
            for (int i = 3; ; --i) {
                aaaa[i] = charn(n % 27, 4);
                if (i == 0) break;
                n /= 27;
            }
            strcpy(result, "CQ ");
            strcat(result, trim_front(aaaa));
            return 0;
        }
        return -1;
    }

    n28 = n28 - NTOKENS;
    if (n28 < MAX22) {
        hash->hash10 = n28;
        hash->hash12 = n28;
        hash->hash22 = n28;
        strcpy(result, "<...>");
        return 0;
    }

    uint32_t n = n28 - MAX22;
    char callsign[7];
    callsign[6] = '\0';
    callsign[5] = charn(n % 27, 4);
    n /= 27;
    callsign[4] = charn(n % 27, 4);
    n /= 27;
    callsign[3] = charn(n % 27, 4);
    n /= 27;
    callsign[2] = charn(n % 10, 3);
    n /= 10;
    callsign[1] = charn(n % 36, 2);
    n /= 36;
    callsign[0] = charn(n % 37, 1);

    strcpy(result, trim(callsign));
    if (strlen(result) == 0) return -1;

    hash->hash10 = hashcall_10(result);
    hash->hash12 = hashcall_12(result);
    hash->hash22 = hashcall_22(result);

    if (ip) {
        if (i3 == 1) strcat(result, "/R");
        else if (i3 == 2) strcat(result, "/P");
    }
    return 0;
}

int unpack_type1_(const uint8_t *a77, message_t *message) {
    uint32_t n28a, n28b;
    uint16_t igrid4;
    uint8_t ir;

    n28a = (a77[0] << 21) | (a77[1] << 13) | (a77[2] << 5) | (a77[3] >> 3);
    n28b = ((a77[3] & 0x07) << 26) | (a77[4] << 18) | (a77[5] << 10) | (a77[6] << 2) | (a77[7] >> 6);
    ir = ((a77[7] & 0x20) >> 5);
    igrid4 = ((a77[7] & 0x1F) << 10) | (a77[8] << 2) | (a77[9] >> 6);

    if (unpack_callsign(n28a >> 1, n28a & 0x01, message->i3, message->call_to, &message->call_to_hash) < 0) return -1;
    if (unpack_callsign(n28b >> 1, n28b & 0x01, message->i3, message->call_de, &message->call_de_hash) < 0) return -2;

    char *dst = message->extra;
    message->report = -100;
    message->maidenGrid[0] = '\0';

    if (igrid4 <= MAXGRID4) {
        if (ir > 0) {
            dst = strcpy(dst, "R ");
            dst += 3;
        }
        uint16_t n = igrid4;
        dst[4] = '\0';
        dst[3] = '0' + (n % 10);
        n /= 10;
        dst[2] = '0' + (n % 10);
        n /= 10;
        dst[1] = 'A' + (n % 18);
        n /= 18;
        dst[0] = 'A' + (n % 18);
        strcpy(message->maidenGrid, dst);
    } else {
        message->report = igrid4 - MAXGRID4 - 35;
        switch (message->report) {
            case 1 - 35: message->extra[0] = '\0'; break;
            case 2 - 35: strcpy(dst, "RRR"); break;
            case 3 - 35: strcpy(dst, "RR73"); break;
            case 4 - 35: strcpy(dst, "73"); break;
            default:
                if (ir > 0) *dst++ = 'R';
                int_to_dd(dst, message->report, 2, true);
                break;
        }
    }
    return 0;
}

int unpack_text(const uint8_t *a71, char *text) {
    uint8_t b71[9];
    uint8_t carry = 0;
    for (int i = 0; i < 9; ++i) {
        b71[i] = carry | (a71[i] >> 1);
        carry = (a71[i] & 1) ? 0x80 : 0;
    }
    char c14[14];
    c14[13] = 0;
    for (int idx = 12; idx >= 0; --idx) {
        uint16_t rem = 0;
        for (int i = 0; i < 9; ++i) {
            rem = (rem << 8) | b71[i];
            b71[i] = rem / 42;
            rem = rem % 42;
        }
        c14[idx] = charn(rem, 0);
    }
    strcpy(text, trim(c14));
    return 0;
}

int unpack_telemetry(const uint8_t *a71, char *telemetry) {
    uint8_t b71[9];
    uint8_t carry = 0;
    for (int i = 0; i < 9; ++i) {
        b71[i] = (carry << 7) | (a71[i] >> 1);
        carry = (a71[i] & 0x01);
    }
    for (int i = 0; i < 9; ++i) {
        uint8_t nibble1 = (b71[i] >> 4);
        uint8_t nibble2 = (b71[i] & 0x0F);
        char c1 = (nibble1 > 9) ? (nibble1 - 10 + 'A') : nibble1 + '0';
        char c2 = (nibble2 > 9) ? (nibble2 - 10 + 'A') : nibble2 + '0';
        telemetry[i * 2] = c1;
        telemetry[i * 2 + 1] = c2;
    }
    telemetry[18] = '\0';
    return 0;
}

int unpack_nonstandard(const uint8_t *a77, message_t *message) {
    uint32_t n12, iflip, nrpt, icq;
    uint64_t n58;

    n12 = (a77[0] << 4) | (a77[1] >> 4);
    n58 = ((uint64_t)(a77[1] & 0x0F) << 54) | ((uint64_t)a77[2] << 46) | ((uint64_t)a77[3] << 38) |
          ((uint64_t)a77[4] << 30) | ((uint64_t)a77[5] << 22) | ((uint64_t)a77[6] << 14) |
          ((uint64_t)a77[7] << 6) | ((uint64_t)a77[8] >> 2);
    iflip = (a77[8] >> 1) & 0x01;
    nrpt = ((a77[8] & 0x01) << 1) | (a77[9] >> 7);
    icq = ((a77[9] >> 6) & 0x01);

    if (iflip == 1) message->call_de_hash.hash12 = n12;
    else message->call_to_hash.hash12 = n12;

    char c11[12];
    c11[11] = '\0';
    for (int i = 10; ; --i) {
        c11[i] = charn(n58 % 38, 5);
        if (i == 0) break;
        n58 /= 38;
    }

    char call_3[15];
    strcpy(call_3, "<...>");
    char *call_1 = (iflip) ? c11 : call_3;
    char *call_2 = (iflip) ? call_3 : c11;

    if (icq == 0) {
        strcpy(message->call_to, trim(call_1));
        if (nrpt == 1) strcpy(message->extra, "RRR");
        else if (nrpt == 2) strcpy(message->extra, "RR73");
        else if (nrpt == 3) strcpy(message->extra, "73");
        else message->extra[0] = '\0';
    } else {
        strcpy(message->call_to, "CQ");
        message->extra[0] = '\0';
    }
    strcpy(message->call_de, trim(call_2));
    return 0;
}

// [NEW] Распаковка мульти-сообщения Fox (полная версия)
int unpack_fox_multi(const uint8_t *a91, message_t *message) {
    message->fox_calls_count = 0;
    message->fox_leader_hash = 0;
    message->is_fox_multi = true;

    // Байт 0: количество позывных (биты 0-2) + флаг (бит 3 = 1 для Fox)
    int count = a91[0] & 0x07;
    if (count > FOX_MAX_CALLS) count = FOX_MAX_CALLS;
    message->fox_calls_count = count;

    // Байты 1-70: список позывных (по 14 байт на каждый)
    for (int i = 0; i < count; i++) {
        int offset = 1 + i * 14;
        strncpy(message->fox_calls[i], (char*)(a91 + offset), 13);
        message->fox_calls[i][13] = '\0';
        trim(message->fox_calls[i]);
    }

    // Байты 71-72: хэш ведущей станции (упрощённо)
    message->fox_leader_hash = (a91[71] << 8) | a91[72];

    // Заполнить стандартные поля для совместимости
    if (count > 0) {
        strcpy(message->call_to, message->fox_calls[0]);
    } else {
        message->call_to[0] = '\0';
    }
    strcpy(message->call_de, "FOX");
    strcpy(message->extra, "[MULTI]");
    message->report = -100;
    message->maidenGrid[0] = '\0';

    return 0;
}

int unpack77_fields_(const uint8_t *a77, message_t *message) {
    message->i3 = (a77[9] >> 3) & 0x07;
    message->n3 = 0;

    if (message->i3 == 0) {
        message->n3 = ((a77[8] << 2) & 0x04) | ((a77[9] >> 6) & 0x03);
        if (message->n3 == 0) {
            return unpack_text(a77, message->extra);
        } else if (message->n3 == 5) {
            return unpack_telemetry(a77, message->extra);
        }
    } else if (message->i3 == 1 || message->i3 == 2) {
        return unpack_type1_(a77, message);
    } else if (message->i3 == 4) {
        return unpack_nonstandard(a77, message);
    }
    return -1;
}

int unpackToMessage_t(const uint8_t *a77, message_t *message) {
    int rc = unpack77_fields_(a77, message);
    if (rc < 0) return rc;

    char *dst = message->text;
    message->text[0] = '\0';

    if (message->call_to[0] != '\0') {
        dst = strcpy(dst, message->call_to);
        dst += strlen(message->call_to);
        *dst++ = ' ';
    }
    if (message->call_de[0] != '\0') {
        dst = strcpy(dst, message->call_de);
        dst += strlen(message->call_de);
        *dst++ = ' ';
    }
    dst = strcpy(dst, message->extra);
    dst += strlen(message->extra);
    *dst = '\0';

    return 0;
}