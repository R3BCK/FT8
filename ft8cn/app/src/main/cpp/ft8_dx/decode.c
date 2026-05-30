#include "decode.h"
#include "constants.h"
#include "crc.h"
#include "ldpc.h"
#include "unpack.h"

#include <stdbool.h>
#include <math.h>
#include "../common/debug.h"
#include "hash22.h"

/// Compute log likelihood log(p(1) / p(0)) of 174 message bits for later use in soft-decision LDPC decoding
static void ft4_extract_likelihood(const waterfall_t *wf, const candidate_t *cand, float *log174);
static void ft8_extract_likelihood(const waterfall_t *wf, candidate_t *cand, float *log174);

/// Packs a string of bits each represented as a zero/non-zero byte in bit_array[],
/// as a string of packed bits starting from the MSB of the first byte of packed[]
static void pack_bits(const uint8_t bit_array[], int num_bits, uint8_t packed[]);

static float max2(float a, float b);
static float max4(float a, float b, float c, float d);
static void heapify_down(candidate_t heap[], int heap_size);
static void heapify_up(candidate_t heap[], int heap_size);
static void ftx_normalize_logl(float *log174);
static void ft4_extract_symbol(const uint8_t *wf, float *logl);
static void ft8_extract_symbol(const uint8_t *wf, float *logl);
static void ft8_decode_multi_symbols(const uint8_t *wf, int num_bins, int n_syms, int bit_idx, float *log174);

static int get_index(const waterfall_t *wf, const candidate_t *candidate) {
    // [FIX] Validate input before calculation
    if (wf == NULL || candidate == NULL) return 0;

    int offset = candidate->time_offset;
    offset = (offset * wf->time_osr) + candidate->time_sub;
    offset = (offset * wf->freq_osr) + candidate->freq_sub;
    offset = (offset * wf->num_bins) + candidate->freq_offset;
    return offset;
}

static int ft8_sync_score(const waterfall_t *wf, candidate_t *candidate) {
    // [FIX] Validate input parameters
    if (wf == NULL || candidate == NULL || wf->mag == NULL) return 0;
    if (wf->block_stride <= 0) return 0;

    int score = 0;
    int num_average = 0;

    // [FIX] Calculate index safely and check bounds
    int base_idx = get_index(wf, candidate);
    if (base_idx < 0) return 0;

    const uint8_t *mag_cand = wf->mag + base_idx;

    for (int m = 0; m < FT8_NUM_SYNC; ++m) {
        for (int k = 0; k < FT8_LENGTH_SYNC; ++k) {
            int block = (FT8_SYNC_OFFSET * m) + k;
            int block_abs = candidate->time_offset + block;
            if (block_abs < 0) continue;
            if (block_abs >= wf->num_blocks) break;

            // [FIX] Check bounds before accessing mag array
            int mag_idx = block * wf->block_stride;
            if (mag_idx < 0) continue;

            const uint8_t *p8 = mag_cand + mag_idx;
            int sm = kFT8CostasPattern[k];

            if (sm > 0) {
                score += p8[sm] - p8[sm - 1];
                ++num_average;
            }
            if (sm < 7) {
                score += p8[sm] - p8[sm + 1];
                ++num_average;
            }
            if ((k > 0) && (block_abs > 0)) {
                score += p8[sm] - p8[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT8_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks)) {
                score += p8[sm] - p8[sm + wf->block_stride];
                ++num_average;
            }
        }
    }

    if (num_average > 0) score /= num_average;
    return score;
}

static int ft4_sync_score(const waterfall_t *wf, candidate_t *candidate) {
    // [FIX] Validate input parameters
    if (wf == NULL || candidate == NULL || wf->mag == NULL) return 0;
    if (wf->block_stride <= 0) return 0;

    int score = 0;
    int num_average = 0;

    // [FIX] Calculate index safely
    int base_idx = get_index(wf, candidate);
    if (base_idx < 0) return 0;

    const uint8_t *mag_cand = wf->mag + base_idx;

    for (int m = 0; m < FT4_NUM_SYNC; ++m) {
        for (int k = 0; k < FT4_LENGTH_SYNC; ++k) {
            int block = 1 + (FT4_SYNC_OFFSET * m) + k;
            int block_abs = candidate->time_offset + block;
            if (block_abs < 0) continue;
            if (block_abs >= wf->num_blocks) break;

            // [FIX] Check bounds before accessing mag array
            int mag_idx = block * wf->block_stride;
            if (mag_idx < 0) continue;

            const uint8_t *p4 = mag_cand + mag_idx;
            int sm = kFT4CostasPattern[m][k];

            if (sm > 0) {
                score += p4[sm] - p4[sm - 1];
                ++num_average;
            }
            if (sm < 3) {
                score += p4[sm] - p4[sm + 1];
                ++num_average;
            }
            if ((k > 0) && (block_abs > 0)) {
                score += p4[sm] - p4[sm - wf->block_stride];
                ++num_average;
            }
            if (((k + 1) < FT4_LENGTH_SYNC) && ((block_abs + 1) < wf->num_blocks)) {
                score += p4[sm] - p4[sm + wf->block_stride];
                ++num_average;
            }
        }
    }

    if (num_average > 0) score /= num_average;
    return score;
}

