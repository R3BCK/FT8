#ifndef DECODE_DX_H
#define DECODE_DX_H

#include <stdint.h>
#include <stdbool.h>

// [IMPORTANT] Включаем constants.h ПЕРВЫМ, чтобы получить все константы
#include "constants.h"

// [SAFETY] Если константы не определены в constants.h, определяем их здесь
// (это запасной вариант на случай, если constants.h не содержит этих определений)
#ifndef kMax_candidates
#define kMax_candidates 120
#endif
#ifndef kMax_decoded_messages
#define kMax_decoded_messages 50
#endif
#ifndef kMin_score
#define kMin_score 10
#endif
#ifndef FTX_LDPC_N
#define FTX_LDPC_N 174
#endif
#ifndef FTX_LDPC_K
#define FTX_LDPC_K 91
#endif
#ifndef FTX_LDPC_M
#define FTX_LDPC_M 83
#endif
#ifndef FTX_LDPC_N_BYTES
#define FTX_LDPC_N_BYTES ((FTX_LDPC_N + 7) / 8)
#endif
#ifndef FTX_LDPC_K_BYTES
#define FTX_LDPC_K_BYTES ((FTX_LDPC_K + 7) / 8)
#endif

#ifdef __cplusplus
extern "C" {
#endif

// [NEW] Поддержка мульти-сообщений Fox/Hound
#define FOX_MAX_CALLS 5

// [NEW] Тип для хэшей
typedef struct {
    uint32_t hash10;
    uint32_t hash12;
    uint32_t hash22;
} hashCode;

typedef struct {
    char text[20];
    char call_to[14];
    char call_de[14];
    char extra[19];
    char maidenGrid[7];
    int report;
    uint32_t hash;
    uint8_t a91[FTX_LDPC_K_BYTES];  // <-- FTX_LDPC_K_BYTES теперь гарантированно определён

    int i3;
    int n3;

    hashCode call_to_hash, call_de_hash;

    // [NEW] Поля для режима Fox multi-call
    bool is_fox_multi;
    char fox_calls[FOX_MAX_CALLS][14];
    int fox_calls_count;
    uint16_t fox_leader_hash;
} message_t;

typedef struct {
    int ldpc_errors;
    uint32_t crc_extracted;
    uint32_t crc_calculated;
    int unpack_status;
} decode_status_t;

typedef struct {
    int score;
    int time_offset;
    int freq_offset;
    int time_sub;
    int freq_sub;
    int snr;
} candidate_t;

typedef struct {
    int num_blocks;
    int block_stride;
    int num_bins;
    int time_osr;
    int freq_osr;
    int symbol_period;
    float max_mag;
    uint8_t *mag;
    float *mag2;
    int protocol;
} waterfall_t;

typedef struct {
    waterfall_t wf;
    int f_min;
    int f_max;
    int sample_rate;
    int time_osr;
    int freq_osr;
    int protocol;
    float symbol_period;
    int block_size;
} monitor_config_t;

typedef struct {
    waterfall_t wf;
    float max_mag;
    monitor_config_t cfg;
} monitor_t;

typedef struct {
    int64_t utcTime;
    int num_samples;
    monitor_config_t mon_cfg;
    monitor_t mon;
    int kLDPC_iterations;
    candidate_t candidate_list[kMax_candidates];  // <-- Теперь kMax_candidates гарантированно определён
    int num_candidates;
    message_t decoded[kMax_decoded_messages];     // <-- Теперь kMax_decoded_messages гарантированно определён
    message_t *decoded_hashtable[kMax_decoded_messages];
    int num_decoded;
    uint8_t a91[FTX_LDPC_K_BYTES];
} decoder_t;

// Public API
void monitor_init(monitor_t *mon, const monitor_config_t *cfg);
void monitor_free(monitor_t *mon);
void monitor_process(monitor_t *mon, const float *signal);

int ft8_find_sync(const waterfall_t *wf, int num_candidates,
                  candidate_t heap[], int min_score);

bool ft8_decode(waterfall_t *wf, candidate_t *cand, message_t *message,
                int max_iterations, decode_status_t *status);

// [NEW] Функции для режима Fox multi-call
int unpack_fox_multi(const uint8_t *a91, message_t *message);
int pack_fox_multi(const char calls_list[][14], int count, uint8_t *a91);

#ifdef __cplusplus
}
#endif

#endif // DECODE_DX_H