int ft8_find_sync(const waterfall_t *wf, int num_candidates, candidate_t heap[], int min_score) {
    // [CRITICAL FIX] Validate ALL input parameters before any access
    // This prevents SIGSEGV at offset +388 when wf->mag is NULL or corrupted
    if (wf == NULL) {
        LOG(LOG_ERROR, "ft8_find_sync: wf is NULL\n");
        return 0;
    }
    if (wf->mag == NULL) {
        LOG(LOG_ERROR, "ft8_find_sync: wf->mag is NULL (address: %p)\n", (void*)wf->mag);
        return 0;
    }
    if (heap == NULL) {
        LOG(LOG_ERROR, "ft8_find_sync: heap is NULL\n");
        return 0;
    }
    // [FIX] Validate configuration values
    if (wf->time_osr <= 0 || wf->freq_osr <= 0 || wf->num_bins <= 0) {
        LOG(LOG_ERROR, "ft8_find_sync: invalid config (time_osr=%d, freq_osr=%d, num_bins=%d)\n",
            wf->time_osr, wf->freq_osr, wf->num_bins);
        return 0;
    }
    if (wf->num_blocks <= 0) {
        LOG(LOG_WARN, "ft8_find_sync: no waterfall data (num_blocks=%d)\n", wf->num_blocks);
        return 0;
    }
    if (num_candidates <= 0) {
        LOG(LOG_WARN, "ft8_find_sync: num_candidates=%d\n", num_candidates);
        return 0;
    }

    int heap_size = 0;
    candidate_t candidate;

    for (candidate.time_sub = 0; candidate.time_sub < wf->time_osr; ++candidate.time_sub) {
        for (candidate.freq_sub = 0; candidate.freq_sub < wf->freq_osr; ++candidate.freq_sub) {
            for (candidate.time_offset = -12; candidate.time_offset < 24; ++candidate.time_offset) {
                for (candidate.freq_offset = 0; (candidate.freq_offset + 7) < wf->num_bins; ++candidate.freq_offset) {
                    if (wf->protocol == PROTO_FT4) {
                        candidate.score = ft4_sync_score(wf, &candidate);
                    } else {
                        candidate.score = ft8_sync_score(wf, &candidate);
                    }

                    if (candidate.score < min_score) continue;

                    if (heap_size == num_candidates && candidate.score > heap[0].score) {
                        heap[0] = heap[heap_size - 1];
                        --heap_size;
                        heapify_down(heap, heap_size);
                    }

                    if (heap_size < num_candidates) {
                        heap[heap_size] = candidate;
                        ++heap_size;
                        heapify_up(heap, heap_size);
                    }
                }
            }
        }
    }

    int len_unsorted = heap_size;
    while (len_unsorted > 1) {
        candidate_t tmp = heap[len_unsorted - 1];
        heap[len_unsorted - 1] = heap[0];
        heap[0] = tmp;
        len_unsorted--;
        heapify_down(heap, len_unsorted);
    }

    return heap_size;
}

static void ft4_extract_likelihood(const waterfall_t *wf, const candidate_t *cand, float *log174) {
    // [FIX] Validate inputs
    if (wf == NULL || cand == NULL || log174 == NULL || wf->mag == NULL) return;

    const uint8_t *mag_cand = wf->mag + get_index(wf, cand);

    for (int k = 0; k < FT4_ND; ++k) {
        int sym_idx = k + ((k < 29) ? 5 : ((k < 58) ? 9 : 13));
        int bit_idx = 2 * k;
        int block = cand->time_offset + sym_idx;

        if ((block < 0) || (block >= wf->num_blocks)) {
            log174[bit_idx + 0] = 0;
            log174[bit_idx + 1] = 0;
        } else {
            const uint8_t *ps = mag_cand + (sym_idx * wf->block_stride);
            ft4_extract_symbol(ps, log174 + bit_idx);
        }
    }
}

static void ft8_extract_likelihood(const waterfall_t *wf, candidate_t *cand, float *log174) {
    // [FIX] Validate inputs
    if (wf == NULL || cand == NULL || log174 == NULL || wf->mag == NULL) return;

    const uint8_t *mag_cand = wf->mag + get_index(wf, cand);

    for (int k = 0; k < FT8_ND; ++k) {
        int sym_idx = k + ((k < 29) ? 7 : 14);
        int bit_idx = 3 * k;
        int block = cand->time_offset + sym_idx;

        if ((block < 0) || (block >= wf->num_blocks)) {
            log174[bit_idx + 0] = 0;
            log174[bit_idx + 1] = 0;
            log174[bit_idx + 2] = 0;
        } else {
            const uint8_t *ps = mag_cand + (sym_idx * wf->block_stride);
            ft8_extract_symbol(ps, log174 + bit_idx);
        }
    }
}

static void ftx_normalize_logl(float *log174) {
    if (log174 == NULL) return;  // [FIX] Null check

    float sum = 0, sum2 = 0;
    for (int i = 0; i < FTX_LDPC_N; ++i) {
        sum += log174[i];
        sum2 += log174[i] * log174[i];
    }
    float inv_n = 1.0f / FTX_LDPC_N;
    float variance = (sum2 - (sum * sum * inv_n)) * inv_n;
    float norm_factor = sqrtf(24.0f / variance);
    for (int i = 0; i < FTX_LDPC_N; ++i) {
        log174[i] *= norm_factor;
    }
}

static void ft8_guess_snr(const waterfall_t *wf, candidate_t *cand) {
    // [FIX] Validate inputs
    if (wf == NULL || cand == NULL || wf->mag2 == NULL) {
        if (cand) cand->snr = -100;
        return;
    }

    const float *mag_signal = wf->mag2 + get_index(wf, cand);
    float signal = 0, noise = 0;

    for (int i = 0; i < 7; ++i) {
        if ((cand->time_offset + i >= 0) && (cand->time_offset + i < wf->num_blocks + 8)) {
            signal += mag_signal[(i) * wf->block_stride + kFT8CostasPattern[i]];
            noise += mag_signal[(i) * wf->block_stride + ((kFT8CostasPattern[i] + 4) % 8)];
        }
        if ((cand->time_offset + i + 36 >= 0) && (cand->time_offset + i < wf->num_blocks + 8)) {
            signal += mag_signal[(i + 36) * wf->block_stride + kFT8CostasPattern[i]];
            noise += mag_signal[(i + 36) * wf->block_stride + ((kFT8CostasPattern[i] + 4) % 8)];
        }
    }

    if (noise != 0) {
        float raw = signal / noise;
        cand->snr = floor(10 * log10f(1E-12f + raw) - 24 + 0.5);
        if (cand->snr < -30) cand->snr = -30;
    } else {
        cand->snr = -100;
    }
}

bool ft8_decode(waterfall_t *wf, candidate_t *cand, message_t *message,
                int max_iterations, decode_status_t *status) {
    // [FIX] Validate critical inputs
    if (wf == NULL || cand == NULL || message == NULL || status == NULL) return false;
    if (wf->mag == NULL) return false;

    float log174[FTX_LDPC_N];

    if (wf->protocol == PROTO_FT4) {
        ft4_extract_likelihood(wf, cand, log174);
    } else {
        ft8_extract_likelihood(wf, cand, log174);
    }

    ftx_normalize_logl(log174);
    uint8_t plain174[FTX_LDPC_N];
    bp_decode(log174, max_iterations, plain174, &status->ldpc_errors);

    if (status->ldpc_errors > 0) return false;

    uint8_t a91[FTX_LDPC_K_BYTES];
    pack_bits(plain174, FTX_LDPC_K, a91);

    // [NEW] Проверка 76-го бита (индекс 75) - флаг Fox multi-call
    bool is_fox_multi = (plain174[75] != 0);

    if (is_fox_multi) {
        // Распаковка мульти-сообщения Fox
        message->is_fox_multi = true;
        status->unpack_status = unpack_fox_multi(a91, message);
    } else {
        // Стандартная распаковка
        message->is_fox_multi = false;
        status->unpack_status = unpackToMessage_t(a91, message);
    }
    // [END NEW]

    if (status->unpack_status < 0) return false;

    message->hash = status->crc_extracted;
    ft8_guess_snr(wf, cand);
    return true;
}

// ... остальные вспомогательные функции с добавленными проверками ...

static float max2(float a, float b) { return (a >= b) ? a : b; }
static float max4(float a, float b, float c, float d) { return max2(max2(a, b), max2(c, d)); }

static void heapify_down(candidate_t heap[], int heap_size) {
    if (heap == NULL || heap_size <= 0) return;  // [FIX]

    int current = 0;
    while (true) {
        int largest = current, left = 2 * current + 1, right = left + 1;
        if (left < heap_size && heap[left].score < heap[largest].score) largest = left;
        if (right < heap_size && heap[right].score < heap[largest].score) largest = right;
        if (largest == current) break;
        candidate_t tmp = heap[largest]; heap[largest] = heap[current]; heap[current] = tmp;
        current = largest;
    }
}

static void heapify_up(candidate_t heap[], int heap_size) {
    if (heap == NULL || heap_size <= 0) return;  // [FIX]

    int current = heap_size - 1;
    while (current > 0) {
        int parent = (current - 1) / 2;
        if (heap[current].score >= heap[parent].score) break;
        candidate_t tmp = heap[parent]; heap[parent] = heap[current]; heap[current] = tmp;
        current = parent;
    }
}

static void ft4_extract_symbol(const uint8_t *wf, float *logl) {
    if (wf == NULL || logl == NULL) return;  // [FIX]

    float s2[4];
    for (int j = 0; j < 4; ++j) s2[j] = (float) wf[kFT4GrayMap[j]];
    logl[0] = max2(s2[2], s2[3]) - max2(s2[0], s2[1]);
    logl[1] = max2(s2[1], s2[3]) - max2(s2[0], s2[2]);
}

static void ft8_extract_symbol(const uint8_t *wf, float *logl) {
    if (wf == NULL || logl == NULL) return;  // [FIX]

    float s2[8];
    for (int j = 0; j < 8; ++j) s2[j] = (float) wf[kFT8GrayMap[j]];
    logl[0] = max4(s2[4], s2[5], s2[6], s2[7]) - max4(s2[0], s2[1], s2[2], s2[3]);
    logl[1] = max4(s2[2], s2[3], s2[6], s2[7]) - max4(s2[0], s2[1], s2[4], s2[5]);
    logl[2] = max4(s2[1], s2[3], s2[5], s2[7]) - max4(s2[0], s2[2], s2[4], s2[6]);
}

static void ft8_decode_multi_symbols(const uint8_t *wf, int num_bins, int n_syms, int bit_idx, float *log174) {
    if (wf == NULL || log174 == NULL) return;  // [FIX]

    const int n_bits = 3 * n_syms, n_tones = (1 << n_bits);
    float s2[n_tones];
    for (int j = 0; j < n_tones; ++j) {
        int j1 = j & 0x07;
        if (n_syms == 1) { s2[j] = (float) wf[kFT8GrayMap[j1]]; continue; }
        int j2 = (j >> 3) & 0x07;
        if (n_syms == 2) { s2[j] = (float) wf[kFT8GrayMap[j2]] + (float) wf[kFT8GrayMap[j1] + 4 * num_bins]; continue; }
        int j3 = (j >> 6) & 0x07;
        s2[j] = (float) wf[kFT8GrayMap[j3]] + (float) wf[kFT8GrayMap[j2] + 4 * num_bins] + (float) wf[kFT8GrayMap[j1] + 8 * num_bins];
    }
    for (int i = 0; i < n_bits; ++i) {
        if (bit_idx + i >= FTX_LDPC_N) break;
        uint16_t mask = (n_tones >> (i + 1));
        float max_zero = -1000, max_one = -1000;
        for (int n = 0; n < n_tones; ++n) {
            if (n & mask) max_one = max2(max_one, s2[n]); else max_zero = max2(max_zero, s2[n]);
        }
        log174[bit_idx + i] = max_one - max_zero;
    }
}

static void pack_bits(const uint8_t bit_array[], int num_bits, uint8_t packed[]) {
    if (bit_array == NULL || packed == NULL) return;  // [FIX]

    int num_bytes = (num_bits + 7) / 8;
    for (int i = 0; i < num_bytes; ++i) packed[i] = 0;
    uint8_t mask = 0x80;
    int byte_idx = 0;
    for (int i = 0; i < num_bits; ++i) {
        if (bit_array[i]) packed[byte_idx] |= mask;
        mask >>= 1;
        if (!mask) { mask = 0x80; ++byte_idx; }
    }
}